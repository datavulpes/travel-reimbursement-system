package com.ts.travelserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 能力配置，对应 application.yml 的 travel.ai.*。
 *
 * <p>三条刻意的取舍：
 * <ul>
 *   <li><b>不引 Spring AI</b>：Boot 4 的版本对齐风险同 mybatis-plus starter 那个坑，
 *       而且这里只需要「发一段 prompt、收一段 JSON」，一个 HTTP 客户端就够了。</li>
 *   <li><b>不锁死厂商</b>：国内外主流厂商都提供 OpenAI 兼容的
 *       {@code POST /v1/chat/completions}，换模型 = 换 base-url + model + api-key 三个值。</li>
 *   <li><b>默认关闭</b>：enabled=false 时走 {@code MockAiService}（本地启发式），
 *       全链路照跑，只是结论标注为「本地演示」。AI 挂了不能拖垮主流程。</li>
 * </ul>
 *
 * <p>api-key 不要提交到公开仓库；换厂商、换模型只改 application.yml，Java 代码不用动。
 * 离线演示用 Ollama 时 api-key 填任意非空字符串即可。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.ai")
public class AiConfig {

    /** 总开关：false 走本地启发式，true 才真调模型 */
    private boolean enabled = false;

    /**
     * 服务地址。只填到 {@code https://api.deepseek.com} 这种根路径即可，
     * {@code RemoteAiService} 会自动拼上 {@code /chat/completions}；
     * 已经写全的地址则原样使用。
     */
    private String baseUrl = "";

    /**
     * 模型名。注意 <b>deepseek-chat / deepseek-reasoner 官方已宣布于 2026-07-24 退役</b>，
     * 当前在售的是 {@code deepseek-flash}（V4.1-Flash，便宜够用）与
     * {@code deepseek-v4-pro}（更强更贵）。本地离线演示填 {@code qwen3:4b}。
     */
    private String model = "";

    /** TODO-需要你填写：模型服务的 API Key。别提交到公开仓库 */
    private String apiKey = "";

    private int connectTimeoutMs = 3000;

    /**
     * 读取超时。给 2 秒是算过的：Δ 评价只要 200 个 token 的结构化输出，
     * 正常 1 秒内回来；超时就降级 Δ=0，绝不让提交接口干等。
     */
    private int readTimeoutMs = 2000;

    /** 恒为 0：要的是可复现的判定，不是文采 */
    private double temperature = 0d;

    private int maxTokens = 512;

    /**
     * 是否让模型「先想再答」（DeepSeek 系的 thinking mode）。
     *
     * <p>三态，故意用包装类型：
     * <ul>
     *   <li>{@code false}／{@code true}：请求体带上 {@code thinking:{type:disabled|enabled}}。</li>
     *   <li>{@code null}（yml 里不写这个键）：<b>完全不带该字段</b>——
     *       Ollama、部分国产厂商不认这个扩展字段，带了会 400。</li>
     * </ul>
     *
     * <p>为什么默认关：DeepSeek V4 系默认开思考且力度 high。实测本项目这条 Δ prompt
     * （五档强约束）下，两种模式都能出合法结果，但<b>关闭只要一半时间、一半 token</b>
     * （0.8~1.1s vs 约 1.9s），思考链对这个场景没有任何增益——事由通不通是一眼的事，
     * 不需要推演。另外思考模式还会静默忽略 {@code temperature}。
     *
     * <p>真正致命的是配额抢占：思考模式下思维链走 {@code reasoning_content}、
     * 但和 {@code content} 共用 {@code maxTokens}，思维链一旦写长，正文会被截成空串。
     * 所以用宽松 prompt 或要开思考时，务必同时放大 {@code maxTokens}。
     */
    private Boolean thinking = null;

    /**
     * Prompt 版本号。改一个字都要改版本号，否则缓存与 ai_log 留痕会对不上
     * ——到时候分不清某条 Δ 是哪版 prompt 算出来的。
     */
    private String promptVersion = "2026-09-24-1";

    /** 缓存条数上限：同一份输入短时间内不重复烧 token */
    private int cacheMaxEntries = 500;

    /** 缓存有效期（分钟） */
    private int cacheTtlMinutes = 60;

    /** 是否写 ai_log 留痕 */
    private boolean logEnabled = true;

