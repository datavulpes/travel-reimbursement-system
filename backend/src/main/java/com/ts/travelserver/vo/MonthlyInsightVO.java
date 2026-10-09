package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理层月度洞察（F-01 的解读层）。
 *
 * <p>与 {@link MonthlyReportVO#getConclusion()} 的分工：
 * 结论是模板拼的两句话，跟着月报一起返回，永远可用；洞察是模型归纳的分级要点，
 * 独立接口、独立缓存，模型不可用或校验不过时整份回落到结论。
 * 页面上两者是同一张 AI 卡，只是内容来源不同——所以这个 VO 的形状与
 * {@code MonthlyReportVO.ConclusionVO} 保持兼容（都有 highlights + text 的等价物）。
 *
 * <h3>三层，各自的可信度不同</h3>
 * <ol>
 *   <li>{@code drivers}：<b>事实层</b>，由一阶分解算出来的贡献值，可复现、可验算。</li>
 *   <li>{@code highlights}：<b>解读层</b>，模型归纳的措辞，数字被白名单圈死。</li>
 *   <li>{@code sampleNote}：<b>可信度声明</b>，样本不足或回填率低时明确说出来，
 *       不让人拿一份半空的月报做判断。</li>
 * </ol>
 */
@Data
public class MonthlyInsightVO {

    /** 月份，yyyy-MM */
    private String month;

    /** 对比的上一个月 */
    private String prevMonth;

    /** 分级要点，与 AiAdvice 卡的 points 同形状 */
    private List<AiPointVO> highlights = new ArrayList<>();

    /**
     * 下一步可以做的动作（处方层）。
     *
     * <p>{@code highlights} 回答的是「发现了什么」，这里回答「下一步查什么」。
     * 两者差一层：只说发现，看报告的人还得自己想该怎么办；而动作是可以勾的，
     * 勾了就记下来——这份报告有没有真的推动过一件事，靠的就是这个勾选。
     *
     * <p>动作的代码是枚举而非自由文本：模型能写出「暂停该部门审批权限」这种
     * 越权且无法执行的句子，所以只给它六个固定动作去选，写别的直接丢弃。
     */
    private List<ActionVO> actions = new ArrayList<>();

    /** 一句话总结 */
    private String summary;

    /**
     * 费效比环比变化的归因分解，按贡献绝对值降序取前几条。
     * 这是本 VO 里唯一"算"出来的东西，也是页面最该信的一部分。
     */
    private List<DriverVO> drivers = new ArrayList<>();

    /** 本月与上月费效比，放这里省得前端再回头取月报 */
    private BigDecimal ratio;

    private BigDecimal prevRatio;

    /** 费效比环比变化 = 本月 − 上月，正数表示变差 */
    private BigDecimal delta;

    /** 数据可信度说明；没有问题时为空 */
    private String sampleNote;

    /**
     * 已付款单据数，也就是这份结论的样本量。
     * 页面上写成「样本 N 单」——它是真实约束，看了才知道这份结论是站在几张单上说的。
     */
    private Integer sampleCnt;

    /**
     * 置信度，0~1，由「样本充足度 × 数字校验通过率 × 产出回填完整度」三项相乘得出。
     *
     * <p>刻意不接受模型自报的把握：模型说自己多有把握不作数，
     * 它算出来的数本来就不进结果，让它给自己打分更是无从验证。
     * 三项全部由统计层的真实数字算，跟「模型不算数」是同一条口径。
     */
    private Double confidence;

    /**
     * 置信度档位，取值见下面的 CONF_* 常量。页面按它选颜色。
     * 数字本身不上页面——写「置信度 0.82」就成了讲解型小字，看了也不知道该怎么办。
     */
    private String confidenceLevel;

    public static final String CONF_AMPLE = "AMPLE";

    public static final String CONF_FAIR = "FAIR";

    public static final String CONF_THIN = "THIN";

    /** true = 本地规则生成，页面标「本地演示」 */
    private Boolean local;

    private String model;

    private String promptVersion;

    /** 这份报告生成的时间。页面上的「更新于」直接读它 */
    private LocalDateTime generatedAt;

    /**
     * 这份是怎么来的：AUTO / SCHEDULED / MANUAL，取值见
     * {@link com.ts.travelserver.entity.MonthlyInsight} 的 BY_* 常量。
     * 页面上如实标出来，因为「凌晨自动跑的」和「刚手动点的」可信度不一样。
     */
    private String generatedBy;

    /**
     * true = 报告生成之后，底下的统计数字又变过（亲朋好友欠款又付了一笔、又回填了一条产出）。
     * 这时 {@code highlights} 里的快照值可能与页面上方实时的 KPI 对不上，
     * 页面必须提示，由人决定要不要点「重新分析」。系统不会偷偷重算。
     */
    private Boolean stale;

    /**
     * 这份报告的数字是从哪几张表、哪个月份汇总出来的。
     *
     * <p>与 {@code AiPointVO.evidence} 是同一套思路，只是一个挂在单条结论上、
     * 一个挂在整个报告上。它的用处有两个：一是后端自检——报告里引用的口径
     * 跟这里对不上就是算错了；二是被人问「这个 1.20 是哪来的」时能答得出来。
     *
     * <p>页面上不展示：把它印成一行小字就是讲解型文案，看了也不知道该怎么办。
     */
    private List<String> sources = new ArrayList<>();

    /**
     * 下一步可以做的一个动作。
     */
    @Data
    public static class ActionVO {

        /**
         * 动作代码，取值见下面的 ACT_* 常量。
         *
         * <p>用代码而不是一句话，是因为动作要能勾选、能统计：
         * 自由文本没法回答「这个月有几条建议被真的做了」。
         */
        private String code;

        /** 代码的中文名，页面直接显示 */
        private String codeText;

        /**
         * 动作落到的对象：部门名或出差类型名；面向全公司时为空串。
         *
         * <p>必须能在本次入参里找到，对不上的一律丢弃——
         * 模型写出一个不存在的部门名，等于让人去查一个查不到的东西。
         */
        private String target;

        /** 一句话说明「具体查什么」，不超过 30 字 */
        private String detail;

        /**
         * 有没有被勾上（表示这件事已经在查了 / 已经做了）。
         *
         * <p>跟着报告一起存：报告重新分析后动作会重新生成，
         * 勾选状态跟着新一版动作走，而不是留着上一版的勾选冒充这一版做了事。
         */
        private Boolean adopted;

        /**
         * 这条的根据，取自本次入参（如 {@code dept.技术部.ratio}）。
         * 没有依据的动作不给上页面——凭空冒出来的「建议复核」等于骚扰。
         */
        private String basis;
    }

    /** 复核这一份的费用明细 */
    public static final String ACT_REVIEW_COST = "REVIEW_COST";

    /** 补录或核实产出登记 */
    public static final String ACT_CHECK_OUTPUT = "CHECK_OUTPUT";

    /** 抽查这一份的票据 */
    public static final String ACT_AUDIT_INVOICE = "AUDIT_INVOICE";

    /** 跟这个部门对齐下月的出差计划 */
    public static final String ACT_TALK_DEPT = "TALK_DEPT";

    /** 调整下月预算 */
    public static final String ACT_ADJUST_BUDGET = "ADJUST_BUDGET";

    /** 继续观察这个往来对象，先不动 */
    public static final String ACT_WATCH_PARTNER = "WATCH_PARTNER";

    /**
     * 归因分解里的一项：某个维度上的某一份，对费效比变化的贡献。
     */
    @Data
    public static class DriverVO {

        /** 维度：DEPT 部门 / TYPE 出差类型 / PARTNER 往来对象 */
        private String dimension;

        /** 维度的中文名，页面直接显示 */
        private String dimensionText;

        /** 这一份的名字（部门名 / 类型名 / 对象名） */
        private String name;

        /**
         * 对费效比变化的贡献。正值 = 把比值推高（变差），负值 = 让比值变好。
         * 同一维度内所有项加起来就是整体的变化量。
         */
        private BigDecimal contribution;

        private BigDecimal pay;

        private BigDecimal output;

        /** 相对上月的投入变化 */
        private BigDecimal payDelta;

        /** 相对上月的产出变化 */
        private BigDecimal outputDelta;

        /** 一句话说明，给页面直接显示用 */
        private String text;
    }
}
