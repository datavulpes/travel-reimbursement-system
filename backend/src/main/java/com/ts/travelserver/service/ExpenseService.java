package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ts.travelserver.common.ApplyNoGenerator;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.dto.ApplyQuery;
import com.ts.travelserver.dto.ExpenseSubmitRequest;
import com.ts.travelserver.entity.ExpenseReport;
import com.ts.travelserver.entity.FieldDraft;
import com.ts.travelserver.entity.Invoice;
import com.ts.travelserver.entity.LoanRecord;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.mapper.ExpenseReportMapper;
import com.ts.travelserver.mapper.InvoiceMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.mapper.TripItineraryMapper;
import com.ts.travelserver.service.support.AccessGuard;
import com.ts.travelserver.vo.ExpenseDetailVO;
import com.ts.travelserver.vo.ExpenseListItemVO;
import com.ts.travelserver.vo.InvoiceVO;
import com.ts.travelserver.vo.PrecheckIssueVO;
import com.ts.travelserver.vo.PrecheckVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 报销单（模块 02，D-02 ~ D-04）。
 *
 * <p>提交时后端按 applyNo 归集票据生成报销单（设计报告 6.4.5）：
 * total = 票据金额之和，offset = min(预借余额, total)，net = total − offset。
 * 三个金额刻意全部冗余保留，必须同时可见可审，复核后不可改（BR-10）。
 *
 * <p>预检四项：差标复核（按实际票据金额）、行程冲突、必填完整性、金额勾稽。
 * 结论 PASS / WARN / FORCE；FORCE 且没填强制说明 → 2004（BR-05）。
 */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExpenseReportMapper expenseReportMapper;
    private final InvoiceMapper invoiceMapper;
    private final TripApplyMapper tripApplyMapper;
    private final TripItineraryMapper tripItineraryMapper;
    private final ApplyNoGenerator applyNoGenerator;
    private final StandardService standardService;
    private final LoanService loanService;
    private final InvoiceService invoiceService;
    private final ApplyService applyService;
    private final com.ts.travelserver.mapper.DeptMapper deptMapper;
    private final com.ts.travelserver.mapper.SysUserMapper sysUserMapper;
    private final FieldDraftService fieldDraftService;
    private final AccessGuard accessGuard;

    // ---------- D-02 提交报销 ----------

    @Transactional(rollbackFor = Exception.class)
    public ExpenseDetailVO submit(ExpenseSubmitRequest request) {
        TripApply apply = applyService.load(request.getApplyNo());
        if (!apply.getUserId().equals(UserContext.currentId())) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "只能为自己的申请提交报销");
        }
        Long exists = expenseReportMapper.selectCount(
                new LambdaQueryWrapper<ExpenseReport>().eq(ExpenseReport::getApplyNo, request.getApplyNo()));
        if (exists != null && exists > 0) {
            throw BusinessException.statusForbidden("该申请已提交过报销单，不能重复提交");
        }

        // 预检四项，唯一实现见 computeChecks；提交和「预检一次」共用同一套口径，
        // 不会出现「预检说能过、提交却被拦」的两头打架
        PrecheckVO pv = computeChecks(request.getApplyNo());

        if (ExpenseReport.PRECHECK_FORCE.equals(pv.getPrecheckResult())
                && !StringUtils.hasText(request.getForceReason())) {
            throw new BusinessException(ResultCode.PRECHECK_FAILED, describe(pv.getChecks()));
        }

        ExpenseReport report = new ExpenseReport();
        report.setReportNo(applyNoGenerator.nextReportNo());
        report.setApplyNo(request.getApplyNo());
        report.setTotalAmount(pv.getTotalAmount());
        report.setOffsetAmount(pv.getOffsetAmount());
        report.setNetAmount(pv.getNetAmount());
        report.setPrecheckResult(pv.getPrecheckResult());
        report.setStatus(ExpenseReport.STATUS_PENDING);
        // 强制说明落库：这是 BR-05 的留痕本体，财务复核时要能看到员工当时怎么说的
        report.setForceReason(request.getForceReason());
        expenseReportMapper.insert(report);

        // 说明已经进了报销单，草稿的使命结束
        fieldDraftService.clear(FieldDraft.BIZ_EXPENSE_SUBMIT, request.getApplyNo(), FieldDraft.FIELD_FORCE_REASON);

        return detail(report.getReportNo(), report, pv.getChecks());
    }

    /**
     * D-02 前置预检：提交前让员工先看结论。只读不落库、不生成报销单，
     * 金额三件套一并返回，页面上的冲抵/净额以后端口径为准。
     */
    public PrecheckVO preview(String applyNo) {
        TripApply apply = applyService.load(applyNo);
        if (!apply.getUserId().equals(UserContext.currentId())) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "只能预检自己的申请");
        }
        return computeChecks(applyNo);
    }

    /**
     * 预检四项的唯一实现。之前只有 submit 里一份，前端「预检一次」是本地装样子
     * （只看缺票，看不见差标超标），FORCE 项永远不亮，强制说明输入框出不来，
     * 提交必然被 2004 拦下——抽出来共用，从根上消灭口径分叉。
     */
    private PrecheckVO computeChecks(String applyNo) {
        List<Invoice> invoices = invoiceMapper.selectList(
                new LambdaQueryWrapper<Invoice>().eq(Invoice::getApplyNo, applyNo));
        if (invoices.isEmpty()) {
            throw BusinessException.paramError("还没有挂接任何票据，先去上传票据");
        }

        BigDecimal total = BigDecimal.ZERO;
        for (Invoice invoice : invoices) {
            total = total.add(invoice.getAmount() == null ? BigDecimal.ZERO : invoice.getAmount());
        }

        List<PrecheckIssueVO> checks = new ArrayList<>();
        String precheck = ExpenseReport.PRECHECK_PASS;

        // ① 差标复核：这回按票据实际金额，不按预估值
        List<TripItinerary> itineraries = tripItineraryMapper.selectList(
                new LambdaQueryWrapper<TripItinerary>().eq(TripItinerary::getApplyNo, applyNo));
        BigDecimal cap = standardService.capTotal(itineraries);
        String standardResult = standardService.judge(total, cap);
        String standardLevel = com.ts.travelserver.entity.TripApply.STANDARD_EXCEED.equals(standardResult)
                ? "FORCE"
                : (com.ts.travelserver.entity.TripApply.STANDARD_HIGH.equals(standardResult) ? "WARN" : "PASS");
        checks.add(issue("STANDARD", "差标复核：" + StandardService.describe(standardResult)
                + "（票据合计 " + total + " 元，上限 " + cap + " 元）", standardLevel));
        if (ExpenseReport.PRECHECK_PASS.equals(precheck)
                && com.ts.travelserver.entity.TripApply.STANDARD_HIGH.equals(standardResult)) {
            precheck = ExpenseReport.PRECHECK_WARN;
        }
        if (com.ts.travelserver.entity.TripApply.STANDARD_EXCEED.equals(standardResult)) {
            precheck = ExpenseReport.PRECHECK_FORCE;
        }

        // ② 行程冲突：票据日期要落在某一段行程里
        long dateMismatch = invoices.stream()
                .filter(invoice -> invoice.getInvoiceDate() == null || !inRange(invoice.getInvoiceDate(), itineraries))
                .count();
        if (dateMismatch > 0) {
            checks.add(issue("DATE", "行程对照：有 " + dateMismatch + " 张票据的开票日期不在任何行程段内", "WARN"));
            if (ExpenseReport.PRECHECK_PASS.equals(precheck)) {
                precheck = ExpenseReport.PRECHECK_WARN;
            }
        } else {
            checks.add(issue("DATE", "行程对照：票据日期均落在行程段内", "PASS"));
        }

        // ③ 必填完整性：缺票
        List<com.ts.travelserver.vo.MissingTicketVO> missing = missingTickets(itineraries, invoices);
        if (!missing.isEmpty()) {
            checks.add(issue("INVOICE", "票据完整性：还有 " + missing.size() + " 处疑似缺票", "FORCE"));
            precheck = ExpenseReport.PRECHECK_FORCE;
        } else {
            checks.add(issue("INVOICE", "票据完整性：各段票据已补齐", "PASS"));
        }

        // ④ 金额勾稽：冲抵不能超过预借余额
        LoanRecord loan = loanService.load(applyNo);
        BigDecimal remain = loan == null ? BigDecimal.ZERO : loan.remainAmount();
        BigDecimal offset = total.min(remain).max(BigDecimal.ZERO);
        BigDecimal net = total.subtract(offset);
        checks.add(issue("AMOUNT", "金额勾稽：票据合计 " + total + " 元，冲抵预借 "
                + offset + " 元，净额 " + net + " 元", "PASS"));

        PrecheckVO vo = new PrecheckVO();
        vo.setChecks(checks);
        vo.setPrecheckResult(precheck);
        vo.setTotalAmount(total);
        vo.setOffsetAmount(offset);
        vo.setNetAmount(net);
        vo.setNeedForceReason(ExpenseReport.PRECHECK_FORCE.equals(precheck));
        return vo;
    }

    // ---------- D-03 我的报销 ----------

    public PageResult<ExpenseListItemVO> page(ApplyQuery query) {
        Long userId = UserContext.currentId();
        Page<ExpenseReport> page = new Page<>(query.safePageNum(), query.safePageSize());
        LambdaQueryWrapper<ExpenseReport> wrapper = new LambdaQueryWrapper<ExpenseReport>()
                .eq(StringUtils.hasText(query.getStatus()), ExpenseReport::getStatus, query.getStatus())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(ExpenseReport::getReportNo, query.getKeyword())
                        .or().like(ExpenseReport::getApplyNo, query.getKeyword()))
                .orderByDesc(ExpenseReport::getCreatedAt);

        // 报销单上没有 user_id 列，按申请号反查申请人（设计报告 8.11 的取舍：冗余只保留金额三个）
        if (!SysUser.ROLE_FINANCE.equals(UserContext.currentRole())) {
            List<String> myApplyNos = myApplyNos(userId);
            if (myApplyNos.isEmpty()) {
                return PageResult.empty(query.safePageNum(), query.safePageSize());
            }
            wrapper.in(ExpenseReport::getApplyNo, myApplyNos);
        }
        expenseReportMapper.selectPage(page, wrapper);
        return PageResult.of(page, item -> toListItem(item, null));
    }

    // ---------- D-04 报销单详情 ----------

    public ExpenseDetailVO detail(String reportNo) {
        ExpenseReport report = load(reportNo);
        assertVisible(report);
        return detail(reportNo, report, checksOf(report));
    }

    public ExpenseReport load(String reportNo) {
        ExpenseReport report = expenseReportMapper.selectOne(
                new LambdaQueryWrapper<ExpenseReport>().eq(ExpenseReport::getReportNo, reportNo));
        if (report == null) {
            throw BusinessException.paramError("报销单不存在：" + reportNo);
        }
        return report;
    }

    /**
     * 供财务模块按申请号取报销单。
     */
    public ExpenseReport loadByApply(String applyNo) {
        return expenseReportMapper.selectOne(
                new LambdaQueryWrapper<ExpenseReport>().eq(ExpenseReport::getApplyNo, applyNo));
    }

    /**
     * 员工本人、审过这一单的审批人、财务、管理层可见。
     *
     * <p>报销单挂在申请单下，可见性跟着申请单走。<b>判据与申请单保持一致</b>：
     * 审批人在复审详情里也要看报销明细，把他挡在门外的话页面上不报错、只是没数据。
     */
    public void assertVisible(ExpenseReport report) {
        SysUser current = UserContext.get();
        if (current == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        if (accessGuard.broadVisible(current.getRole())) {
            return;
        }
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, report.getApplyNo()));
        if (apply != null && apply.getUserId() != null && apply.getUserId().equals(current.getId())) {
            return;
        }
        if (apply != null && apply.getDeptId() != null && apply.getDeptId().equals(current.getDeptId())
                && SysUser.ROLE_APPROVER.equals(current.getRole())) {
            return;
        }
        if (report.getApplyNo() != null && accessGuard.approvedByMe(report.getApplyNo(), current.getId())) {
            return;
        }
        throw new BusinessException(ResultCode.NO_PERMISSION, "只能查看自己的报销单");
    }

    public ExpenseListItemVO toListItem(ExpenseReport report, Integer issueCount) {
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, report.getApplyNo()));
        ExpenseListItemVO vo = new ExpenseListItemVO();
        vo.setReportNo(report.getReportNo());
        vo.setApplyNo(report.getApplyNo());
        vo.setReason(apply == null ? null : apply.getReason());
        vo.setApplicant(name(apply == null ? null : apply.getUserId()));
        vo.setDeptName(deptName(apply == null ? null : apply.getDeptId()));
        vo.setTotalAmount(report.getTotalAmount());
        vo.setOffsetAmount(report.getOffsetAmount());
        vo.setNetAmount(report.getNetAmount());
        vo.setPrecheckResult(report.getPrecheckResult());
        vo.setStatus(report.getStatus());
        vo.setCreatedAt(report.getCreatedAt() == null ? null : report.getCreatedAt().format(DATE_TIME));
        vo.setIssueCount(issueCount);
        Long cnt = invoiceMapper.selectCount(
                new LambdaQueryWrapper<Invoice>().eq(Invoice::getApplyNo, report.getApplyNo()));
        vo.setInvoiceCnt(cnt == null ? 0 : cnt.intValue());
        return vo;
    }

    // ---------- 内部 ----------

    private ExpenseDetailVO detail(String reportNo, ExpenseReport report, List<PrecheckIssueVO> checks) {
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, report.getApplyNo()));

        List<Invoice> invoices = invoiceMapper.selectList(
                new LambdaQueryWrapper<Invoice>().eq(Invoice::getApplyNo, report.getApplyNo()));
        List<InvoiceVO> invoiceVOs = new ArrayList<>();
        for (Invoice invoice : invoices) {
            invoiceVOs.add(InvoiceService.toVO(invoice,
                    invoiceService.signedUrl(invoice.getImgPath()),
                    invoiceService.thumbUrl(invoice.getImgPath())));
        }

        LoanRecord loan = loanService.load(report.getApplyNo());

        ExpenseDetailVO vo = new ExpenseDetailVO();
        vo.setReportNo(report.getReportNo());
        vo.setApplyNo(report.getApplyNo());
        vo.setReason(apply == null ? null : apply.getReason());
        vo.setApplicant(name(apply == null ? null : apply.getUserId()));
        vo.setDeptName(deptName(apply == null ? null : apply.getDeptId()));
        vo.setTotalAmount(report.getTotalAmount());
        vo.setOffsetAmount(report.getOffsetAmount());
        vo.setNetAmount(report.getNetAmount());
        vo.setPrecheckResult(report.getPrecheckResult());
        vo.setStatus(report.getStatus());
        vo.setForceReason(report.getForceReason());
        vo.setReviewReason(report.getReviewReason());
        vo.setPrecheckIssues(checks);
        vo.setInvoices(invoiceVOs);
        vo.setLoan(loan == null ? null : loanService.toVO(loan));
        vo.setCreatedAt(report.getCreatedAt() == null ? null : report.getCreatedAt().format(DATE_TIME));
        return vo;
    }

    /**
     * 复核后重新算一遍预检结论，给财务看「当时为什么是这个结论」。
     */
    private List<PrecheckIssueVO> checksOf(ExpenseReport report) {
        List<PrecheckIssueVO> checks = new ArrayList<>();
        List<TripItinerary> itineraries = tripItineraryMapper.selectList(
                new LambdaQueryWrapper<TripItinerary>().eq(TripItinerary::getApplyNo, report.getApplyNo()));
        BigDecimal cap = standardService.capTotal(itineraries);
        String standardResult = standardService.judge(report.getTotalAmount(), cap);
        String standardLevel = com.ts.travelserver.entity.TripApply.STANDARD_EXCEED.equals(standardResult)
                ? "FORCE"
                : (com.ts.travelserver.entity.TripApply.STANDARD_HIGH.equals(standardResult) ? "WARN" : "PASS");
        checks.add(issue("STANDARD", "差标复核：" + StandardService.describe(standardResult)
                + "（票据合计 " + report.getTotalAmount() + " 元，上限 " + cap + " 元）", standardLevel));
        checks.add(issue("AMOUNT", "金额勾稽：票据合计 " + report.getTotalAmount() + " 元，冲抵预借 "
                + report.getOffsetAmount() + " 元，净额 " + report.getNetAmount() + " 元", "PASS"));
        return checks;
    }

    /** 拼一条预检结论，省掉每处都 new 一遍 */
    private PrecheckIssueVO issue(String type, String desc, String level) {
        PrecheckIssueVO vo = new PrecheckIssueVO();
        vo.setType(type);
        vo.setDesc(desc);
        vo.setLevel(level);
        return vo;
    }

    /** 预检结论拼成一句话，用于 2004 的提示文案 */
    private String describe(List<PrecheckIssueVO> checks) {
        if (checks == null || checks.isEmpty()) {
            return "预检未通过";
        }
        StringBuilder sb = new StringBuilder();
        for (PrecheckIssueVO item : checks) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(item.getDesc());
        }
        return sb.toString();
    }

    private List<String> myApplyNos(Long userId) {
        List<TripApply> applies = tripApplyMapper.selectList(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getUserId, userId));
        List<String> applyNos = new ArrayList<>();
        for (TripApply apply : applies) {
            applyNos.add(apply.getApplyNo());
        }
        return applyNos;
    }

    private List<com.ts.travelserver.vo.MissingTicketVO> missingTickets(List<TripItinerary> itineraries,
                                                                       List<Invoice> invoices) {
        List<com.ts.travelserver.vo.MissingTicketVO> result = new ArrayList<>();
        for (TripItinerary item : itineraries) {
            long cnt = invoices.stream().filter(i -> item.getId().equals(i.getItineraryId())).count();
            long days = (item.getStartDate() == null || item.getEndDate() == null)
                    ? 0
                    : java.time.temporal.ChronoUnit.DAYS.between(item.getStartDate(), item.getEndDate());
            if (cnt == 0 || (days >= 1 && cnt < 2)) {
                com.ts.travelserver.vo.MissingTicketVO vo = new com.ts.travelserver.vo.MissingTicketVO();
                vo.setItineraryId(item.getId());
                vo.setSeq(item.getSeq());
                vo.setRoute((item.getFromCity() == null ? "" : item.getFromCity()) + " → "
                        + (item.getToCity() == null ? "" : item.getToCity()));
                vo.setTicketType(cnt == 0 ? (days >= 1 ? "住宿发票 / 交通票据" : "交通票据") : "住宿发票");
                vo.setHint("第 " + item.getSeq() + " 段票据未补齐");
                result.add(vo);
            }
        }
        return result;
    }

    private boolean inRange(java.time.LocalDate date, List<TripItinerary> itineraries) {
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

    private String name(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : user.getRealName();
    }

    private String deptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        com.ts.travelserver.entity.Dept dept = deptMapper.selectById(deptId);
        return dept == null ? null : dept.getDeptName();
    }
}