    /**
     * Δ 是否计入总分。关掉之后 AI 照常算、照常留痕、照常在页面上给建议，
     * 但一分都不动——答辩现场想演示「同一趟出差，开不开 AI 结论差多少」就靠它。
     */
    private boolean applyDelta = true;

    /**
     * G-02 事前预演是否也调模型。默认关：预演是「边填边看」，
     * 每敲一次键盘烧一次 token 不划算；真正算分落库的是 A-02 提交那一次。
     * 想让预演也带 AI 结论，把它改成 true（前端另有 /api/eval/semantic 可单独调）。
     */
    private boolean previewEnabled = false;

    /**
     * 月度洞察报告的自动生成时间，cron 表达式，默认是每天 0 点。
     *
     * <p>写成 {@code "-"} 表示不自动跑（Spring 的约定）。演示时想当场看效果，
     * 可以临时改成五分钟一次：秒位 0、分位 0/5，其余照旧。
     * 但凡写在注释里，注意别打出「星号紧跟斜杠」这个组合——它会把这段注释提前闭合。
     *
     * <p>为什么外部可调：这是唯一一个半夜才跑、而且要去请求外部服务的动作，
     * 现场网络不好时，与其让它每天失败一次，不如让人关掉、需要时手动点「重新分析」。
     */
    private String insightCron = "0 0 0 * * *";

    /**
     * 场景级覆盖（application.yml 的 {@code travel.ai.scenes.<场景名>.*}）。
     *
     * <p>为什么要有这一层：三个场景的「耐心」完全不一样，共用一个读超时必然两头不讨好。
     * <ul>
     *   <li><b>填写助手</b>在提交链路上，员工点完在等结果，超时必须短（1.5 秒），
     *       超了就走本地模板，别让人干等。</li>
     *   <li><b>月度洞察</b>是半夜定时任务跑的，没人盯着，给 30 秒也不影响任何人。</li>
     *   <li><b>语义评价</b>夹在提交里，但要的是可复现判定，不能给温度。</li>
     * </ul>
     *
     * <p>只写要改的那一项：yml 里没写的项沿用上面的全局值，没写这个场景就全用全局值。
     * 场景名是 {@link com.ts.travelserver.ai.AiScene} 的常量值（大写下划线），
     * 写错的场景名不会被用到（取配置时按名查，查不到就用全局），启动不报错——
     * 所以改完最好去「AI 运行状况」页看一眼平均耗时有没有变化。
     */
    private Map<String, SceneConf> scenes = new LinkedHashMap<>();

    /**
     * 单个场景的覆盖项。全部用包装类型：{@code null} 表示「这项不覆盖，用全局值」。
     *
     * <p>刻意不给 baseUrl / model / apiKey 留覆盖位：换模型是全局决策，
     * 按场景换会让同一个 prompt-version 下的结论来自不同的模型，留痕对不上账。
     */
    @Data
    public static class SceneConf {

        /** 读取超时（毫秒） */
        private Integer readTimeoutMs;

        /** 输出上限（token） */
        private Integer maxTokens;

        /** 温度 */
        private Double temperature;

        /** 是否开思考模式；null = 沿用全局（全局为 null 时请求体不带该字段） */
        private Boolean thinking;
    }

    /**
     * 三件套齐全才算可用，缺一样就走本地启发式，不在运行时抛异常。
     */
    public boolean usable() {
        return enabled
                && StringUtils.hasText(baseUrl)
                && StringUtils.hasText(model)
                && StringUtils.hasText(apiKey);
    }

    // ---------- 场景级取配置：没配就回落到全局 ----------

    private SceneConf of(String scene) {
        return scenes == null || scene == null ? null : scenes.get(scene);
    }

    public int readTimeoutMs(String scene) {
        SceneConf c = of(scene);
        return c == null || c.getReadTimeoutMs() == null ? readTimeoutMs : Math.max(500, c.getReadTimeoutMs());
    }

    public int maxTokens(String scene) {
        SceneConf c = of(scene);
        return c == null || c.getMaxTokens() == null ? maxTokens : Math.max(64, c.getMaxTokens());
    }

    public double temperature(String scene) {
        SceneConf c = of(scene);
        return c == null || c.getTemperature() == null ? temperature : c.getTemperature();
    }

    public Boolean thinking(String scene) {
        SceneConf c = of(scene);
        return c == null || c.getThinking() == null ? thinking : c.getThinking();
    }
}
