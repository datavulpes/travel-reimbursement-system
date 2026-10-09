package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工修正留痕（ai_correction）：系统给了一个值，人改成了另一个值。
 *
 * <p>和 ai_log 的分工：ai_log 记「模型这一次跑得怎么样」，
 * 这里记「人最终认不认这个值」。前者是运行状况，后者是效果——
 * <b>采纳率只说「有没有被用」，修正记录才说「用的时候改了什么」</b>，
 * 后者才是指向具体 prompt 该改哪一句的证据。
 *
 * <p>两个采集点：
 * <ul>
 *   <li><b>票据 OCR</b>：识别回填票号 / 金额 / 日期 / 销售方，员工核对手改。
 *       这是全系统唯一「系统先给了值、人逐项核对」的地方。</li>
 *   <li><b>填写助手</b>：建议值 vs 最终提交值。同一个字段被反复改，
 *       说明这个字段的建议口径有问题。</li>
 * </ul>
 *
 * <p>old_value / new_value 都用字符串存：票号是数字串、金额是小数、日期是文本，
 * 统一成文本才不用为每种类型开一列，比对时按各自的口径比（金额会先抹掉尾零）。
 *
 * <p>跟 ai_log 一样只追加，不更新、不删除。
 */
@Data
@TableName("ai_correction")
public class AiCorrection {

    /** 来源：OCR 票据回填 */
    public static final String SCENE_OCR_INVOICE = "OCR_INVOICE";
    /** 来源：填写助手建议 */
    public static final String SCENE_APPLY_ASSIST = "APPLY_ASSIST";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源场景，取值见本类 SCENE_* 常量 */
    private String scene;

    /** 单据类型：INVOICE / TRIP_APPLY；还没落库的新建单留空 */
    private String bizType;

    /** 单号。票据是发票号，申请单是申请号（新建单用 NEW + 用户 id） */
    private String bizNo;

    /** 被改的字段名：invoiceNo / amount / invoiceDate / seller / reason / tripType 等 */
    private String fieldName;

    /** 系统给的值（OCR 识别值 / AI 建议值） */
    private String oldValue;

    /** 人最终填进去的值 */
    private String newValue;

    /** 修改人 */
    private Long operatorId;

    private LocalDateTime createdAt = LocalDateTime.now();
}
