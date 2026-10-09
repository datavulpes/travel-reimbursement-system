package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批记录（approval_record）：两级审批各留一条。
 *
 * <p>数据字典 8.8。不引工作流引擎，状态字段 + 这张记录表就是全部审批流（设计报告 2.3）。
 *
 * <p>snapshot 是依据快照：审批那一刻把页面展示的参考值、差标结论、评价结论
 * 序列化成 JSON 存下来，做到「批有依据、拒有理由、事后可溯」（FR-0107）。
 */
@Data
@TableName("approval_record")
public class ApprovalRecord {

    // ---------- 审批环节 ----------
    /** 部门初审，由 dept.leader_id 指定的人审 */
    public static final String STEP_DEPT_REVIEW = "DEPT_REVIEW";
    /** 管理层复审。需求文档写作 LEADER_REVIEW，这里跟前端 dict.js 保持一致 */
    public static final String STEP_FINAL_REVIEW = "FINAL_REVIEW";
    /** 提交动作，写一条留痕让流转记录看起来完整 */
    public static final String STEP_SUBMIT = "SUBMIT";

    /**
     * 手工关闭。员工把「批了但没成行、也没费用要报」的单归档时写一条。
     *
     * <p>它不是审批环节（没有判断成分），但一样要留痕：
     * 单据状态从 APPROVED 变成 CLOSED 是台账级的变动，事后必须能查到是谁、什么时候关的。
     * 与 {@link #STEP_SUBMIT} 的性质相同——都是「动作留痕」而非「审批结论」。
     */
    public static final String STEP_CLOSE = "CLOSE";

    // ---------- 审批结论 ----------
    public static final String RESULT_APPROVED = "APPROVED";
    /** 驳回，opinion 必填（BR-03） */
    public static final String RESULT_REJECTED = "REJECTED";
    /** 手工关闭写在 result 列上的取值，与 step=CLOSE 成对出现 */
    public static final String RESULT_CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请号 */
    private String applyNo;

    /** 审批人 */
    private Long approverId;

    /** 环节，取值见本类 STEP_* 常量 */
    private String step;

    /** 结论，取值见本类 RESULT_* 常量 */
    private String result;

    /** 审批意见，驳回时必填，不超过 200 字 */
    private String opinion;

    /**
     * 依据快照（JSON 文本）：存参考区间、差标结论、评价档位等当时页面上的数。
     *
     * <p>列类型是 JSON，这里用 String 收，Service 层用 ObjectMapper 读写，
     * 省掉 Jackson 版本适配的麻烦。
     */
    private String snapshot;

    private LocalDateTime approvedAt = LocalDateTime.now();
}
