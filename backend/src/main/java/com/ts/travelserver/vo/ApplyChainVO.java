package com.ts.travelserver.vo;

import lombok.Data;

import java.util.List;

/**
 * 审批链：一张单从提交到走完，中间经过哪几步、每步谁办的、卡在谁的桌上多久。
 *
 * <p>此前详情接口只把 approval_record 原样列出来，看的人得自己在脑子里拼：
 * 这一步办没办、现在轮到谁、压了多久。这里把它拼成固定四段的链条，
 * 每段带一个状态，前端照着画时间轴就行，不用在页面里做判断。
 *
 * <p>四段固定：提交 → 部门初审 → 管理层复审 → 完成。
 * 驳回不额外加段——驳回发生在哪一级，那一段的结论就是「驳回」，
 * 后面没走到的段标 {@link StepVO#STATE_SKIPPED}，一眼能看出是在哪一级卡住的。
 */
@Data
public class ApplyChainVO {

    /**
     * 最后一段的环节代码。它不是审批环节（没人在这段上下结论），
     * 只是给链条一个终点：通过 / 关闭都落在这里，驳回则整段跳过。
     */
    public static final String STEP_FINISH = "FINISH";

    private String applyNo;

    /** 单据当前状态，取值见 TripApply 的 STATUS_* */
    private String status;

    /** 当前停在哪一段：SUBMIT / DEPT_REVIEW / FINAL_REVIEW / FINISH */
    private String currentStep;

    /** 当前环节是否已超时（只在审环节才可能为 true） */
    private Boolean overdue;

    /** 超时了多少小时，没超时为 0 */
    private Long overdueHours;

    /** 这张单被催过几次 */
    private Integer remindCnt;

    private List<StepVO> steps;

    @Data
    public static class StepVO {

        /** 已完成，有结论 */
        public static final String STATE_DONE = "DONE";
        /** 正在这一步，等人处理 */
        public static final String STATE_CURRENT = "CURRENT";
        /** 还没轮到 */
        public static final String STATE_PENDING = "PENDING";
        /** 永远不会走到（前面被驳回，或这单没走完就关了） */
        public static final String STATE_SKIPPED = "SKIPPED";

        /** SUBMIT / DEPT_REVIEW / FINAL_REVIEW / FINISH */
        private String step;

        /** 环节名：提交申请 / 部门初审 / 管理层复审 / 完成 */
        private String stepText;

        /** 状态，取值见本类 STATE_* 常量 */
        private String state;

        /** 这一步该谁办。已完成的写实际办理人，等待中的写该环节的责任人 */
        private String actorName;

        /** 这一步的角色说明：申请人 / 部门负责人 / 管理层 */
        private String actorRole;

        /** 结论：APPROVED / REJECTED / CLOSED，等待中为 null */
        private String result;

        /** 结论的中文名：通过 / 驳回 / 已关闭 */
        private String resultText;

        /** 审批意见或关闭说明 */
        private String opinion;

        /** 办理时间，yyyy-MM-dd HH:mm:ss；等待中的这一步为 null */
        private String at;

        /**
         * 在这一步上耗了多少小时。
         *
         * <p>已完成的算到下一步发生为止（真实耗时）；
         * 等待中的算到当前时刻（已经压了多久）；
         * status_at 为空的老数据算不出来，给 null——拿提交时间去凑一个数是假数字。
         */
        private Long waitHours;

        /** 这一步限时多少小时，只有两个审批环节有值 */
        private Integer limitHours;
    }
}
