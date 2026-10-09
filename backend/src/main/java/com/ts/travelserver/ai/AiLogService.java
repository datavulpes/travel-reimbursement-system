package com.ts.travelserver.ai;

import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.config.AiConfig;
import com.ts.travelserver.dto.AiAdoptRequest;
import com.ts.travelserver.entity.AiLog;
import com.ts.travelserver.mapper.AiLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 留痕写入。
 *
 * <p>一条铁律：<b>留痕失败绝不能影响业务</b>。
 * 写 ai_log 抛异常一律吞掉只记日志——它是审计数据，不是主流程的一部分，
 * 为了记一条日志让提交失败是本末倒置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiLogService {

    private final AiLogMapper aiLogMapper;
    private final AiConfig config;
    private final AiCache aiCache;
    private final AiCorrectionService aiCorrectionService;

    /**
     * 记一条调用。
     *
     * @param scene    场景，见 {@link AiScene}
     * @param refNo    关联单号，可为空
     * @param input    原始入参，只存 hash，不落原文（事由里可能有客户信息）
     * @param result   模型原文
     * @param delta    解析出的 Δ
     * @param grade    解析出的档位
     * @param costMs   耗时
     * @param accepted 是否采纳
     * @param fallback 是否降级
     */
    public void write(String scene, String refNo, String input, String result,
                      BigDecimal delta, String grade, long costMs,
                      boolean accepted, boolean fallback) {
        write(scene, refNo, input, result, delta, grade, costMs, accepted, fallback, null, null, null);
    }

    /**
     * 记一条调用，带失败原因与 token。
     *
     * @param errorMessage 没拿到可用结论时写原因；正常时传 null
     * @param requestTokens  输入 token，取不到传 null（不估算）
     * @param responseTokens 输出 token，取不到传 null
     */
    public void write(String scene, String refNo, String input, String result,
                      BigDecimal delta, String grade, long costMs,
                      boolean accepted, boolean fallback,
                      String errorMessage, Integer requestTokens, Integer responseTokens) {
        if (!config.isLogEnabled()) {
            return;
        }
        try {
            AiLog entity = new AiLog();
            entity.setScene(scene);
            entity.setRefNo(refNo);
            entity.setPromptVersion(config.getPromptVersion());
            entity.setInputHash(input == null ? "" : sceneKey(scene, input));
            entity.setModel(config.isEnabled() ? config.getModel() : "local-heuristic");
            entity.setOutputJson(clip(result, 4000));
            entity.setDelta(delta);
            entity.setGrade(grade);
            entity.setCostMs((int) Math.min(Integer.MAX_VALUE, costMs));
            entity.setAccepted(accepted ? AiLog.ACCEPTED_YES : AiLog.ACCEPTED_NO);
            entity.setFallback(fallback ? AiLog.FALLBACK_YES : AiLog.FALLBACK_NO);
            entity.setErrorMessage(clip(errorMessage, 500));
            entity.setRequestTokens(requestTokens);
            entity.setResponseTokens(responseTokens);
            entity.setOperatorId(currentUserId());
            aiLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("AI 留痕写入失败（不影响业务）：{}", e.getMessage());
        }
    }

    /**
     * 事后补记「这条结论有没有被采纳」。
     *
     * <p>写这条日志的时候人还没做决定（填单助手是出建议那一刻写的，月度洞察是出报告那一刻写的），
     * 采纳与否要等人真的用完了才知道，所以只能回头改最近那一条。
     *
     * <p>只改 accepted，不动 output_json：留痕的意义是「当时模型说了什么」，
     * 事后涂改内容这条日志就废了。
     */
    public void markAccepted(String scene, String refNo, boolean accepted) {
        if (!config.isLogEnabled() || refNo == null || refNo.isBlank()) {
            return;
        }
        try {
            AiLog latest = aiLogMapper.selectOne(new LambdaQueryWrapper<AiLog>()
                    .eq(AiLog::getScene, scene)
                    .eq(AiLog::getRefNo, refNo)
                    .orderByDesc(AiLog::getId)
                    .last("limit 1"));
            if (latest == null) {
                return;
            }
            AiLog patch = new AiLog();
            patch.setId(latest.getId());
            patch.setAccepted(accepted ? AiLog.ACCEPTED_YES : AiLog.ACCEPTED_NO);
            aiLogMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("采纳结果回写失败（不影响业务）：{}", e.getMessage());
        }
    }

    /**
     * 填写助手：按「建议值 vs 最终提交值」补记采纳结果。
     *
     * <p>判定口径：<b>只要有任意一条建议被原样采用，这一次分析就算被采纳</b>。
     * 不按条数算比例——一次分析是一个整体结论，拆成「采纳了 1/3 条」反而说不清。
     * 全部改掉或一条没勾，才算没采纳。
     *
     * <p>建议值为空（只给方向的 HINT）不参与判定：它本来就没有可比对的值。
     *
     * @return 是否落到某一条留痕上；对不上（比如这一版建议不是模型出的）返回 false
     */
    public boolean markAdviceAdopted(String bizNo, List<AiAdoptRequest.Item> items) {
        if (bizNo == null || bizNo.isBlank() || items == null || items.isEmpty()) {
            return false;
        }
        boolean adopted = false;
        for (AiAdoptRequest.Item it : items) {
            String suggest = it.getSuggested();
            String actual = it.getFinalValue();
            if (suggest == null || suggest.isBlank() || actual == null) {
                continue;
            }
            if (suggest.trim().equals(actual.trim())) {
                adopted = true;
                break;
            }
        }
        markAccepted(AiScene.APPLY_ASSIST, bizNo, adopted);
        // 同一批比对再落一份「哪个字段被改成了什么」：accepted 只说有没有采用，
        // 修正记录才指向具体字段，两边共用这一次比对的结果，别再让前端传一遍
        aiCorrectionService.recordAdvice(bizNo, currentUserId(), items.stream()
                .map(it -> new AiCorrectionService.Item(it.getField(), it.getSuggested(), it.getFinalValue()))
                .toList());
        return true;
    }

    private String sceneKey(String scene, String input) {
        return aiCache.key(scene, input).substring(0, Math.min(64, aiCache.key(scene, input).length()));
    }

    /**
     * 当前登录人。取不到（定时任务、未登录）就记 0，不让它成为失败原因。
     */
    private Long currentUserId() {
        try {
            Long id = UserContext.currentId();
            return id == null ? 0L : id;
        } catch (Exception e) {
            return 0L;
        }
    }

    private String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
