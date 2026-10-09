package com.ts.travelserver.ai;

import com.ts.travelserver.entity.EvalResult;
import com.ts.travelserver.vo.ApprovalViewVO;
import com.ts.travelserver.vo.AiPointVO;
import com.ts.travelserver.vo.AiRiskVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 审批风险摘要（B-04）。
 *
 * <p>做的事情只有一件：把散落在同类参考卡、差标卡、评价卡、流转记录里的信号
 * 汇总成几条话。不新增任何判定，不改任何分数，也不替审批人下结论——
 * 审批人完全可以看完以后照批。
 *
 * <p>为什么值得做：审批人要在四张卡之间来回看才能拼出全貌，
 * 「这单到底哪里有问题」这件事本该由系统先说一遍。
 *
 * <p>规则全部是确定性的，不进模型：这些信号本来就是系统自己算出来的，
 * 让模型复述一遍除了增加延迟与不确定性，没有任何收益。
 * （将来要润色措辞，再在最后一步接模型，见 AiScene 的注释。）
 */
@Slf4j
@Component
public class RiskSummarizer {

    /** 高出均值这个比例就算偏贵 */
    private static final BigDecimal OVER_AVG = new BigDecimal("1.2");

    public AiRiskVO summarize(ApprovalViewVO view) {
        AiRiskVO vo = new AiRiskVO();
        vo.setLocal(true);
        if (view == null || view.getApply() == null) {
            vo.setSummary("没有可取到的数据");
            return vo;
        }
        vo.setApplyNo(view.getApply().getApplyNo());

        BigDecimal estTotal = view.getApply().getEstTotal();
        ApprovalViewVO.CostRefVO ref = view.getCostRef();

        // 同类花费参考：超上限是高、超均值是中
        if (ref != null && estTotal != null && estTotal.compareTo(BigDecimal.ZERO) > 0) {
            if (ref.getMaxAmount() != null && estTotal.compareTo(ref.getMaxAmount()) > 0) {
                int over = percent(estTotal.subtract(ref.getMaxAmount()), ref.getMaxAmount());
                vo.getPoints().add(AiPointVO.of("high",
                        "预估 ¥" + money(estTotal) + " 高于同类区间上限 ¥" + money(ref.getMaxAmount())
                                + "，超出 " + over + "%"));
            } else if (ref.getAvgAmount() != null
                    && estTotal.compareTo(ref.getAvgAmount().multiply(OVER_AVG)) > 0) {
                int over = percent(estTotal.subtract(ref.getAvgAmount()), ref.getAvgAmount());
                vo.getPoints().add(AiPointVO.of("mid",
                        "预估比同类均值高 " + over + "%（均值 ¥" + money(ref.getAvgAmount()) + "）"));
            }
        }

        // 差标：超限段数与有没有填说明
        List<ApprovalViewVO.StandardHintVO> hints = view.getStandardHints();
        if (hints != null && !hints.isEmpty()) {
            long exceed = hints.stream()
                    .filter(h -> "EXCEED".equals(h.getResult()))
                    .count();
            if (exceed > 0) {
                boolean noReason = view.getApply().getExceedReason() == null
                        || view.getApply().getExceedReason().isBlank();
                vo.getPoints().add(AiPointVO.of("high",
                        exceed + " 段行程超出差标" + (noReason ? "，且未填写超标说明" : "")));
            }
        }

        // 合理性评价档位
        String level = view.getEval() == null ? null : view.getEval().getLevel();
        if (EvalResult.LEVEL_ATTENTION.equals(level)) {
            vo.getPoints().add(AiPointVO.of("high",
                    "合理性评价为「重点关注」，加权总分 " + score(view)));
        } else if (EvalResult.LEVEL_NOTICE.equals(level)) {
            vo.getPoints().add(AiPointVO.of("mid",
                    "合理性评价为「值得关注」，加权总分 " + score(view)));
        }

        // 曾被驳回过：当前这版是改后重提
        boolean rejected = view.getRecords() != null
                && view.getRecords().stream().anyMatch(r -> "REJECTED".equals(r.getResult()));
        if (rejected) {
            vo.getPoints().add(AiPointVO.of("mid", "这单此前被驳回过，当前是修改后重新提交"));
        }

        // 事由过短
        String reason = view.getApply().getReason();
        if (reason != null && !reason.isBlank() && reason.trim().length() < 15) {
            vo.getPoints().add(AiPointVO.of("mid",
                    "事由只有 " + reason.trim().length() + " 个字，信息不足以判断必要性"));
        }

        if (vo.getPoints().isEmpty()) {
            vo.getPoints().add(AiPointVO.of("low",
                    "没发现需要特别关注的点：预估在同类区间内，差标与评价结论都正常"));
        }

        long high = vo.getPoints().stream().filter(p -> "high".equals(p.getLevel())).count();
        vo.setSummary(high > 0 ? "有 " + high + " 条建议重点确认" : "整体平稳，按常规判断即可");
        return vo;
    }

    private String score(ApprovalViewVO view) {
        return view.getEval().getTotalScore() == null
                ? "—"
                : view.getEval().getTotalScore().setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private int percent(BigDecimal diff, BigDecimal base) {
        if (base == null || base.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return diff.multiply(BigDecimal.valueOf(100))
                .divide(base, 0, RoundingMode.HALF_UP).intValue();
    }

    private String money(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
