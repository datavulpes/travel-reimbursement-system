package com.ts.travelserver.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.entity.BizResult;
import com.ts.travelserver.entity.CostRef;
import com.ts.travelserver.entity.EvalResult;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.ai.AiSemanticEvaluator;
import com.ts.travelserver.config.AiConfig;
import com.ts.travelserver.eval.factor.CompletenessFactor;
import com.ts.travelserver.eval.factor.CostRatioFactor;
import com.ts.travelserver.eval.factor.FrequencyFactor;
import com.ts.travelserver.eval.factor.HistoryYieldFactor;
import com.ts.travelserver.mapper.BizResultMapper;
import com.ts.travelserver.mapper.CostRefMapper;
import com.ts.travelserver.mapper.EvalResultMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.vo.AiSemanticVO;
import com.ts.travelserver.vo.EvalResultVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 合理性评价引擎（模块 05）。
 *
 * <p>四因子加权：F1 历史产出率 0.35、F2 成本占对象产出比 0.30、
 * F3 频次异常度 0.20、F4 事由完整度 0.15，权重在 application.yml 里可调。
 * 总分 ≥75 建议、55~74 提示、＜55 关注。
 *
 * <p>两个入口：{@link #preview(EvalContext)} 只算分不落库（G-02 提交前预演），
 * {@link #evaluateAndSave(EvalContext)} 算完写进 eval_result（A-02 提交时）。
 * 一申请一条，重算覆盖（BR-08）。
 *
 * <p>TODO-答辩可讲：把权重放在配置里，现场改 travel.eval.weight 就能演示
 * 「同一趟出差，看重成本还是看重产出，结论会不一样」。
 */
@Slf4j
@Service
public class EvalEngine {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final List<EvalFactor> factors;
    private final EvalResultMapper evalResultMapper;
    private final EvalExplain evalExplain;
    private final TripApplyMapper tripApplyMapper;
    private final BizResultMapper bizResultMapper;
    private final CostRefMapper costRefMapper;
    private final AiSemanticEvaluator aiSemanticEvaluator;
    private final AiConfig aiConfig;

    @Value("${travel.eval.level.suggest-threshold:75}")
    private int suggestThreshold;

    @Value("${travel.eval.level.notice-threshold:55}")
    private int noticeThreshold;

    /** 上一趟无进展且间隔小于这个天数，事前提示命中 */
    @Value("${travel.eval.no-progress-gap-days:15}")
    private long noProgressGapDays;

    /** 30 天内第 N 次去同一对象，事前提示命中 */
    @Value("${travel.eval.freq-window-days:30}")
    private long freqWindowDays;

    @Value("${travel.eval.freq-threshold:3}")
    private int freqThreshold;

    public EvalEngine(HistoryYieldFactor historyYieldFactor,
                      CostRatioFactor costRatioFactor,
                      FrequencyFactor frequencyFactor,
                      CompletenessFactor completenessFactor,
                      EvalResultMapper evalResultMapper,
                      EvalExplain evalExplain,
                      TripApplyMapper tripApplyMapper,
                      BizResultMapper bizResultMapper,
                      CostRefMapper costRefMapper,
                      AiSemanticEvaluator aiSemanticEvaluator,
                      AiConfig aiConfig) {
        // 顺序写死：F1 → F4，前端按这个顺序展示，别用 Spring 注入 List 的默认顺序
        this.factors = List.of(historyYieldFactor, costRatioFactor, frequencyFactor, completenessFactor);
        this.evalResultMapper = evalResultMapper;
        this.evalExplain = evalExplain;
        this.tripApplyMapper = tripApplyMapper;
        this.bizResultMapper = bizResultMapper;
        this.costRefMapper = costRefMapper;
        this.aiSemanticEvaluator = aiSemanticEvaluator;
        this.aiConfig = aiConfig;
    }

    /**
     * 事前预演：只算分，不落库（G-02）。
     */
    public EvalResultVO preview(EvalContext ctx) {
        // 预演是「边填边看」，默认不烧 token；要带 AI 结论就改 travel.ai.preview-enabled
        Outcome outcome = compute(ctx, aiConfig.isPreviewEnabled());
        return toVO(outcome.getResult(), outcome.getFactors(), outcome.getHints());
    }

    /**
     * 算分并落库（A-02 提交时调用）。一申请一条，重算覆盖。
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalResult evaluateAndSave(EvalContext ctx) {
        Outcome outcome = compute(ctx, true);
        EvalResult entity = outcome.getResult();
        String applyNo = ctx.getApplyNo();
        if (applyNo == null || applyNo.isBlank()) {
            return entity;
        }
        EvalResult old = evalResultMapper.selectOne(
                new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getApplyNo, applyNo));
        if (old == null) {
            evalResultMapper.insert(entity);
        } else {
            entity.setId(old.getId());
            evalResultMapper.updateById(entity);
        }
        return entity;
    }

    /**
     * 查某单的评价结论（G-01）。
     */
    public EvalResultVO get(String applyNo) {
        EvalResult entity = evalResultMapper.selectOne(
                new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getApplyNo, applyNo));
        if (entity == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该申请还没有评价结果");
        }
        return toVO(entity,
                evalExplain.readFactors(entity.getExplainJson()),
                evalExplain.readHints(entity.getExplainJson()));
    }

    // ---------- 内部实现 ----------

    private Outcome compute(EvalContext ctx, boolean withAi) {
        List<EvalResultVO.EvalFactorVO> factorVOs = new ArrayList<>();
        BigDecimal total = ZERO;
        EvalResult entity = new EvalResult();
        entity.setApplyNo(ctx.getApplyNo());

        for (EvalFactor factor : factors) {
            EvalFactor.EvalScore raw = factor.calc(ctx);
            BigDecimal score = clamp(raw.getScore()).setScale(2, RoundingMode.HALF_UP);

            EvalResultVO.EvalFactorVO vo = new EvalResultVO.EvalFactorVO();
            vo.setKey(factor.key());
            vo.setName(factor.name());
            vo.setWeight(factor.weight());
            vo.setScore(score);
            vo.setValue(raw.getValue());
            factorVOs.add(vo);

            total = total.add(score.multiply(factor.weight()));
            switch (factor.key()) {
                case EvalResult.KEY_F_HISTORY -> entity.setFHistory(score);
                case EvalResult.KEY_F_COST -> entity.setFCost(score);
                case EvalResult.KEY_F_FREQ -> entity.setFFreq(score);
                case EvalResult.KEY_F_COMPLETE -> entity.setFComplete(score);
                default -> log.warn("未知的评价因子：{}", factor.key());
            }
        }

        total = clamp(total).setScale(2, RoundingMode.HALF_UP);

        // AI 只挪动分数，不重算分数：总分 = clamp(规则加权分 + Δ)。
        // Δ 取 0 时这一步等于没做，AI 挂了也不会改变任何既有结论。
        if (withAi && aiConfig.isApplyDelta()) {
            AiSemanticVO ai = aiSemanticEvaluator.evaluate(ctx);
            if (ai != null) {
                entity.setAiDelta(BigDecimal.valueOf(ai.getDelta()));
                entity.setAiGrade(ai.getGrade());
                entity.setAiReasons(evalExplain.writeAiReasons(ai.getReasons()));
                entity.setAiModel(ai.getModel());
                entity.setPromptVersion(ai.getPromptVersion());
                entity.setAiEnhanced(Boolean.TRUE.equals(ai.getEnhanced()) ? 1 : 0);
                total = clamp(total.add(BigDecimal.valueOf(ai.getDelta())))
                        .setScale(2, RoundingMode.HALF_UP);
            }
        }

        entity.setTotalScore(total);
        entity.setLevel(EvalLevel.of(total, suggestThreshold, noticeThreshold).getCode());

        List<String> hints = buildHints(ctx);
        entity.setExplainJson(evalExplain.write(factorVOs, hints));
        return new Outcome(entity, factorVOs, hints);
    }

    /**
     * 事前提示：把「看起来有点怪」的地方直接摆出来，但不阻拦提交（BR-08）。
     */
    private List<String> buildHints(EvalContext ctx) {
        List<String> hints = new ArrayList<>();

        if (ctx.getPartnerId() != null) {
            // 上一趟无进展且间隔太短——「到场没到效」
            List<BizResult> lastResults = bizResultMapper.selectList(new LambdaQueryWrapper<BizResult>()
                    .eq(BizResult::getPartnerId, ctx.getPartnerId())
                    .orderByDesc(BizResult::getFilledAt)
                    .last("limit 1"));
            if (!lastResults.isEmpty() && BizResult.TYPE_NO_PROGRESS.equals(lastResults.get(0).getResultType())) {
                LocalDateTime filledAt = lastResults.get(0).getFilledAt();
                long gap = filledAt == null
                        ? noProgressGapDays
                        : ChronoUnit.DAYS.between(filledAt.toLocalDate(), LocalDate.now());
                if (gap < noProgressGapDays) {
                    hints.add("该对象上一趟（" + (filledAt == null ? "时间未知" : filledAt.format(DATE_TIME))
                            + "）结果为「无进展」，距本次不足 " + noProgressGapDays + " 天，请确认本次必要性");
                }
            }

            // 短期高频
            long recent = countRecentTrips(ctx);
            if (recent + 1 >= freqThreshold) {
                hints.add("本次将是 " + freqWindowDays + " 天内第 " + (recent + 1) + " 次去同一对象");
            }
        }

        // 高于同类花费参考上限
        String toCity = firstDestination(ctx.getItineraries());
        CostRef ref = toCity == null ? null : costRefMapper.selectOne(new LambdaQueryWrapper<CostRef>()
                .eq(CostRef::getToCity, toCity)
                .eq(ctx.getTripType() != null, CostRef::getTripType, ctx.getTripType())
                .last("limit 1"));
        if (ref != null && ref.available() && ref.getMaxAmount() != null
                && ctx.getEstTotal() != null
                && ctx.getEstTotal().compareTo(ref.getMaxAmount()) > 0) {
            hints.add("本次预估 " + ctx.getEstTotal().setScale(2, RoundingMode.HALF_UP)
                    + " 元，高于同类参考上限 " + ref.getMaxAmount().setScale(2, RoundingMode.HALF_UP) + " 元（样本 "
                    + ref.getSampleCnt() + " 条）");
        }

        // 事由太短，F4 会直接扣分，提前说一声
        if (ctx.getReason() == null || ctx.getReason().trim().length() < 15) {
            hints.add("出差事由不足 15 字，补全目的（见谁、办什么事）可以提高事由完整度得分");
        }

        return hints;
    }

    private long countRecentTrips(EvalContext ctx) {
        LocalDateTime since = LocalDateTime.now().minusDays(freqWindowDays);
        Long count = tripApplyMapper.selectCount(new LambdaQueryWrapper<TripApply>()
                .eq(TripApply::getPartnerId, ctx.getPartnerId())
                .ge(TripApply::getCreatedAt, since));
        return count == null ? 0L : count;
    }

    private String firstDestination(List<TripItinerary> itineraries) {
        if (itineraries == null) {
            return null;
        }
        for (TripItinerary item : itineraries) {
            if (item.getToCity() != null && !item.getToCity().isBlank()) {
                return item.getToCity();
            }
        }
        return null;
    }

    private EvalResultVO toVO(EvalResult entity, List<EvalResultVO.EvalFactorVO> factors, List<String> hints) {
        EvalResultVO vo = new EvalResultVO();
        vo.setApplyNo(entity.getApplyNo());
        vo.setFHistory(entity.getFHistory());
        vo.setFCost(entity.getFCost());
        vo.setFFreq(entity.getFFreq());
        vo.setFComplete(entity.getFComplete());
        vo.setTotalScore(entity.getTotalScore());
        vo.setLevel(entity.getLevel());
        vo.setEvalAt(entity.getEvalAt() == null ? null : entity.getEvalAt().format(DATE_TIME));
        vo.setFactors(factors);
        vo.setHints(hints);
        vo.setAi(toAi(entity));
        return vo;
    }

    /**
     * 把落库的 AI 字段还原成结论对象。
     *
     * <p>没有 AI 参与时返回 null，前端据此不渲染那一块——
     * 不要返回一份「Δ=0」的空壳，那会让人误以为 AI 跑过且认为这趟没问题。
     */
    private AiSemanticVO toAi(EvalResult entity) {
        if (entity.getAiDelta() == null && entity.getAiGrade() == null) {
            return null;
        }
        AiSemanticVO vo = new AiSemanticVO();
        vo.setDelta(entity.getAiDelta() == null ? 0 : entity.getAiDelta().intValue());
        vo.setGrade(entity.getAiGrade());
        vo.setReasons(evalExplain.readAiReasons(entity.getAiReasons()));
        vo.setModel(entity.getAiModel());
        vo.setPromptVersion(entity.getPromptVersion());
        vo.setEnhanced(Integer.valueOf(1).equals(entity.getAiEnhanced()));
        vo.setLocal("local-heuristic".equals(entity.getAiModel()));
        return vo;
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.max(ZERO).min(HUNDRED);
    }

    /**
     * 一次评价的完整产出：落库实体 + 展示用的因子明细 + 事前提示。
     */
    @Data
    @AllArgsConstructor
    public static class Outcome {
        private EvalResult result;
        private List<EvalResultVO.EvalFactorVO> factors;
        private List<String> hints;
    }
}
