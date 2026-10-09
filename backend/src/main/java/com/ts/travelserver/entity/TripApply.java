package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出差申请单（trip_apply）：全系统的业务主线。
 *
 * <p>数据字典 8.4。行程段、审批记录、预借款、票据、报销单、业务结果、评价结果
 * 全部通过 applyNo 挂到这张单上（设计报告 4.1：1:n 联系并入 n 端，不建中间表）。
 *
 * <p>申请号规则 TA + yyyyMMdd + 4 位当日序号，例如 TA202609190042（设计报告 6.3.2）。
 */
@Data
@TableName("trip_apply")
public class TripApply {

    // ---------- 申请单六态（数据字典 8.4） ----------
    /** 草稿 */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 部门审批中（初审） */
    public static final String STATUS_DEPT_REVIEW = "DEPT_REVIEW";
    /** 管理层复审中。需求文档写作 LEADER_REVIEW，这里跟前端 dict.js 保持一致 */
    public static final String STATUS_FINAL_REVIEW = "FINAL_REVIEW";
    /** 已通过，复审通过瞬间生成预借款 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 已驳回，驳回原因必填 */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 已关闭，报销完成后可关闭 */
    public static final String STATUS_CLOSED = "CLOSED";

    // ---------- 差标校验三档（数据字典 8.4） ----------
    /** 未超上限 */
    public static final String STANDARD_NORMAL = "NORMAL";
    /** 超出 10% 以内，偏高 */
    public static final String STANDARD_HIGH = "HIGH";
    /** 超出 10% 以上，超限，必须填超标说明才能提交（BR-01） */
    public static final String STANDARD_EXCEED = "EXCEED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请号，全局唯一，业务主线 */
    private String applyNo;

    /** 申请人 */
    private Long userId;

    /** 申请部门 */
    private Long deptId;

    /** 出差事由，不超过 200 字，写得越具体 F4 事由完整度得分越高 */
    private String reason;

    /** 关联往来对象 */
    private Long partnerId;

    /**
     * 出差类型。查同类花费参考要按（目的地 + 出差类型）匹配 cost_ref，类型的字面值因此不能随便改。
     *
     * <p>值域六个：验货 / 商务拜访 / 项目交付 / 供应商审核 / 展会 / 其他。
     * 三处必须逐字一致——本注释、schema.sql 里 trip_apply 与 cost_ref 的 comment、
     * 前端 utils/dict.js 的 TRIP_TYPES。字典里写成别的名字，员工在页面上就选不出这个值，
     * cost_ref 里对应那一组参考会永远匹配不上，而且不报错。
     */
    private String tripType;

    /**
     * 出差类型选「其他」时的补充说明。非必填。
     *
     * <p>六个类型里只有「其他」需要它：前五个审批人一看就知道这趟是干什么的，
     * 「其他」两个字本身不携带任何信息，没有这句补充，审批人和费效分析都只能看到一个空壳类型。
     * 之所以不设成必填——真有说不清归类的事由（比如内部借调、临时支援），
     * 逼员工硬写一句反而会写出「其他事项」这种等于没写的废话。
     *
     * <p>它不参与 cost_ref 匹配：同类花费参考按 tripType 字面值匹配，「其他」这一组本来就宽，
     * 把说明文字并进匹配键只会让这一组再也匹配不上。
     */
    private String tripTypeNote;

    /** 预估交通费 */
    private BigDecimal estTraffic;

    /** 预估住宿费 */
    private BigDecimal estHotel;

    /**
     * 预估合计。刻意保留的派生字段（交通 + 住宿），
     * 后端算完写入，避免前端各自加和导致口径不一（设计报告 5.6）。
     */
    private BigDecimal estTotal;

    /** 差标校验结论，取值见本类 STANDARD_* 常量 */
    private String standardResult;

    /** 超标说明，差标超限时必填 */
    private String exceedReason;

    /** 状态，取值见本类 STATUS_* 常量 */
    private String status;

    /**
     * 进入当前状态的时间。
     *
     * <p>加这一列是为了算「在这一步待了多久」：状态机只说了下一步去哪，
     * 没说在那一步待多久算不正常。没有它，待办列表就只是一堆单子，
     * 看的人分不清哪张是刚提交的、哪张已经压了三天。
     *
     * <p>每次状态流转都要跟着改（提交 / 审批 / 结案）。老数据这一列是 NULL，
     * 页面按「不计时」处理，不拿创建时间冒充。
     */
    private LocalDateTime statusAt;

    /** 提交时间 */
    private LocalDateTime createdAt = LocalDateTime.now();
}
