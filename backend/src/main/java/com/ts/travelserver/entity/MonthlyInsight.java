package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理层月度洞察报告（monthly_insight）。
 *
 * <p>一个月一行，可被覆盖。它和 {@link AiLog} 的区别要分清：
 * ai_log 记的是「每一次模型调用」，审计用的，只追加不删；
 * 这张表记的是「当前生效的那份报告」，页面上的「更新于」读的就是
 * {@code generated_at}——管理层看的是这份，不是某次调用。
 *
 * <h3>为什么要存 input_hash</h3>
 * 它是生成那一刻的入参指纹。页面读的时候拿它跟当前重算的指纹比，
 * 不一样就说明「报告生成之后数据又变过」，前端挂一个「数据已更新」的标记。
 * 这样既守住了「已经生成过就展示原先那份」，又不至于拿一份过时解读糊弄人——
 * 要不要重算的决定权交回给看报告的人。
 *
 * <h3>generated_by 的三种取值</h3>
 * 页面上要如实说这份报告是怎么来的，因为它决定了这份解读"新鲜"到什么程度：
 * 定时的是每天 0 点跑的（数据截止到昨天），手动的是人刚点的。
 */
@Data
@TableName("monthly_insight")
public class MonthlyInsight {

    /** 首次读取时自动生成：页面第一次打开这个月还没有报告 */
    public static final String BY_AUTO = "AUTO";

    /** 凌晨定时任务生成：数据截止到昨天夜里 */
    public static final String BY_SCHEDULED = "SCHEDULED";

    /** 管理层点了「重新分析」：人主动要的，一定重跑模型 */
    public static final String BY_MANUAL = "MANUAL";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属月份，yyyy-MM，唯一 */
    private String month;

    /** 指引入参指纹；变了表示数据或 prompt 版本已更新 */
    private String inputHash;

    private String summary;

    /** 分级要点，JSON 文本，形状是 {@link com.ts.travelserver.vo.AiPointVO} 数组 */
    private String highlightsJson;

    /** 归因分解，JSON 文本，形状是 MonthlyInsightVO.DriverVO 数组 */
    private String driversJson;

    /** 下一步动作，JSON 文本，形状是 MonthlyInsightVO.ActionVO 数组；含是否被勾选 */
    private String actionsJson;

    /** 这份报告的数字出处，JSON 文本，字符串数组；用于后端自检与口径核对，不上页面 */
    private String sourcesJson;

    /** 快照当时的费效比。注意这是快照值，页面上方的 KPI 是实时的，两者可能不一致 */
    private BigDecimal ratio;

    private BigDecimal prevRatio;

    private BigDecimal delta;

    private String prevMonth;

    private String sampleNote;

    /**
     * 快照当时的已付款单据数。跟着报告一起存，是因为置信度要在「从库里读出来」时也说得通——
     * 只有重新生成的那一刻才知道样本有多少，不存下来老报告就永远没有这一项。
     */
    private Integer sampleCnt;

    /** 快照当时的置信度，0~1。算不出来时为 null，页面就不显示样本标签 */
    private BigDecimal confidence;

    /** true = 规则生成的本地版本，页面标「本地演示」 */
    private Integer localFlag;

    private String model;

    private String promptVersion;

    private LocalDateTime generatedAt = LocalDateTime.now();

    private String generatedBy = BY_AUTO;
}
