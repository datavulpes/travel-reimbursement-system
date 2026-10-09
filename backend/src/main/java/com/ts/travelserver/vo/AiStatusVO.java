package com.ts.travelserver.vo;

import lombok.Data;

import java.util.List;

/**
 * AI 运行状况（系统管理员端的运行状况页）。
 *
 * <p><b>所有数字都是实时聚合 ai_log 得来的，没有统计宽表。</b>
 * 统计表一旦和业务表不同步就成了谁也不敢信的数，而 ai_log 是只追加的审计底稿，
 * 聚合错了大不了重算一次；派生数据丢了能算回来，统计表丢了要补数。
 *
 * <p>页面存在的意义只有一个：让人不用猜 AI 有没有在正常工作。
 * 演示现场模型超时降级了，打开这一页就能立刻看到，而不是盯着页面以为它「本来就这样」。
 */
@Data
public class AiStatusVO {

    /** 当前配置的模型名；未启用为空 */
    private String model;

    /** AI 总开关 */
    private boolean enabled;

    /** 配置是否完整（base-url / model / api-key 都有） */
    private boolean usable;

    /** 留痕开关；关了这一页就没有数据可看 */
    private boolean logEnabled;

    /** 统计窗口（天） */
    private int windowDays;

    /** 窗口内调用总数 */
    private long totalCnt;

    /** 拿到可用结论的次数（fallback = 0 且无失败原因） */
    private long successCnt;

    /** 降级次数（本地启发式或调用失败） */
    private long fallbackCnt;

    /** 失败原因非空的次数 */
    private long errorCnt;

    /** 平均耗时（毫秒），无数据为 0 */
    private long avgCostMs;

    /** 采纳率：accepted = 1 的占比，0~1；无数据为 null（不要拿 0 冒充「没人采纳」） */
    private Double adoptRate;

    /** token 合计；厂商没返回过就是 null，页面上显示横杠而不是 0 */
    private Long tokenSum;

    /** 按场景拆分 */
    private List<SceneStat> scenes;

    /** 最近若干条留痕，倒序 */
    private List<LogRow> recent;

    /**
     * 窗口内被改得最多的几个字段（人工修正）。
     *
     * <p>这一块回答的是「哪个字段的建议最不靠谱」，
     * 和上面的采纳率是两件事：采纳率高只说明大家照着填了，
     * 改得多的字段才是 prompt 该改的地方。
     */
    private List<FieldCorrectionVO> topCorrected;

    @Data
    public static class SceneStat {

        /** 场景码，见 AiScene */
        private String scene;

        /** 场景中文名 */
        private String sceneText;

        private long cnt;

        private long successCnt;

        private long fallbackCnt;

        private long avgCostMs;

        /** 采纳率，无数据为 null */
        private Double adoptRate;
    }

    @Data
    public static class LogRow {

        private Long id;

        private String scene;

        private String sceneText;

        private String refNo;

        private String model;

        private Integer costMs;

        /** 1 采纳 / 0 未采纳 / null 还没定（人还没做决定） */
        private Integer accepted;

        private Integer fallback;

        /** 失败原因，成功为 null */
        private String errorMessage;

        private String createdAt;
    }

    @Data
    public static class FieldCorrectionVO {

        /** 字段名（英文，与表单字段名一致） */
        private String field;

        /** 字段中文名 */
        private String fieldText;

        /** 被改次数 */
        private long cnt;
    }
}
