package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报销单（expense_report）：一张申请一张报销单。
 *
 * <p>数据字典 8.11。reportNo 与 applyNo 都唯一，applyNo 的唯一键同时是「一申请一单」的约束。
 *
 * <p>三个金额是刻意保留的冗余：总额、冲抵、净额必须同时可见可审，
 * 复核后不可改（BR-10，设计报告 5.6）。
 * total = 票据金额之和，offset = min(预借余额, total)，net = total − offset。
 *
 * <p>force_reason / review_reason 两列是后补的：原先这两句话只在请求体里过一遍，
 * 一个字都没落库——强制提交说明提交完就蒸发，财务的复核说明更是连列都没有，
 * 事后翻报销单只能看到结论，看不到「为什么」。理由类字段不落库等于没说过。
 */
@Data
@TableName("expense_report")
public class ExpenseReport {

    // ---------- 预检结论（数据字典 8.11） ----------
    /** 预检通过，可直接提交 */
    public static final String PRECHECK_PASS = "PASS";
    /** 有提醒，建议修改后提交 */
    public static final String PRECHECK_WARN = "WARN";
    /** 必须带说明强制提交，且留痕 */
    public static final String PRECHECK_FORCE = "FORCE";

    // ---------- 报销单状态 ----------
    /** 待复核 */
    public static final String STATUS_PENDING = "PENDING";
    /** 复核通过，可进入付款 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 带异常通过（财务下的结论，前端 dict.js 有这一档） */
    public static final String STATUS_PASS_WITH_ISSUE = "PASS_WITH_ISSUE";
    /** 已退回，退回原因必填 */
    public static final String STATUS_RETURNED = "RETURNED";
    /** 已付款 */
    public static final String STATUS_PAID = "PAID";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 报销单号 */
    private String reportNo;

    /** 申请号，唯一 */
    private String applyNo;

    /** 票据合计 */
    private BigDecimal totalAmount;

    /** 冲抵预借 */
    private BigDecimal offsetAmount = BigDecimal.ZERO;

    /** 净额付款 */
    private BigDecimal netAmount = BigDecimal.ZERO;

    /** 预检结论，取值见本类 PRECHECK_* 常量 */
    private String precheckResult;

    /** 状态，取值见本类 STATUS_* 常量 */
    private String status;

    /**
     * 强制提交说明：预检为 FORCE 时员工写的那句「为什么缺票也要报」。
     *
     * <p>提交时落库，之后可见不可改——它是 BR-05 留痕的那一半，
     * 财务复核时要能看到员工当时怎么说的。
     */
    private String forceReason;

    /**
     * 财务复核说明：带异常通过说明异常在哪、退回说明退在哪（BR-06）。
     *
     * <p>结论本身已经在 status 上，这一列存的是结论背后的那句话。
     * 通过（APPROVED）时可以不写，落库为 null。
     */
    private String reviewReason;

    private LocalDateTime createdAt = LocalDateTime.now();
}
