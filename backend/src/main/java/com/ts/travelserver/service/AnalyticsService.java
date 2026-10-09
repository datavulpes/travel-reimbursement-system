package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.ai.MonthlyConclusionBuilder;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.entity.ApprovalRecord;
import com.ts.travelserver.entity.BizResult;
import com.ts.travelserver.entity.Dept;
import com.ts.travelserver.entity.EvalResult;
import com.ts.travelserver.entity.ExpenseReport;
import com.ts.travelserver.entity.Invoice;
import com.ts.travelserver.entity.Partner;
import com.ts.travelserver.entity.PaymentRecord;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.mapper.ApprovalRecordMapper;
import com.ts.travelserver.mapper.BizResultMapper;
import com.ts.travelserver.mapper.DeptMapper;
import com.ts.travelserver.mapper.EvalResultMapper;
import com.ts.travelserver.mapper.ExpenseReportMapper;
import com.ts.travelserver.mapper.InvoiceMapper;
import com.ts.travelserver.mapper.PartnerMapper;
import com.ts.travelserver.mapper.PaymentRecordMapper;
import com.ts.travelserver.mapper.SysUserMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.vo.ApplyDetailVO;
import com.ts.travelserver.vo.DrillVO;
import com.ts.travelserver.vo.InvoiceVO;
import com.ts.travelserver.vo.MonthlyReportVO;
import com.ts.travelserver.vo.PartnerRoiVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 费效比分析（模块 04，F-01 ~ F-04）。
 *
 * <p>指标口径（设计报告 6.6）：
 * 部门投入 = 该部门该月实付净额之和；部门产出 = 该部门该月业务结果折算产出之和；
 * 费效比 = 投入 ÷ 产出（越小越划算）；可疑差旅占比 = 评价档位为「关注」的单数 ÷ 总单数。
 *
 * <p>报表数据源本来是 v_dept_monthly 视图，这里改成用基础表在内存里汇总：
 * 视图的字段口径一旦与实体对不上，排查成本比多写几十行 Java 高得多，
 * 数据量在这个课程项目里也完全撑得住。
 *
 * <p>TODO-答辩可讲：数据量上到十万级就该换视图或物化汇总表了，这里是刻意的简化。
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TripApplyMapper tripApplyMapper;
    private final DeptMapper deptMapper;
    private final EvalResultMapper evalResultMapper;
    private final BizResultMapper bizResultMapper;
    private final ExpenseReportMapper expenseReportMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final PartnerMapper partnerMapper;
    private final InvoiceMapper invoiceMapper;
    private final ApprovalRecordMapper approvalRecordMapper;
    private final SysUserMapper sysUserMapper;
    private final InvoiceService invoiceService;
    private final MonthlyConclusionBuilder monthlyConclusionBuilder;

    // ---------- F-04 月报四个关键数字 ----------

    /**
     * 四个关键数字：总投入、总产出、费效比、可疑差旅占比，外加事前拦截金额。
     */
    public MonthlyReportVO summary(String month) {
        return monthly(month, null);
    }

    // ---------- F-01 部门费效比月报 ----------

    public MonthlyReportVO monthly(String month, Long deptId) {
        int[] ym = parseMonth(month);
        LocalDateTime from = LocalDateTime.of(ym[0], ym[1], 1, 0, 0);
        LocalDateTime to = from.plusMonths(1);

        List<TripApply> applies = tripApplyMapper.selectList(new LambdaQueryWrapper<TripApply>()
                .ge(TripApply::getCreatedAt, from)
                .lt(TripApply::getCreatedAt, to)
                .eq(deptId != null, TripApply::getDeptId, deptId));

        /*
         * 付款、产出、回填情况一次取齐，再按申请号分到每张单上。
         * 原来 paidOf(applyNos) 是「先查报销单、再逐单查付款记录」的 N+1 写法，
         * 部门维度还能顶住，一旦要按出差类型、按对象再拆一遍就变成三份 N+1。
         */
        List<String> allApplyNos = new ArrayList<>();
        for (TripApply apply : applies) {
            allApplyNos.add(apply.getApplyNo());
        }
        Map<String, BigDecimal> paidByApply = paidMap(allApplyNos);
        List<BizResult> results = resultsOf(allApplyNos);
        Map<String, BigDecimal> outputByApply = new HashMap<>();
        Set<String> filledApplyNos = new HashSet<>();
        for (BizResult result : results) {
            outputByApply.merge(result.getApplyNo(), nz(result.getOutputAmount()), BigDecimal::add);
            // 填了「无进展」也算回填：这里量的是数据完整度，不是产出好坏
            filledApplyNos.add(result.getApplyNo());
        }

        Map<Long, List<TripApply>> byDept = new LinkedHashMap<>();
        for (TripApply apply : applies) {
            byDept.computeIfAbsent(apply.getDeptId() == null ? 0L : apply.getDeptId(),
                    k -> new ArrayList<>()).add(apply);
        }

        List<MonthlyReportVO.DeptRowVO> rows = new ArrayList<>();
        BigDecimal totalPay = BigDecimal.ZERO;
        BigDecimal totalOutput = BigDecimal.ZERO;
        int attentionCnt = 0;
        BigDecimal blocked = BigDecimal.ZERO;
        int paidCnt = 0;
        int filledCnt = 0;

        for (Map.Entry<Long, List<TripApply>> entry : byDept.entrySet()) {
            List<TripApply> deptApplies = entry.getValue();
            List<String> applyNos = new ArrayList<>();
            for (TripApply apply : deptApplies) {
                applyNos.add(apply.getApplyNo());
                // 事前拦截金额：差标超限且最终没成行（被驳回）的预估合计
                if (TripApply.STANDARD_EXCEED.equals(apply.getStandardResult())
                        && TripApply.STATUS_REJECTED.equals(apply.getStatus())
                        && apply.getEstTotal() != null) {
                    blocked = blocked.add(apply.getEstTotal());
                }
            }

            MonthlyReportVO.DeptRowVO row = new MonthlyReportVO.DeptRowVO();
            row.setDeptId(entry.getKey());
            row.setDeptName(deptName(entry.getKey()));
            row.setTotalPay(sumOf(paidByApply, applyNos));
            row.setTotalOutput(sumOf(outputByApply, applyNos));
            row.setRatio(divide(row.getTotalPay(), row.getTotalOutput()));
            row.setApplyCnt(deptApplies.size());
            rows.add(row);

            totalPay = totalPay.add(row.getTotalPay());
            totalOutput = totalOutput.add(row.getTotalOutput());
            attentionCnt += attentionOf(applyNos);
        }

        // 回填率只在「已经付过钱」的单里算：没付款的单谈不上产出，混进来只会把分母灌水
        for (String applyNo : allApplyNos) {
            if (nz(paidByApply.get(applyNo)).compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            paidCnt++;
            if (filledApplyNos.contains(applyNo)) {
                filledCnt++;
            }
        }

        MonthlyReportVO vo = new MonthlyReportVO();
        vo.setMonth(month);
        vo.setRows(rows);
        vo.setTotalPay(totalPay);
        vo.setTotalOutput(totalOutput);
        vo.setRatio(divide(totalPay, totalOutput));
        vo.setApplyCnt(applies.size());
        vo.setAttentionCnt(attentionCnt);
        vo.setAttentionRatio(divideInt(attentionCnt, applies.size()));
        vo.setBlockedAmount(blocked);
        vo.setPaidCnt(paidCnt);
        vo.setFilledCnt(filledCnt);
        vo.setFillRate(divideInt(filledCnt, paidCnt));
        vo.setTypeRows(typeRowsOf(applies, paidByApply, outputByApply));
        vo.setOutputStruct(outputStructOf(results));
        vo.setConclusion(monthlyConclusionBuilder.build(rows));
        return vo;
    }

    // ---------- F-01 的三个新维度 ----------

    /**
     * 按出差类型汇总。类型为空的归到「未填类型」——
     * 单独留这一行是有意的：它占了多大比例，本身就是「表单填得认不认真」的一个信号。
     */
    private List<MonthlyReportVO.TypeRowVO> typeRowsOf(List<TripApply> applies,
                                                       Map<String, BigDecimal> paidByApply,
                                                       Map<String, BigDecimal> outputByApply) {
        Map<String, List<TripApply>> byType = new LinkedHashMap<>();
        for (TripApply apply : applies) {
            String key = apply.getTripType() == null || apply.getTripType().isBlank()
                    ? "未填类型" : apply.getTripType().trim();
            byType.computeIfAbsent(key, k -> new ArrayList<>()).add(apply);
        }

        List<MonthlyReportVO.TypeRowVO> rows = new ArrayList<>();
        for (Map.Entry<String, List<TripApply>> entry : byType.entrySet()) {
            List<String> applyNos = new ArrayList<>();
            for (TripApply apply : entry.getValue()) {
                applyNos.add(apply.getApplyNo());
            }
            MonthlyReportVO.TypeRowVO row = new MonthlyReportVO.TypeRowVO();
            row.setTripType("未填类型".equals(entry.getKey()) ? null : entry.getKey());
            row.setLabel(entry.getKey());
            row.setTotalPay(sumOf(paidByApply, applyNos));
            row.setTotalOutput(sumOf(outputByApply, applyNos));
            row.setRatio(divide(row.getTotalPay(), row.getTotalOutput()));
            row.setApplyCnt(applyNos.size());
            rows.add(row);
        }
        // 投入多的排前面：管理层先看的是「钱主要花在哪类出差上」
        rows.sort((a, b) -> nz(b.getTotalPay()).compareTo(nz(a.getTotalPay())));
        return rows;
    }

    /**
     * 产出构成：把本月回填的业务结果按类型拆开，附带无进展率。
     *
     * <p>无进展率与费效比是两回事，别混着看：费效比差可能是花多了（控成本），
     * 无进展率高是白跑了（别再派人）。两条路的处置动作完全不同。
     */
    private MonthlyReportVO.OutputStructVO outputStructOf(List<BizResult> results) {
        MonthlyReportVO.OutputStructVO vo = new MonthlyReportVO.OutputStructVO();
        if (results.isEmpty()) {
            vo.setTotalCnt(0);
            vo.setNoProgressRate(null);
            return vo;
        }

        Map<String, Integer> cntByType = new LinkedHashMap<>();
        Map<String, BigDecimal> amountByType = new LinkedHashMap<>();
        int noProgress = 0;
        for (BizResult result : results) {
            String type = result.getResultType() == null || result.getResultType().isBlank()
                    ? "未标注" : result.getResultType().trim();
            cntByType.merge(type, 1, Integer::sum);
            amountByType.merge(type, nz(result.getOutputAmount()), BigDecimal::add);
            if (!result.effective()) {
                noProgress++;
            }
        }

        List<MonthlyReportVO.OutputItemVO> items = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : cntByType.entrySet()) {
            MonthlyReportVO.OutputItemVO item = new MonthlyReportVO.OutputItemVO();
            item.setResultType(entry.getKey());
            item.setCnt(entry.getValue());
            item.setAmount(nz(amountByType.get(entry.getKey())));
            items.add(item);
        }
        items.sort((a, b) -> b.getCnt() - a.getCnt());

        vo.setTotalCnt(results.size());
        vo.setItems(items);
        vo.setNoProgressRate(divideInt(noProgress, results.size()));
        return vo;
    }

    // ---------- F-02 归因钻取 ----------

    /**
     * 部门 → 单据 → 票据 / 审批依据 / 业务结果，一次返回整条链（FR-0402）。
     */
    public DrillVO drill(Long deptId, String month) {
        Dept dept = deptMapper.selectById(deptId);
        if (dept == null) {
            throw BusinessException.paramError("部门不存在");
        }
        int[] ym = parseMonth(month);
        LocalDateTime from = LocalDateTime.of(ym[0], ym[1], 1, 0, 0);
        LocalDateTime to = from.plusMonths(1);

        List<TripApply> applies = tripApplyMapper.selectList(new LambdaQueryWrapper<TripApply>()
                .eq(TripApply::getDeptId, deptId)
                .ge(TripApply::getCreatedAt, from)
                .lt(TripApply::getCreatedAt, to)
                .orderByDesc(TripApply::getCreatedAt));

        List<DrillVO.ApplyRowVO> rows = new ArrayList<>();
        List<DrillVO.DrillRowVO> flatRows = new ArrayList<>();
        for (TripApply apply : applies) {
            BigDecimal applyPaid = paidOf(List.of(apply.getApplyNo()));
            BigDecimal applyOutput = outputOf(List.of(apply.getApplyNo()));

            // 扁平视图：一张申请单自己一行，它下面的每张报销单各一行
            flatRows.add(drillRow("APPLY", apply.getApplyNo(), apply.getReason(), applyPaid, applyOutput));
            List<ExpenseReport> reports = expenseReportMapper.selectList(
                    new LambdaQueryWrapper<ExpenseReport>()
                            .eq(ExpenseReport::getApplyNo, apply.getApplyNo())
                            .orderByAsc(ExpenseReport::getId));
            for (ExpenseReport report : reports) {
                flatRows.add(drillRow("REPORT", report.getReportNo(),
                        apply.getReason() + "（" + name(apply.getUserId()) + "）",
                        report.getTotalAmount(), applyOutput));
            }

            DrillVO.ApplyRowVO row = new DrillVO.ApplyRowVO();
            row.setApplyNo(apply.getApplyNo());
            row.setReason(apply.getReason());
            row.setApplicant(name(apply.getUserId()));
            row.setEstTotal(apply.getEstTotal());
            row.setStatus(apply.getStatus());
            row.setPayAmount(applyPaid);
            row.setOutputAmount(applyOutput);

            EvalResult eval = evalResultMapper.selectOne(
                    new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getApplyNo, apply.getApplyNo()));
            row.setEvalLevel(eval == null ? null : eval.getLevel());

            List<Invoice> invoices = invoiceMapper.selectList(
                    new LambdaQueryWrapper<Invoice>().eq(Invoice::getApplyNo, apply.getApplyNo()));
            List<InvoiceVO> invoiceVOs = new ArrayList<>();
            for (Invoice invoice : invoices) {
                invoiceVOs.add(InvoiceService.toVO(invoice,
                        invoiceService.signedUrl(invoice.getImgPath()),
                        invoiceService.thumbUrl(invoice.getImgPath())));
            }
            row.setInvoices(invoiceVOs);

            List<ApprovalRecord> records = approvalRecordMapper.selectList(
                    new LambdaQueryWrapper<ApprovalRecord>()
                            .eq(ApprovalRecord::getApplyNo, apply.getApplyNo())
                            .orderByAsc(ApprovalRecord::getId));
            List<ApplyDetailVO.FlowRecordVO> recordVOs = new ArrayList<>();
            for (ApprovalRecord record : records) {
                ApplyDetailVO.FlowRecordVO recordVO = new ApplyDetailVO.FlowRecordVO();
                recordVO.setId(record.getId());
                recordVO.setApplyNo(record.getApplyNo());
                recordVO.setApprover(name(record.getApproverId()));
                recordVO.setStep(record.getStep());
                recordVO.setResult(record.getResult());
                recordVO.setOpinion(record.getOpinion());
                recordVO.setSnapshot(record.getSnapshot());
                recordVO.setApprovedAt(record.getApprovedAt() == null
                        ? null : record.getApprovedAt().format(DATE_TIME));
                recordVOs.add(recordVO);
            }
            row.setRecords(recordVOs);

            List<BizResult> results = bizResultMapper.selectList(
                    new LambdaQueryWrapper<BizResult>().eq(BizResult::getApplyNo, apply.getApplyNo()));
            List<DrillVO.BizResultRowVO> resultVOs = new ArrayList<>();
            for (BizResult result : results) {
                DrillVO.BizResultRowVO resultVO = new DrillVO.BizResultRowVO();
                resultVO.setResultType(result.getResultType());
                resultVO.setResultDesc(result.getResultDesc());
                resultVO.setOutputAmount(result.getOutputAmount());
                resultVO.setFilledAt(result.getFilledAt() == null ? null : result.getFilledAt().format(DATE_TIME));
                resultVOs.add(resultVO);
            }
            row.setResults(resultVOs);
            rows.add(row);
        }

        DrillVO vo = new DrillVO();
        vo.setDeptId(deptId);
        vo.setDeptName(dept.getDeptName());
        vo.setMonth(month);
        vo.setApplies(rows);
        vo.setRows(flatRows);
        return vo;
    }

    /**
     * 拼一行钻取表格数据。产出为 0 时费效比记 0，前端会把 0 渲染成红色「无产出」。
     *
     * <p>这里统一走 divide()（4 位小数）而不是自己保留 2 位：投入/产出口径下比值可能很小
     * （花 700 赚 120000 → 0.0058），保留 2 位会被抹成 0.00，前端反而当成「无产出」标红。
     */
    private DrillVO.DrillRowVO drillRow(String level, String bizNo, String name,
                                        BigDecimal payAmount, BigDecimal outputAmount) {
        DrillVO.DrillRowVO row = new DrillVO.DrillRowVO();
        row.setLevel(level);
        row.setBizNo(bizNo);
        row.setName(name);
        row.setPayAmount(payAmount == null ? BigDecimal.ZERO : payAmount);
        row.setOutputAmount(outputAmount == null ? BigDecimal.ZERO : outputAmount);
        row.setRatio(divide(row.getPayAmount(), row.getOutputAmount()));
        return row;
    }

    // ---------- F-03 对象投入产出对照 ----------

    /**
     * 对象投入产出对照。传月份就按该月的付款与回填算，不传月份直接取 partner 表上的累计值。
     */
    public PartnerRoiVO partner(String month) {
        List<Partner> partners = partnerMapper.selectList(
                new LambdaQueryWrapper<Partner>().orderByDesc(Partner::getTotalOutput));

        List<PartnerRoiVO.PartnerRowVO> rows = new ArrayList<>();
        BigDecimal totalInvest = BigDecimal.ZERO;
        BigDecimal totalOutput = BigDecimal.ZERO;

        for (Partner partner : partners) {
            PartnerRoiVO.PartnerRowVO row = new PartnerRoiVO.PartnerRowVO();
            row.setPartnerId(partner.getId());
            row.setPartnerName(partner.getPartnerName());
            row.setPartnerType(partner.getPartnerType());

            if (month == null || month.isBlank()) {
                // 累计值直接用冗余汇总字段，管理层每次全表汇总太慢（设计报告 5.6）
                row.setTotalInvest(partner.getTotalInvest());
                row.setTotalOutput(partner.getTotalOutput());
                row.setTripCnt(tripCntOf(partner.getId(), null, null));
            } else {
                int[] ym = parseMonth(month);
                LocalDateTime from = LocalDateTime.of(ym[0], ym[1], 1, 0, 0);
                LocalDateTime to = from.plusMonths(1);
                List<String> applyNos = applyNosOf(partner.getId(), from, to);
                row.setTotalInvest(paidOf(applyNos));
                row.setTotalOutput(outputOf(applyNos));
                row.setTripCnt(applyNos.size());
            }
            row.setRatio(divide(row.getTotalInvest(), row.getTotalOutput()));
            rows.add(row);

            if (row.getTotalInvest() != null) {
                totalInvest = totalInvest.add(row.getTotalInvest());
            }
            if (row.getTotalOutput() != null) {
                totalOutput = totalOutput.add(row.getTotalOutput());
            }
        }

        // 按费效比从高到低排，值不值得继续跑一眼看得出来
        rows.sort((a, b) -> (b.getRatio() == null ? BigDecimal.ZERO : b.getRatio())
                .compareTo(a.getRatio() == null ? BigDecimal.ZERO : a.getRatio()));

        PartnerRoiVO vo = new PartnerRoiVO();
        vo.setMonth(month);
        vo.setRows(rows);
        vo.setTotalInvest(totalInvest);
        vo.setTotalOutput(totalOutput);
        vo.setRatio(divide(totalInvest, totalOutput));
        return vo;
    }

    // ---------- 内部 ----------

    private int[] parseMonth(String month) {
        if (month == null || month.isBlank()) {
            LocalDateTime now = LocalDateTime.now();
            return new int[]{now.getYear(), now.getMonthValue()};
        }
        String[] parts = month.trim().split("-");
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (RuntimeException e) {
            throw BusinessException.paramError("月份格式不正确，应为 yyyy-MM");
        }
    }

    /**
     * 这批申请单的实付金额：申请号 → 报销单 → 付款记录。
     */
    private BigDecimal paidOf(List<String> applyNos) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : paidMap(applyNos).values()) {
            total = total.add(value);
        }
        return total;
    }

    /**
     * 每张申请单各付了多少钱。两次查询搞定（报销单一次、付款记录一次），
     * 不在循环里逐单查库。
     */
    private Map<String, BigDecimal> paidMap(List<String> applyNos) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (applyNos == null || applyNos.isEmpty()) {
            return out;
        }
        List<ExpenseReport> reports = expenseReportMapper.selectList(
                new LambdaQueryWrapper<ExpenseReport>().in(ExpenseReport::getApplyNo, applyNos));
        if (reports.isEmpty()) {
            return out;
        }
        Map<String, BigDecimal> payByReport = new HashMap<>();
        List<String> reportNos = new ArrayList<>();
        for (ExpenseReport report : reports) {
            reportNos.add(report.getReportNo());
        }
        List<PaymentRecord> payments = paymentRecordMapper.selectList(
                new LambdaQueryWrapper<PaymentRecord>().in(PaymentRecord::getReportNo, reportNos));
        for (PaymentRecord payment : payments) {
            payByReport.merge(payment.getReportNo(), nz(payment.getPayAmount()), BigDecimal::add);
        }
        for (ExpenseReport report : reports) {
            BigDecimal paid = payByReport.get(report.getReportNo());
            if (paid != null) {
                out.merge(report.getApplyNo(), paid, BigDecimal::add);
            }
        }
        return out;
    }

    /** 这批申请单回填的业务结果 */
    private List<BizResult> resultsOf(List<String> applyNos) {
        if (applyNos == null || applyNos.isEmpty()) {
            return List.of();
        }
        return bizResultMapper.selectList(
                new LambdaQueryWrapper<BizResult>().in(BizResult::getApplyNo, applyNos));
    }

    private BigDecimal outputOf(List<String> applyNos) {
        BigDecimal total = BigDecimal.ZERO;
        for (BizResult result : resultsOf(applyNos)) {
            total = total.add(nz(result.getOutputAmount()));
        }
        return total;
    }

    /** 按申请号把某项金额加总 */
    private BigDecimal sumOf(Map<String, BigDecimal> byApply, List<String> applyNos) {
        BigDecimal total = BigDecimal.ZERO;
        for (String applyNo : applyNos) {
            total = total.add(nz(byApply.get(applyNo)));
        }
        return total;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 两个计数相除。分母为 0 时返回 null 而不是 0——
     * 「0% 回填」和「没有可统计的单据」是两回事，页面上前者显示 0%、后者显示「—」。
     */
    private BigDecimal divideInt(int numerator, int denominator) {
        if (denominator <= 0) {
            return null;
        }
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), 4, RoundingMode.HALF_UP);
    }

    private int attentionOf(List<String> applyNos) {
        if (applyNos == null || applyNos.isEmpty()) {
            return 0;
        }
        Long cnt = evalResultMapper.selectCount(new LambdaQueryWrapper<EvalResult>()
                .in(EvalResult::getApplyNo, applyNos)
                .eq(EvalResult::getLevel, EvalResult.LEVEL_ATTENTION));
        return cnt == null ? 0 : cnt.intValue();
    }

    private List<String> applyNosOf(Long partnerId, LocalDateTime from, LocalDateTime to) {
        List<TripApply> applies;
        if (from == null || to == null) {
            applies = tripApplyMapper.selectList(
                    new LambdaQueryWrapper<TripApply>().eq(TripApply::getPartnerId, partnerId));
        } else {
            applies = tripApplyMapper.selectList(new LambdaQueryWrapper<TripApply>()
                    .eq(TripApply::getPartnerId, partnerId)
                    .ge(TripApply::getCreatedAt, from)
                    .lt(TripApply::getCreatedAt, to));
        }
        List<String> applyNos = new ArrayList<>();
        for (TripApply apply : applies) {
            applyNos.add(apply.getApplyNo());
        }
        return applyNos;
    }

    private int tripCntOf(Long partnerId, LocalDateTime from, LocalDateTime to) {
        return applyNosOf(partnerId, from, to).size();
    }

    private String deptName(Long deptId) {
        if (deptId == null) {
            return "未分配部门";
        }
        Dept dept = deptMapper.selectById(deptId);
        return dept == null ? "未知部门" : dept.getDeptName();
    }

    private String name(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : user.getRealName();
    }

    /**
     * 费效比专用除法：全系统统一口径 **费效比 = 投入 ÷ 产出**（越小越划算）。
     *
     * <p>与前端「0.40 警戒线」、评价因子 F2「成本占对象产出比（5% 满分 / 50% 零分）」同一口径。
     * 除数为 0（没产出）时返回 0，前端把 0 渲染成红色「无产出」。保留 4 位小数，
     * 免得 0.005 这种划算的比值被抹成 0.00 反而标红。
     */
    private BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    /**
     * 当前登录人所在部门，管理层不传部门时用它兜底。
     */
    public Long currentDeptId() {
        SysUser current = UserContext.get();
        return current == null ? null : current.getDeptId();
    }
}
