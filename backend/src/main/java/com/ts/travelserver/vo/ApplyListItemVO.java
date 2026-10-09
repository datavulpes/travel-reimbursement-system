package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 申请列表行（A-03 我的申请 / B-01 待审列表）。
 *
 * <p>列表页只需要一眼能认出是哪一趟差：申请号、事由、谁去的、去哪个对象、
 * 多少钱、差标结论、状态。详情留在详情接口，列表不拖明细。
 *
 * <p>字段名与前端 mock/applyListData 一一对应，改字段名要同步改前端。
 */
@Data
public class ApplyListItemVO {

    /** 申请号 */
    private String applyNo;

    /** 出差事由 */
    private String reason;

    /** 申请人姓名 */
    private String applicant;

    /** 部门名称 */
    private String deptName;

    /** 往来对象名称 */
    private String partnerName;

    /** 预估合计 */
    private BigDecimal estTotal;

    /** 差标结论：NORMAL / HIGH / EXCEED */
    private String standardResult;

    /** 状态，取值见 TripApply 的 STATUS_* */
    private String status;

    /** 提交时间，yyyy-MM-dd HH:mm:ss */
    private String createdAt;

    // ---------- 待审列表（B-01）额外带的三列 ----------

    /** 同类花费参考均值，待审列表直接展示，审批人不用点进去就能比 */
    private BigDecimal avgAmount;

    /** 参考区间下限 */
    private BigDecimal minAmount;

    /** 参考区间上限 */
    private BigDecimal maxAmount;

    /** 评价档位：SUGGEST / NOTICE / ATTENTION */
    private String evalLevel;

    /** 评价总分 */
    private BigDecimal totalScore;

    // ---------- 时限与催办 ----------

    /**
     * 进入当前状态的时间，yyyy-MM-dd HH:mm:ss。
     *
     * <p>老数据为 null：那时候还没这一列，页面显示横杠，
     * 不拿提交时间冒充——假的时间比没有更误导。
     */
    private String statusAt;

    /**
     * 在当前环节是否已超出时限。只有部门审批中 / 复审中才可能为真，
     * 其余状态一律 false（草稿与已终结的单子不计时）。
     */
    private Boolean overdue;

    /** 超出时限多久（小时）；未超时为 0，不计时为 null */
    private Long overdueHours;

    /** 被申请人催过几次；没人催过是 0，不是 null */
    private Integer remindCnt;
}
