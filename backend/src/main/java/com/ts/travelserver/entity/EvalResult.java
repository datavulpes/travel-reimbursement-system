package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评价结果（eval_result）：出差合理性评价的四因子得分与结论。
 *
 * <p>数据字典 8.14。一申请一条，事前提示与事后评分共用同一条记录，每次重算覆盖（BR-08）。
 *
 * <p>四个因子与权重（设计报告 6.7，权重在 application.yml 的 travel.eval.weight 里可调）：
 * F1 历史产出率 0.35、F2 成本占对象产出比 0.30、F3 频次异常度 0.20、F4 事由完整度 0.15。
 * 分档：≥75 建议（SUGGEST）、55~74 提示（NOTICE）、＜55 关注（ATTENTION）。
 *
 * <p>结论只作建议，不自动拦截（BR-08）。
 */
@Data
@TableName("eval_result")
public class EvalResult {

    /** 建议出行 */
    public static final String LEVEL_SUGGEST = "SUGGEST";
    /** 值得关注 */
    public static final String LEVEL_NOTICE = "NOTICE";
    /** 重点关注 */
    public static final String LEVEL_ATTENTION = "ATTENTION";

    /** F1 键，与前端 evalData.factors[].key 对齐 */
    public static final String KEY_F_HISTORY = "F_HISTORY";
    public static final String KEY_F_COST = "F_COST";
    public static final String KEY_F_FREQ = "F_FREQ";
    public static final String KEY_F_COMPLETE = "F_COMPLETE";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请号，唯一 */
    private String applyNo;

    /** F1 历史产出率得分，0~100 */
    private BigDecimal fHistory;

    /** F2 成本占对象产出比得分，0~100 */
    private BigDecimal fCost;

    /** F3 频次异常度得分，0~100 */
    private BigDecimal fFreq;

    /** F4 事由完整度得分，0~100 */
    private BigDecimal fComplete;

    /** 加权总分，0~100 */
    private BigDecimal totalScore;

    /** 档位，取值见本类 LEVEL_* 常量 */
    private String level;

    /**
     * 依据明细（JSON 文本）：各因子原始取值、命中特征、分数来源，做到「分数可解释」。
     *
     * <p>列类型是 JSON，这里用 String 收，Service 层用 ObjectMapper 读写。
     */
    private String explainJson;

    // ---------- AI 语义调节（可空）----------
    // 规则的四因子与权重一个字不动，AI 只出 Δ ∈ [−10, +5]：
    // 总分 = clamp(规则加权分 + Δ, 0, 100)，档位仍按原阈值切。
    // 这样 AI 挂了（下面几个字段全空、Δ 记 0）就退化成原来的纯规则模型。

    /** AI 语义调节分，−10 ~ +5；没有 AI 结论时为 null */
    private BigDecimal aiDelta;

    /** AI 档位 A~E */
    private String aiGrade;

    /** AI 理由明细（JSON 文本）：[{point, evidence:{field,value}}] */
    private String aiReasons;

    /** 实际模型名；本地启发式为 local-heuristic */
    private String aiModel;

    /** 产出这个结论的 prompt 版本号 */
    private String promptVersion;

    /** 1 = AI 结论参与了本次评分，0 = 未参与（降级） */
    private Integer aiEnhanced;

    private LocalDateTime evalAt = LocalDateTime.now();
}
