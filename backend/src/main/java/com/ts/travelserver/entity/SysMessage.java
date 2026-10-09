package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内消息（sys_message）：把「有事找你」推到收件人眼前。
 *
 * <p>补的是这样一个缺口：催办只写 apply_remind、审批结论只写 approval_record，
 * 当事人不主动打开对应页面就一个字都看不到。员工点了催办，审批人那边毫无动静，
 * 只有某天翻待办列表才偶然发现「这张单已被催 3 次」——催办这个动作等于白做。
 *
 * <p>四类消息：
 * <ul>
 *   <li>{@link #TYPE_APPLY_REMIND} 催办，收件人是当前环节的审批人</li>
 *   <li>{@link #TYPE_APPLY_RESULT} 审批结论，收件人是申请人</li>
 *   <li>{@link #TYPE_EXPENSE_REVIEW} 复核结论，收件人是报销人</li>
 *   <li>{@link #TYPE_EXPENSE_PAID} 已付款，收件人是报销人</li>
 * </ul>
 *
 * <p>它只是通知，不是业务数据：业务接口一律不读这张表，删掉它不影响任何单据状态。
 * 发送失败也绝不能把主业务回滚掉（见 {@code MessageService.send} 的静默处理）。
 */
@Data
@TableName("sys_message")
public class SysMessage {

    /** 催办：有人催他手上压着的单 */
    public static final String TYPE_APPLY_REMIND = "APPLY_REMIND";
    /** 审批结论：初审通过 / 复审通过 / 驳回 */
    public static final String TYPE_APPLY_RESULT = "APPLY_RESULT";
    /** 复核结论：通过 / 带异常通过 / 退回 */
    public static final String TYPE_EXPENSE_REVIEW = "EXPENSE_REVIEW";
    /** 已付款 */
    public static final String TYPE_EXPENSE_PAID = "EXPENSE_PAID";

    // ---------- 关联单据类型 ----------
    public static final String BIZ_APPLY = "APPLY";
    public static final String BIZ_EXPENSE = "EXPENSE";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收件人 */
    private Long userId;

    /** 消息类型，取值见本类 TYPE_* 常量 */
    private String msgType;

    /** 关联单据类型，取值见本类 BIZ_* 常量 */
    private String bizType;

    /** 关联单号，点消息跳详情用 */
    private String bizNo;

    private String title;

    private String content;

    /** 是否已读：0 未读 / 1 已读 */
    private Integer readFlag = 0;

    private LocalDateTime createdAt;

    private LocalDateTime readAt;
}
