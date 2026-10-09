package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用留痕（ai_log）。
 *
 * <p>与 config_log 一个路子：只 INSERT，不 UPDATE、不 DELETE，页面上是只读的。
 * 为什么要留：模型输出不可复现，没有留痕就查不清「这条 Δ 是谁、哪版 prompt、
 * 基于什么输入算出来的」，出了问题只能认栽。有了它才能做两件事——
 * 事后核对 Δ 准不准、二期用它训小模型替换 Δ。
 *
 * <p>output_json 存模型原文（校验前的），accepted 记这条结论最终有没有被采纳：
 * evidence 对不上整条丢弃时它是 0，这类样本正是判断 prompt 好不好用的依据。
 */
@Data
@TableName("ai_log")
public class AiLog {

    /** 结论被采纳：校验通过 */
    public static final int ACCEPTED_YES = 1;
    /** 结论被丢弃：解析失败或 evidence 校验不通过 */
    public static final int ACCEPTED_NO = 0;

    /** 走了真实模型 */
    public static final int FALLBACK_NO = 0;
    /** 降级：本地启发式或调用失败 */
    public static final int FALLBACK_YES = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 场景，取值见 AiScene 常量 */
    private String scene;

    /** 关联单号：申请号或报销单号，没有则为空 */
    private String refNo;

    /** prompt 版本号 */
    private String promptVersion;

    /** 输入 hash，同缓存键，用来找重复调用 */
    private String inputHash;

    /** 实际模型名；本地启发式为 local-heuristic */
    private String model;

    /** 模型返回原文 */
    private String outputJson;

    /** 解析出的 Δ，未解析出来为空 */
    private BigDecimal delta;

    /** 解析出的档位 A~E */
    private String grade;

    /** 调用耗时（毫秒） */
    private Integer costMs;

    /**
     * 失败原因。只在没拿到可用结论时写（超时 / HTTP 非 200 / 返回无法解析 /
     * evidence 校验不过被整条丢弃）。
     *
     * <p>没有这一列时，失败那一行除了一句 fallback=1 什么都没有，看板上只能说
     * 「失败了」，说不出「为什么失败」。而失败原因恰恰是判断要不要改 prompt 的依据。
     */
    private String errorMessage;

    /** 输入 token，厂商没返回为 null（不估算） */
    private Integer requestTokens;

    /** 输出 token，厂商没返回为 null（不估算） */
    private Integer responseTokens;

    /** 是否采纳，取值见本类 ACCEPTED_* 常量 */
    private Integer accepted;

    /** 是否降级，取值见本类 FALLBACK_* 常量 */
    private Integer fallback;

    /** 操作人 */
    private Long operatorId;

    private LocalDateTime createdAt = LocalDateTime.now();
}
