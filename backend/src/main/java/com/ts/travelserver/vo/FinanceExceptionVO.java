package com.ts.travelserver.vo;

import lombok.Data;

import java.util.List;

/**
 * 四维比对异常项（E-02）。
 *
 * <p>人、事、票、账四个维度在报销单提交瞬间自动比对，异常按维度分组返回。
 * 只列事实与原始依据链接，不判违规——结论由财务下（BR-06）。
 *
 * <p>refType + refId 让财务能一键跳到出问题的那张票或那条记录，
 * srcValue 是系统当时比对的原始值，方便人工核对。
 */
@Data
public class FinanceExceptionVO {

    // ---------- 异常编码 ----------
    /** 人维：报销人不是申请人 */
    public static final String PERSON_NOT_APPLICANT = "PERSON_NOT_APPLICANT";
    /** 人维：票据抬头不是本公司 */
    public static final String PERSON_TITLE_MISMATCH = "PERSON_TITLE_MISMATCH";
    /** 事维：票据日期不在任何行程段内 */
    public static final String MATTER_DATE_MISMATCH = "DATE_MISMATCH";
    /** 事维：票据地点与行程对不上 */
    public static final String MATTER_PLACE_MISMATCH = "PLACE_MISMATCH";
    /** 事维：事由与结果回填对不上 */
    public static final String MATTER_RESULT_MISMATCH = "RESULT_MISMATCH";
    /** 票维：发票号重复 */
    public static final String INVOICE_DUP = "INV_DUP";
    /** 票维：缺影像 */
    public static final String INVOICE_NO_IMAGE = "INV_NO_IMAGE";
    /** 票维：金额不合法 */
    public static final String INVOICE_AMOUNT_INVALID = "INV_AMOUNT_INVALID";
    /** 账维：票据合计与报销单总额对不上 */
    public static final String ACCOUNT_TOTAL_DIFF = "TOTAL_DIFF";
    /** 账维：净额不等于总额减冲抵 */
    public static final String ACCOUNT_NET_DIFF = "NET_DIFF";
    /** 账维：冲抵超出预借余额 */
    public static final String ACCOUNT_OFFSET_OVER = "OFFSET_OVER";

    // ---------- 维度分组键 ----------
    public static final String DIM_PERSON = "PERSON";
    public static final String DIM_MATTER = "MATTER";
    public static final String DIM_INVOICE = "INVOICE";
    public static final String DIM_ACCOUNT = "ACCOUNT";

    /** 异常编码，取值见本类常量 */
    private String issueCode;

    /** 异常描述，直接展示给财务 */
    private String desc;

    /**
     * 依据类型：INVOICE 票据、APPLY 申请单、ITINERARY 行程段、LOAN 预借款。
     * 财务点异常项时跳到对应单据。
     */
    private String refType;

    /** 依据 id：票号、申请号或记录 id，取决于 refType */
    private String refId;

    /** 系统比对的原始值，例如开票日期 2026-09-22 */
    private String srcValue;

    /**
     * 异常等级：WARN 提醒、ERROR 必须处理。前端按等级上色。
     */
    private String level;

    /**
     * 四维比对的整份结果，作为 E-02 的 data 返回。
     *
     * <p>设计报告 7.4：返回 personIssues / matterIssues / invoiceIssues / accountIssues
     * 四个分组、异常总数，以及是否允许带异常通过。
     */
    @Data
    public static class FourDimResult {

        /** 报销单号 */
        private String reportNo;

        /** 人维异常 */
        private List<FinanceExceptionVO> personIssues;

        /** 事维异常 */
        private List<FinanceExceptionVO> matterIssues;

        /** 票维异常 */
        private List<FinanceExceptionVO> invoiceIssues;

        /** 账维异常 */
        private List<FinanceExceptionVO> accountIssues;

        /** 异常总数，前端默认勾选的复核结论要看它 */
        private Integer issueCount;

        /** 是否允许带异常通过。恒为 true，但结论仍由财务下 */
        private Boolean canForcePass;
    }
}
