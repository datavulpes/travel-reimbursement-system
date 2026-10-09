package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 申请单催办记录（apply_remind）。
 *
 * <p>员工催一次写一行，只追加。它与 approval_record 是两回事：
 * 后者是「审批人做了什么」，前者是「申请人等不及了」——
 * 审批流转全靠单据状态，催办不改变任何状态，所以必须单独记。
 *
 * <p>为什么不用 config_log：那是配置变更（差标、预算）的留痕，
 * 把催办塞进去会让「配置被谁改过」这本账混进业务动作，事后对不上。
 *
 * <p>节流（同一人 24 小时只能催一次）就落在这张表上：
 * 与其在内存里记「谁催过」，不如以库里的行为准——重启了也一样。
 */
@Data
@TableName("apply_remind")
public class ApplyRemind {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被催的申请单号 */
    private String applyNo;

    /** 催办时单据所处的环节，取值见 TripApply 的 STATUS_* */
    private String step;

    /** 谁催的，关联 sys_user.id */
    private Long fromUserId;

    /** 催办时留的一句话，可空 */
    private String note;

    private LocalDateTime remindAt = LocalDateTime.now();
}
