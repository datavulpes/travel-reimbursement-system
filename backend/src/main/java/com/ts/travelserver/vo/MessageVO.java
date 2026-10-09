package com.ts.travelserver.vo;

import lombok.Data;

import java.time.format.DateTimeFormatter;

/**
 * 消息中心的一行。
 *
 * <p>bizType + bizNo 一起给前端，点消息直接跳对应详情页：
 * APPLY 跳申请详情，EXPENSE 跳报销详情。两个都为空就是纯通知，点了没地方去。
 */
@Data
public class MessageVO {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Long id;

    /** 消息类型，取值见 SysMessage 的 TYPE_* */
    private String msgType;

    /** 页面上的类型名：催办 / 审批结论 / 复核结论 / 已付款 */
    private String msgTypeText;

    /** APPLY / EXPENSE */
    private String bizType;

    /** 申请号或报销单号 */
    private String bizNo;

    private String title;

    private String content;

    /** 是否已读 */
    private Boolean readFlag;

    /** 生成时间，yyyy-MM-dd HH:mm:ss */
    private String createdAt;

    public static MessageVO of(com.ts.travelserver.entity.SysMessage entity) {
        MessageVO vo = new MessageVO();
        vo.setId(entity.getId());
        vo.setMsgType(entity.getMsgType());
        vo.setMsgTypeText(typeText(entity.getMsgType()));
        vo.setBizType(entity.getBizType());
        vo.setBizNo(entity.getBizNo());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setReadFlag(entity.getReadFlag() != null && entity.getReadFlag() == 1);
        vo.setCreatedAt(entity.getCreatedAt() == null ? null : entity.getCreatedAt().format(DATE_TIME));
        return vo;
    }

    private static String typeText(String type) {
        if (type == null) {
            return "通知";
        }
        return switch (type) {
            case com.ts.travelserver.entity.SysMessage.TYPE_APPLY_REMIND -> "催办";
            case com.ts.travelserver.entity.SysMessage.TYPE_APPLY_RESULT -> "审批结论";
            case com.ts.travelserver.entity.SysMessage.TYPE_EXPENSE_REVIEW -> "复核结论";
            case com.ts.travelserver.entity.SysMessage.TYPE_EXPENSE_PAID -> "已付款";
            default -> "通知";
        };
    }
}
