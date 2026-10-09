package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 语义评价结论（Δ 与依据）。
 *
 * <p>核心设定：<b>AI 不直接打分</b>。规则的四个因子与权重（0.35/0.30/0.20/0.15）
 * 一个字都不动，AI 只输出语义调节量 Δ ∈ [−10, +5]，
 * 最终 {@code 总分 = clamp(规则加权分 + Δ, 0, 100)}，档位仍按原阈值切。
 *
 * <p>这样定有三个好处：权重表不用改、答辩时那张权重表依然成立、
 * AI 挂了 Δ 取 0 就退化成原来的纯规则模型，天然降级。
 */
@Data
public class AiSemanticVO {

    /** 档位：A +5 / B 0 / C −3 / D −7 / E −10 */
    private String grade;

    /** 语义调节分，−10 ~ +5 */
    private int delta;

    /** 逐条理由，每条必须带依据 */
    private List<AiPointVO> reasons = new ArrayList<>();

    /** 缺失的关键信息：预期产出 / 出行目的 / 关联对象 */
    private List<String> missing = new ArrayList<>();

    /** 置信度 0~1；本地启发式固定 0.6，前端对低置信度会加提示 */
    private BigDecimal confidence;

    /** true = 本地启发式，前端标注「本地演示」 */
    private Boolean local;

    /** false = 降级（调用失败或 evidence 校验全灭），此时 delta 恒为 0 */
    private Boolean enhanced;

    /** 实际模型名，留痕对账用 */
    private String model;

    /** prompt 版本号 */
    private String promptVersion;

    /** 降级原因，正常为空；排查问题时直接看这个字段 */
    private String note;
}
