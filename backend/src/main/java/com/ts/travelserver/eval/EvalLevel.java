package com.ts.travelserver.eval;

import com.ts.travelserver.entity.EvalResult;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 评价档位。
 *
 * <p>设计报告 6.7：总分 ≥75 建议出行（SUGGEST）、55~74 值得关注（NOTICE）、
 * ＜55 重点关注（ATTENTION）。阈值在 application.yml 的 travel.eval.level 里可调。
 *
 * <p>三档都只是建议，不自动拦截（BR-08），最终结论由审批人下。
 */
@Getter
@AllArgsConstructor
public enum EvalLevel {

    /** 建议出行 */
    SUGGEST(EvalResult.LEVEL_SUGGEST, "建议出行"),
    /** 值得关注 */
    NOTICE(EvalResult.LEVEL_NOTICE, "值得关注"),
    /** 重点关注 */
    ATTENTION(EvalResult.LEVEL_ATTENTION, "重点关注");

    /** 存库与返回前端用的编码 */
    private final String code;

    /** 中文名，前端直接展示 */
    private final String label;

    /**
     * 按总分区间定档。阈值走配置，写死在代码里改一次要重新编译，不划算。
     */
    public static EvalLevel of(BigDecimal totalScore, int suggestThreshold, int noticeThreshold) {
        BigDecimal score = totalScore == null ? BigDecimal.ZERO : totalScore;
        if (score.compareTo(BigDecimal.valueOf(suggestThreshold)) >= 0) {
            return SUGGEST;
        }
        if (score.compareTo(BigDecimal.valueOf(noticeThreshold)) >= 0) {
            return NOTICE;
        }
        return ATTENTION;
    }
}
