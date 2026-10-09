package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.ai.SuspicionDetector;
import com.ts.travelserver.dto.ApplyQuery;
import com.ts.travelserver.dto.FinanceReviewRequest;
import com.ts.travelserver.dto.PaymentRequest;
import com.ts.travelserver.entity.BizResult;
import com.ts.travelserver.entity.ExpenseReport;
import com.ts.travelserver.entity.FieldDraft;
import com.ts.travelserver.entity.Invoice;
import com.ts.travelserver.entity.LoanRecord;
import com.ts.travelserver.entity.Partner;
import com.ts.travelserver.entity.PaymentRecord;
import com.ts.travelserver.entity.SysMessage;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.mapper.BizResultMapper;
import com.ts.travelserver.mapper.ExpenseReportMapper;
import com.ts.travelserver.mapper.InvoiceMapper;
import com.ts.travelserver.mapper.LoanRecordMapper;
import com.ts.travelserver.mapper.PartnerMapper;
import com.ts.travelserver.mapper.PaymentRecordMapper;
import com.ts.travelserver.mapper.SysUserMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.mapper.TripItineraryMapper;
import com.ts.travelserver.vo.AiSuspicionVO;
import com.ts.travelserver.vo.ExpenseListItemVO;
import com.ts.travelserver.vo.FinanceExceptionVO;
import com.ts.travelserver.vo.PaymentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 财务复核与付款（模块 03，E-01 ~ E-04）。
 *
 * <p>四维比对在人、事、票、账四个维度上自动比对，只列事实与原始依据链接，
 * 不判违规——结论由财务下（BR-06）。
 *
 * <p>付款在一个事务里做三件事：写 payment_record、报销单置为已付款、
 * 预借款累加已冲抵金额。一笔预借对一笔付款，杜绝交叉冲抵（FR-0305）。
 */
