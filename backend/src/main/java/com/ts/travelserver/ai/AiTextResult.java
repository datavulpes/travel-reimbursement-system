package com.ts.travelserver.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 一次模型调用的结果。
 */
@Data
@Builder
@AllArgsConstructor
public class AiTextResult {

    /** 模型返回的原文（JSON 文本）；失败时为 null */
    private String content;

    /** 实际模型名，留痕用 */
    private String model;

    /** 耗时（毫秒） */
    private long costMs;

    /** 是否走了真实模型；false 表示本地启发式 */
    private boolean remote;

    /**
     * 输入 / 输出 token，从响应的 usage 里读。
     *
     * <p>厂商没返回就留 null，<b>不许估算</b>：拿字符数除以 2 这种估算进了看板就是假数据，
     * 看的人会当成真实消耗去算成本。没有就是没有。
     */
    private Integer requestTokens;

    private Integer responseTokens;
}
