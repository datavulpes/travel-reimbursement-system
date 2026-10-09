package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 一条「可以直接填进表单」的建议。
 *
 * <p>与 {@link AiPointVO} 的分工：AiPointVO 只是提醒（「你还没写预期产出」），
 * 这一条带 {@link #suggestValue}，勾上就能把值写进对应输入框。
 *
 * <h3>为什么必须带 source</h3>
 * 页面上每条建议都要标出依据从哪来。员工看到「预估住宿费 ¥1000」时，
 * 必须同时看到它是按同类均值摊的还是按差标封顶的——否则这个数字和拍脑袋没区别，
 * 审批人一问「凭什么」就答不上来。所以 source 不可为空，
 * 且 {@link #reason} 里必须出现真实数字（没有数字的建议宁可不给）。
 *
 * <h3>为什么金额类建议不让模型出</h3>
 * 金额、差标、天数这类值模型只能编。这里所有非文案字段都由库内数据算出来，
 * 模型只负责 {@code reason} 那一句话（把已填的零散信息组织成通顺表述）。
 */
@Data
public class AiFieldAdviceVO {

    /** 依据来自差标表 trip_standard */
    public static final String SRC_STANDARD = "DB_STANDARD";
    /** 依据来自同类花费参考 cost_ref */
    public static final String SRC_COST_REF = "DB_COST_REF";
    /** 依据来自往来对象 partner 的投入产出 */
    public static final String SRC_PARTNER = "DB_PARTNER";
    /** 依据来自本人 / 本对象的历史单据 */
    public static final String SRC_HISTORY = "DB_HISTORY";
    /**
     * 库里一条能对标的数据都没有时的通用建议。
     * 页面照样要标出来源：这时候的建议是常识性的，混着说等于假装它有数据支撑。
     */
    public static final String SRC_NONE = "NONE";
    /** 这一条由模型生成 */
    public static final String SRC_MODEL = "MODEL";
    /** 模型不可用时的本地模板兜底，页面要如实标「本地演示」 */
    public static final String SRC_TEMPLATE = "LOCAL_TEMPLATE";

    /**
     * 表单字段标识，前端按它把值写回对应输入框。
     * 取值：reason / tripType / estTraffic / estHotel / exceedReason。
     * 刻意不包含 partnerId、日期、城市等级——那几项只有当事人知道，AI 猜了就是添乱。
     */
    private String field;

    /** 字段中文名，如「预估住宿费」 */
    private String label;

    /** 当前已填值（统一转字符串，只用于页面显示「现在是 xxx」） */
    private String currentValue;

    /** 建议值。数字字段前端自己 Number() 转，这里保持字符串避免精度在序列化里变形 */
    private String suggestValue;

    /** 一句话理由，必须落到具体数字或具体单据上 */
    private String reason;

    /** 依据来源，取值见本类 SRC_* 常量 */
    private String source;

    /** 来源的一句话说明，页面显示在理由下面 */
    private String sourceText;

    /** 0~1。库内硬数据给高值，推断类给低值 */
    private BigDecimal confidence;

    /**
     * true = 该字段已经填了值，采纳会覆盖。
     * 前端据此默认不勾这一项，要覆盖得由人显式勾选——
     * AI 悄悄改掉人已经填好的东西是最招人烦的一类行为。
     */
    private Boolean overwrite;

    /** 展示顺序，小的在前 */
    private Integer sort;
}
