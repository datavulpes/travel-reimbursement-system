package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 理由字段草稿（field_draft）：一句话理由没写完，先替人存着。
 *
 * <p>数据字典 8.15 之后新增的第十八张表。它不参与任何业务计算，
 * 也不进任何统计——存在的唯一理由是：审批意见、复核说明这类文本框
 * 往往要斟酌很久，写到一半被别的事打断、浏览器崩了、误点了别的菜单，
 * 几百字就没了。整张单据的草稿（trip_apply 的 DRAFT 状态）管不了这种
 * 「只有一个框没填完」的情况。
 *
 * <p>定位成「通用」而不是给每个场景单独建表：五个落点（审批意见、复核说明、
 * 付款备注、强制提交说明、超标说明）形状完全一样——都是「某人 + 某张单 +
 * 某个字段 = 一段文字」。差别只在 biz_type 与 field_name 这两个键上，
 * 用一个四元组唯一键就够，没必要铺五张表。
 *
 * <p>与正式字段的关系：草稿只是暂存，正式值永远在业务表上
 * （approval_record.opinion / payment_record.pay_remark / expense_report.force_reason 等）。
 * 提交成功的那一刻草稿即失效，Service 层顺手清掉，不留过期内容。
 */
@Data
@TableName("field_draft")
public class FieldDraft {

    // ---------- 业务场景：决定草稿挂在哪个业务动作上 ----------
    /** 审批意见，biz_no 记申请号 */
    public static final String BIZ_APPLY_REVIEW = "APPLY_REVIEW";
    /** 财务复核说明，biz_no 记报销单号 */
    public static final String BIZ_EXPENSE_REVIEW = "EXPENSE_REVIEW";
    /** 财务付款备注，biz_no 记报销单号 */
    public static final String BIZ_EXPENSE_PAY = "EXPENSE_PAY";
    /** 员工强制提交说明，biz_no 记申请号（此时报销单还没生成） */
    public static final String BIZ_EXPENSE_SUBMIT = "EXPENSE_SUBMIT";
    /** 员工超标说明，biz_no 记申请号 */
    public static final String BIZ_APPLY_SUBMIT = "APPLY_SUBMIT";

    // ---------- 字段名：与前端表单的字段名逐字一致 ----------
    public static final String FIELD_OPINION = "opinion";
    public static final String FIELD_REVIEW_REASON = "reviewReason";
    public static final String FIELD_PAY_REMARK = "payRemark";
    public static final String FIELD_FORCE_REASON = "forceReason";
    public static final String FIELD_EXCEED_REASON = "exceedReason";

    /** 内容上限，与列宽 VARCHAR(200) 对齐，也和其余理由字段的 200 字口径一致 */
    public static final int MAX_LEN = 200;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务场景，取值见本类 BIZ_* 常量 */
    private String bizType;

    /** 业务单号：申请号或报销单号，取决于 bizType */
    private String bizNo;

    /** 字段名，取值见本类 FIELD_* 常量 */
    private String fieldName;

    /** 草稿归属人，指向 sys_user.id；只认本人的草稿，避免 A 看到 B 写的驳回理由 */
    private Long userId;

    /** 草稿正文 */
    private String content;

    /** 最后一次保存时间 */
    private LocalDateTime updatedAt;
}