@Service
@RequiredArgsConstructor
public class FinanceService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExpenseReportMapper expenseReportMapper;
    private final InvoiceMapper invoiceMapper;
    private final TripApplyMapper tripApplyMapper;
    private final TripItineraryMapper tripItineraryMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final BizResultMapper bizResultMapper;
    private final LoanRecordMapper loanRecordMapper;
    private final PartnerMapper partnerMapper;
    private final SysUserMapper sysUserMapper;
    private final LoanService loanService;
    private final ExpenseService expenseService;
    private final FieldDraftService fieldDraftService;
    private final SuspicionDetector suspicionDetector;
    private final MessageService messageService;

    /**
     * 本公司发票抬头，用来核对票据销售方是不是开给本公司。
     *
     * <p>TODO-需要你填写：在 application.yml 的 travel.company.invoice-title 里填本公司全称，
     * 留空表示不校验这一项（事维比对会少一条）。
     */
    @Value("${travel.company.invoice-title:}")
    private String companyInvoiceTitle;

    // ---------- E-01 待复核列表 ----------

    public PageResult<ExpenseListItemVO> pending(ApplyQuery query) {
        Page<ExpenseReport> page = new Page<>(query.safePageNum(), query.safePageSize());
        LambdaQueryWrapper<ExpenseReport> wrapper = new LambdaQueryWrapper<ExpenseReport>()
                .eq(StringUtils.hasText(query.getStatus()), ExpenseReport::getStatus, query.getStatus())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(ExpenseReport::getReportNo, query.getKeyword())
                        .or().like(ExpenseReport::getApplyNo, query.getKeyword()))
                .orderByAsc(ExpenseReport::getCreatedAt);
        if (!StringUtils.hasText(query.getStatus())) {
            wrapper.eq(ExpenseReport::getStatus, ExpenseReport.STATUS_PENDING);
        }
        expenseReportMapper.selectPage(page, wrapper);
        return PageResult.of(page, report -> expenseService.toListItem(report, check(report.getReportNo()).getIssueCount()));
    }

    /**
     * E-04 待付款列表：复核通过（APPROVED / PASS_WITH_ISSUE）但还没付款的单。
     *
     * <p>E-01 只查 PENDING，复核完单子就从列表里蒸发，而付款入口在详情页，
     * 财务点不回去等于没法付款。这个接口把「已过会、待打款」这一档单独列出来。
     */
    public PageResult<ExpenseListItemVO> payable(ApplyQuery query) {
        Page<ExpenseReport> page = new Page<>(query.safePageNum(), query.safePageSize());
        LambdaQueryWrapper<ExpenseReport> wrapper = new LambdaQueryWrapper<ExpenseReport>()
                .in(ExpenseReport::getStatus,
                        ExpenseReport.STATUS_APPROVED, ExpenseReport.STATUS_PASS_WITH_ISSUE)
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(ExpenseReport::getReportNo, query.getKeyword())
                        .or().like(ExpenseReport::getApplyNo, query.getKeyword()))
                .orderByAsc(ExpenseReport::getCreatedAt);
        expenseReportMapper.selectPage(page, wrapper);
        return PageResult.of(page, report -> expenseService.toListItem(report, check(report.getReportNo()).getIssueCount()));
    }

    // ---------- E-02 四维比对 ----------

    /**
     * 人、事、票、账四个维度逐一比对，异常按维度分组返回。
     * 只列事实不判违规，canForcePass 恒为 true（BR-06）。
     */
    public FinanceExceptionVO.FourDimResult check(String reportNo) {
        ExpenseReport report = expenseService.load(reportNo);
        List<Invoice> invoices = invoiceMapper.selectList(
                new LambdaQueryWrapper<Invoice>().eq(Invoice::getApplyNo, report.getApplyNo()));
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, report.getApplyNo()));
        List<TripItinerary> itineraries = tripItineraryMapper.selectList(
                new LambdaQueryWrapper<TripItinerary>().eq(TripItinerary::getApplyNo, report.getApplyNo()));
        LoanRecord loan = loanService.load(report.getApplyNo());

        List<FinanceExceptionVO> person = new ArrayList<>();
        List<FinanceExceptionVO> matter = new ArrayList<>();
        List<FinanceExceptionVO> invoiceIssues = new ArrayList<>();
        List<FinanceExceptionVO> account = new ArrayList<>();

        // ---- 人维：报销人是不是申请人、票据抬头是不是本公司 ----
        if (apply == null || apply.getUserId() == null) {
            person.add(issue(FinanceExceptionVO.PERSON_NOT_APPLICANT,
                    "找不到该报销单对应的申请人", "APPLY", report.getApplyNo(), null, "ERROR"));
        }
        if (StringUtils.hasText(companyInvoiceTitle)) {
            for (Invoice invoice : invoices) {
                if (invoice.getSeller() == null || !invoice.getSeller().contains(companyInvoiceTitle)) {
                    person.add(issue(FinanceExceptionVO.PERSON_TITLE_MISMATCH,
                            "票据销售方与本公司抬头不一致", "INVOICE", invoice.getInvoiceNo(),
                            invoice.getSeller(), "WARN"));
                }
            }
        }

        // ---- 事维：票据日期落在行程段内、事由与结果回填呼应 ----
        for (Invoice invoice : invoices) {
            if (invoice.getInvoiceDate() == null || !inRange(invoice.getInvoiceDate(), itineraries)) {
                matter.add(issue(FinanceExceptionVO.MATTER_DATE_MISMATCH,
                        "票据开票日期不在任何行程段内", "INVOICE", invoice.getInvoiceNo(),
                        String.valueOf(invoice.getInvoiceDate()), "WARN"));
            }
        }
        Long resultCnt = bizResultMapper.selectCount(
                new LambdaQueryWrapper<BizResult>().eq(BizResult::getApplyNo, report.getApplyNo()));
        if (resultCnt == null || resultCnt == 0) {
            matter.add(issue(FinanceExceptionVO.MATTER_RESULT_MISMATCH,
                    "该申请还没有回填业务结果，无法核对事由与产出", "APPLY", report.getApplyNo(), null, "WARN"));
        }

        // ---- 票维：查重、影像、金额 ----
        for (Invoice invoice : invoices) {
            if (StringUtils.hasText(invoice.getDupOf())) {
                invoiceIssues.add(issue(FinanceExceptionVO.INVOICE_DUP,
                        "发票号与申请 " + invoice.getDupOf() + " 重复", "INVOICE", invoice.getInvoiceNo(),
                        invoice.getDupOf(), "ERROR"));
            }
            if (!StringUtils.hasText(invoice.getImgPath())) {
                invoiceIssues.add(issue(FinanceExceptionVO.INVOICE_NO_IMAGE,
                        "票据缺少影像", "INVOICE", invoice.getInvoiceNo(), null, "WARN"));
            }
            if (invoice.getAmount() == null || invoice.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                invoiceIssues.add(issue(FinanceExceptionVO.INVOICE_AMOUNT_INVALID,
                        "票据金额不合法", "INVOICE", invoice.getInvoiceNo(),
                        String.valueOf(invoice.getAmount()), "ERROR"));
            }
        }

        // ---- 账维：三个金额的勾稽 ----
        BigDecimal sum = BigDecimal.ZERO;
        for (Invoice invoice : invoices) {
            sum = sum.add(invoice.getAmount() == null ? BigDecimal.ZERO : invoice.getAmount());
        }
        if (report.getTotalAmount() == null || report.getTotalAmount().compareTo(sum) != 0) {
            account.add(issue(FinanceExceptionVO.ACCOUNT_TOTAL_DIFF,
                    "报销单总额与票据金额之和不一致", "APPLY", report.getApplyNo(),
                    "总额 " + report.getTotalAmount() + " / 票据合计 " + sum, "ERROR"));
        }
        BigDecimal expectNet = (report.getTotalAmount() == null ? BigDecimal.ZERO : report.getTotalAmount())
                .subtract(report.getOffsetAmount() == null ? BigDecimal.ZERO : report.getOffsetAmount());
        if (report.getNetAmount() == null || report.getNetAmount().compareTo(expectNet) != 0) {
            account.add(issue(FinanceExceptionVO.ACCOUNT_NET_DIFF,
                    "净额不等于总额减冲抵", "APPLY", report.getApplyNo(),
                    "净额 " + report.getNetAmount() + " / 应为 " + expectNet, "ERROR"));
        }
        if (report.getOffsetAmount() != null && report.getOffsetAmount().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal remain = loan == null ? BigDecimal.ZERO : loan.remainAmount();
            if (report.getOffsetAmount().compareTo(remain.add(report.getOffsetAmount())) > 0) {
                account.add(issue(FinanceExceptionVO.ACCOUNT_OFFSET_OVER,
                        "冲抵金额超出预借余额", "LOAN", String.valueOf(loan == null ? null : loan.getId()),
                        "冲抵 " + report.getOffsetAmount() + " / 余额 " + remain, "ERROR"));
            }
        }

        FinanceExceptionVO.FourDimResult result = new FinanceExceptionVO.FourDimResult();
        result.setReportNo(reportNo);
        result.setPersonIssues(person);
        result.setMatterIssues(matter);
        result.setInvoiceIssues(invoiceIssues);
        result.setAccountIssues(account);
        result.setIssueCount(person.size() + matter.size() + invoiceIssues.size() + account.size());
        // 恒为 true：系统只列事实，结论由财务下（BR-06）
        result.setCanForcePass(true);
        return result;
    }

    /**
     * E-05 可疑模式提示：AI 在四维比对之外再抓一遍「太巧了」的模式。
     *
     * <p>只提示不判违规，也不写库——它是一份给财务看的清单，
     * 真正的结论在 E-03 里由人下。
     *
     * <p>为什么独立成一个接口而不是并进 check：check 在列表页每翻一页就要跑 N 次
     * （要拿异常数），把这套比对塞进去会让列表变慢；而可疑模式只有打开详情页才需要。
     */
    public AiSuspicionVO suspicion(String reportNo) {
        ExpenseReport report = expenseService.load(reportNo);
        List<Invoice> invoices = invoiceMapper.selectList(
                new LambdaQueryWrapper<Invoice>().eq(Invoice::getApplyNo, report.getApplyNo()));
        return suspicionDetector.detect(reportNo, invoices);
    }

    // ---------- E-03 复核结论 ----------

    @Transactional(rollbackFor = Exception.class)
    public void review(String reportNo, FinanceReviewRequest request) {
        ExpenseReport report = expenseService.load(reportNo);
        if (!ExpenseReport.STATUS_PENDING.equals(report.getStatus())) {
            throw BusinessException.statusForbidden("只有待复核的报销单可以做复核，当前状态为 " + report.getStatus());
        }
        if (!ExpenseReport.STATUS_APPROVED.equals(request.getResult())
                && !ExpenseReport.STATUS_PASS_WITH_ISSUE.equals(request.getResult())
                && !ExpenseReport.STATUS_RETURNED.equals(request.getResult())) {
            throw BusinessException.paramError("复核结论只能是 APPROVED / PASS_WITH_ISSUE / RETURNED");
        }
        if (request.needRemark() && !StringUtils.hasText(request.getReason())) {
            throw BusinessException.paramError("带异常通过或退回时必须填写说明");
        }
        // 说明落库：结论在 status 上，理由在这一列，事后翻单要能看出「为什么这么判」
        report.setStatus(request.getResult());
        report.setReviewReason(request.getReason());
        expenseReportMapper.updateById(report);

        // 复核结论已经正式写入，草稿清掉
        fieldDraftService.clear(FieldDraft.BIZ_EXPENSE_REVIEW, reportNo, FieldDraft.FIELD_REVIEW_REASON);

        // 三种结论三种说法，退回那一条必须带上说明——那是员工改单的唯一依据
        String title = switch (request.getResult()) {
            case ExpenseReport.STATUS_APPROVED -> "报销单 " + reportNo + " 已通过财务复核";
            case ExpenseReport.STATUS_PASS_WITH_ISSUE -> "报销单 " + reportNo + " 带异常通过";
            default -> "报销单 " + reportNo + " 被退回，需要你改";
        };
        String body = switch (request.getResult()) {
            case ExpenseReport.STATUS_APPROVED -> "已进入待付款，付款完成后会再通知你。";
            case ExpenseReport.STATUS_PASS_WITH_ISSUE -> "财务说明：" + emptyToDash(request.getReason())
                    + "。款项照常支付，这些问题下次请避开。";
            default -> "退回原因：" + emptyToDash(request.getReason())
                    + "。改完之后重新提交报销。";
        };
        messageService.send(applicantId(report.getApplyNo()), SysMessage.TYPE_EXPENSE_REVIEW,
                SysMessage.BIZ_EXPENSE, reportNo, title, body);
    }

    // ---------- E-04 付款并冲抵预借 ----------

    @Transactional(rollbackFor = Exception.class)
    public PaymentVO pay(String reportNo, PaymentRequest request) {
        ExpenseReport report = expenseService.load(reportNo);
        if (!ExpenseReport.STATUS_APPROVED.equals(report.getStatus())
                && !ExpenseReport.STATUS_PASS_WITH_ISSUE.equals(report.getStatus())) {
            throw BusinessException.statusForbidden("只有复核通过的报销单可以付款，当前状态为 " + report.getStatus());
        }
        if (request.getPayAmount() == null || request.getPayAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw BusinessException.paramError("实付金额不能为负");
        }
        // 一笔预借对一笔付款，不交叉冲抵（FR-0305）
        Long paid = paymentRecordMapper.selectCount(
                new LambdaQueryWrapper<PaymentRecord>().eq(PaymentRecord::getReportNo, reportNo));
        if (paid != null && paid > 0) {
            throw BusinessException.statusForbidden("该报销单已付款，不能重复支付");
        }
        LoanRecord loan = loanRecordMapper.selectById(request.getLoanId());
        if (loan == null) {
            throw BusinessException.paramError("预借款不存在");
        }
        if (loan.getApplyNo() != null && !loan.getApplyNo().equals(report.getApplyNo())) {
            throw BusinessException.paramError("该预借款不属于本报销单对应的申请");
        }

        PaymentRecord record = new PaymentRecord();
        record.setReportNo(reportNo);
        record.setPayAmount(request.getPayAmount());
        record.setLoanId(loan.getId());
        record.setOperatorId(UserContext.currentId());
        record.setPayRemark(request.getPayRemark());
        paymentRecordMapper.insert(record);

        // 备注进了付款记录，草稿清掉
        fieldDraftService.clear(FieldDraft.BIZ_EXPENSE_PAY, reportNo, FieldDraft.FIELD_PAY_REMARK);

        report.setStatus(ExpenseReport.STATUS_PAID);
        expenseReportMapper.updateById(report);

        // 冲抵预借：累加已冲抵金额，冲完置为已结清
        loanService.offset(loan.getId(), report.getOffsetAmount() == null
                ? BigDecimal.ZERO
                : report.getOffsetAmount());

        // 冗余汇总：对象累计投入累加，月报不用每次全表汇总（设计报告 5.6）
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, report.getApplyNo()));
        if (apply != null && apply.getPartnerId() != null) {
            Partner partner = partnerMapper.selectById(apply.getPartnerId());
            if (partner != null) {
                BigDecimal invest = partner.getTotalInvest() == null ? BigDecimal.ZERO : partner.getTotalInvest();
                partner.setTotalInvest(invest.add(report.getTotalAmount() == null
                        ? BigDecimal.ZERO : report.getTotalAmount()));
                partnerMapper.updateById(partner);
            }
        }

        /*
         * 报销付完，这趟差在系统里就算结案：申请单推进到 CLOSED（设计报告 6.3.1「报销完成后可关闭」）。
         *
         * <p>这是全系统唯一产生 CLOSED 的地方，放在同一个事务里，
         * 付款失败回滚时单据不会变成「已关闭但没付钱」。
         *
         * <p>关了不是删——CLOSED 照样进 F1 历史产出率、同类花费参考、费效比月报的样本，
         * 票据补录和结果回填也都对它开着，事后补的东西一样入账。
         */
        if (apply != null && TripApply.STATUS_APPROVED.equals(apply.getStatus())) {
            apply.setStatus(TripApply.STATUS_CLOSED);
            apply.setStatusAt(LocalDateTime.now());
            tripApplyMapper.updateById(apply);
        }

        messageService.send(applicantId(report.getApplyNo()), SysMessage.TYPE_EXPENSE_PAID,
                SysMessage.BIZ_EXPENSE, reportNo,
                "报销单 " + reportNo + " 已付款",
                "实付 " + request.getPayAmount() + " 元"
                        + (report.getOffsetAmount() == null || report.getOffsetAmount().compareTo(BigDecimal.ZERO) <= 0
                        ? "。"
                        : "，其中冲抵预借款 " + report.getOffsetAmount() + " 元。")
                        + "这笔报销已结案，对应的出差申请同步归档。");

        PaymentVO vo = new PaymentVO();
        vo.setId(record.getId());
        vo.setReportNo(reportNo);
        vo.setPayAmount(request.getPayAmount());
        vo.setLoanId(loan.getId());
        vo.setPayAt(record.getPayAt() == null ? null : record.getPayAt().format(DATE_TIME));
        vo.setOperator(name(UserContext.currentId()));
        return vo;
    }

    // ---------- 内部 ----------

    private String name(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : user.getRealName();
    }

    /**
     * 报销单的报销人 id。
     *
     * <p>报销单上没有 user_id 列，人挂在对应的申请单上，所以要多绕一次 trip_apply。
     * 取不到就返回 null，{@code MessageService.send} 会静默跳过——
     * 找不到人的时候不值得为一条通知报错。
     */
    private Long applicantId(String applyNo) {
        if (!StringUtils.hasText(applyNo)) {
            return null;
        }
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, applyNo));
        return apply == null ? null : apply.getUserId();
    }

    private String emptyToDash(String text) {
        return StringUtils.hasText(text) ? text.trim() : "（财务未填写）";
    }

    // ---------- 供分析模块用 ----------

    /**
     * 某个月的已付款记录，月报的「投入」数据就来自这里。
     */
    public List<PaymentRecord> paidRecords(int year, int month) {
        java.time.LocalDateTime from = java.time.LocalDateTime.of(year, month, 1, 0, 0);
        java.time.LocalDateTime to = from.plusMonths(1);
        return paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .ge(PaymentRecord::getPayAt, from)
                .lt(PaymentRecord::getPayAt, to));
    }

    public List<PaymentRecord> allPaidRecords() {
        return paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .orderByDesc(PaymentRecord::getPayAt));
    }

    // ---------- 内部 ----------

    private boolean inRange(LocalDate date, List<TripItinerary> itineraries) {
        for (TripItinerary item : itineraries) {
            if (item.getStartDate() == null || item.getEndDate() == null) {
                continue;
            }
            if (!date.isBefore(item.getStartDate()) && !date.isAfter(item.getEndDate())) {
                return true;
            }
        }
        return false;
    }

    private FinanceExceptionVO issue(String code, String desc, String refType, String refId,
                                     String srcValue, String level) {
        FinanceExceptionVO vo = new FinanceExceptionVO();
        vo.setIssueCode(code);
        vo.setDesc(desc);
        vo.setRefType(refType);
        vo.setRefId(refId);
        vo.setSrcValue(srcValue);
        vo.setLevel(level);
        return vo;
    }
}
