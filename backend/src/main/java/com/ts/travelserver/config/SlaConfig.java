package com.ts.travelserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 审批时限（SLA）配置，对应 application.yml 的 travel.sla.*。
 *
 * <p>为什么要有「时限」这个概念：单据状态机解决了「下一步去哪」，
 * 没解决「在那一步待多久算不正常」。没有时限，「待办」就只是一个堆着单子的列表，
 * 看的人分不清哪张单是刚提交的、哪张已经压了三天。
 *
 * <p>三条刻意的取舍：
 * <ul>
 *   <li><b>只约束在审环节</b>。草稿、已通过、已驳回都不计时——
 *       草稿是员工自己的事，驳回之后球在员工手上，催审批人没有意义。</li>
 *   <li><b>超时不自动处理</b>。系统只标「已逾期」，绝不会自动通过、自动退回、
 *       自动转给别人。自动处置会让人不知道自己的单被谁动了；
 *       这里要的是把压住的单子暴露出来，而不是替人做决定。</li>
 *   <li><b>催办只走站内</b>。写一行记录、在待办列表上标「已催 N 次」，
 *       再给当前环节的审批人推一条站内消息（{@code sys_message}）。
 *       不发邮件、不发短信——这个项目没有外网通道，做一个发不出去的通道是自欺。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.sla")
public class SlaConfig {

    /** 部门审批时限（小时） */
    private int deptReviewHours = 48;

    /** 复审时限（小时） */
    private int finalReviewHours = 48;

    /** 同一张单同一人的催办间隔（小时），防止狂点刷屏 */
    private int remindIntervalHours = 24;

    /**
     * 这个环节的时限（小时）。不在审的状态返回 0，表示不计时。
     */
    public int hoursOf(String status) {
        if (com.ts.travelserver.entity.TripApply.STATUS_DEPT_REVIEW.equals(status)) {
            return deptReviewHours;
        }
        if (com.ts.travelserver.entity.TripApply.STATUS_FINAL_REVIEW.equals(status)) {
            return finalReviewHours;
        }
        return 0;
    }
}
