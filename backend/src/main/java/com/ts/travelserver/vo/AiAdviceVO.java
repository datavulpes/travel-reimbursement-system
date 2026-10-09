package com.ts.travelserver.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 填写建议：缺什么、怎么补、哪些字段可以直接填。
 *
 * <p>三类内容分得很清楚：
 * <ul>
 *   <li>{@link #missing} 与 {@link #items} 是确定性规则（有没有写产出、有没有选对象，
 *       查一下就知道），不进模型。</li>
 *   <li>{@link #fields} 里除 reason 外都是库内数据算出来的：金额按同类花费参考与差标，
 *       出差类型按本人历史与对象类型。模型只负责 reason 那一句文案。</li>
 *   <li>模型不可用时 reason 由模板拼，{@link #local} 为 true，页面如实标「本地演示」。</li>
 * </ul>
 *
 * <p>{@link #rewrite} 保留是为了兼容已经上线的「用这句改写事由」按钮，
 * 它与 fields 里 field=reason 那条是同一个值。
 */
@Data
public class AiAdviceVO {

    /** 缺项，如「预期产出」「出行目的」 */
    private List<String> missing = new ArrayList<>();

    /** 逐条建议，前端直接当 AI 卡的 points 用 */
    private List<AiPointVO> items = new ArrayList<>();

    /** 改写后的事由；为空表示没有可给的建议 */
    private String rewrite;

    /** 可以直接填进表单的字段建议，勾一条填一条 */
    private List<AiFieldAdviceVO> fields = new ArrayList<>();

    /** true = 本地模板生成，不是模型输出 */
    private Boolean local;

    /**
     * true = 库里一条能对标的数据都没查到（没有同类花费参考，本人 / 同部门 / 全员
     * 近 12 个月也没去过这里）。这一批建议是通用提示，页面要如实标出来。
     *
     * <p>与 {@link #local} 是两件事：一个是「谁算的」，一个是「有没有数据支撑」。
     * 只说本地的话，员工会以为是模型没配好，看不出其实是库里没数据。
     */
    private Boolean anchorless;

    /** anchorless 时的开场白，说清「缺什么数据」以及「补上哪几项就能给出具体数字」 */
    private String sourceNote;

    /** 实际出结论的通道名：local-heuristic 或配置的模型名 */
    private String model;

    /** prompt 版本，页面不展示，排查「这句是哪版算出来的」时才用得上 */
    private String promptVersion;
}
