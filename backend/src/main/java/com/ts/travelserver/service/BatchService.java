package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.dto.ApprovalRequest;
import com.ts.travelserver.dto.BatchApprovalRequest;
import com.ts.travelserver.dto.BatchPayRequest;
import com.ts.travelserver.dto.BatchReviewRequest;
import com.ts.travelserver.dto.FinanceReviewRequest;
import com.ts.travelserver.dto.PaymentRequest;
import com.ts.travelserver.entity.ExpenseReport;
import com.ts.travelserver.entity.LoanRecord;
import com.ts.travelserver.mapper.ExpenseReportMapper;
import com.ts.travelserver.vo.BatchResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 批量操作：审批、复核、付款三个批量入口。
 *
 * <h3>一单失败不拖累其他单</h3>
 * 这里刻意不加 {@code @Transactional}。批一百张单，第九十九张状态不对，
 * 把前九十八张一起回滚掉——那九十八张白批了，还得重来一遍，比失败几张难收拾得多。
 * 所以逐单调用各自的 Service（它们自己带事务），失败的收进 {@code failed} 列表，
 * 前端照着列表一条条说清楚哪张没成、为什么。
 *
 * <h3>不做「全部成功才提交」</h3>
 * 有人会觉得批量就该原子。但审批 / 付款这种动作，成一张是一张的既成事实——
 * 批完的那张已经通知了申请人，回滚只是让系统内部的账对上了，人那边的账还是乱的。
 *
 * <h3>上限 50 张</h3>
 * 再多的批量就不是「我看过这几张」了，而是无脑全选。50 张足够覆盖一屏待办，
 * 又不至于让一个请求挂在那里跑几分钟。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchService {

    private final ApprovalService approvalService;
    private final FinanceService financeService;
    private final LoanService loanService;
    private final ExpenseReportMapper expenseReportMapper;

    // ---------- 批量审批 ----------

    public BatchResultVO decision(BatchApprovalRequest request) {
        List<String> nos = clean(request.getApplyNos());
        BatchResultVO result = shell(nos);
        for (String applyNo : nos) {
            try {
                ApprovalRequest one = new ApprovalRequest();
                one.setResult(request.getResult());
                one.setOpinion(request.getOpinion());
                approvalService.decision(applyNo, one);
                result.setSuccessCnt(result.getSuccessCnt() + 1);
            } catch (BusinessException e) {
                fail(result, applyNo, e.getMessage());
            } catch (Exception e) {
                fail(result, applyNo, e);
            }
        }
        return done(result);
    }

    // ---------- 批量复核 ----------

    public BatchResultVO review(BatchReviewRequest request) {
        List<String> nos = clean(request.getReportNos());
        BatchResultVO result = shell(nos);
        for (String reportNo : nos) {
            try {
                FinanceReviewRequest one = new FinanceReviewRequest();
                one.setResult(request.getResult());
                one.setReason(request.getReason());
                financeService.review(reportNo, one);
                result.setSuccessCnt(result.getSuccessCnt() + 1);
            } catch (BusinessException e) {
                fail(result, reportNo, e.getMessage());
            } catch (Exception e) {
                fail(result, reportNo, e);
            }
        }
        return done(result);
    }

    // ---------- 批量付款 ----------

    /**
     * 照单付：每单付它的净额（总额减冲抵），预借按申请号自动找。
     *
     * <p>没有预借款的单直接进失败列表，不自动造一笔——
     * 预借是在复审通过那一刻按规则生成的，付钱的时候补一笔等于改了当时的额度口径。
     */
    public BatchResultVO pay(BatchPayRequest request) {
        List<String> nos = clean(request.getReportNos());
        BatchResultVO result = shell(nos);
        for (String reportNo : nos) {
            try {
                ExpenseReport report = expenseReportMapper.selectOne(
                        new LambdaQueryWrapper<ExpenseReport>()
                                .eq(ExpenseReport::getReportNo, reportNo));
                if (report == null) {
                    fail(result, reportNo, "报销单不存在");
                    continue;
                }
                LoanRecord loan = loanService.load(report.getApplyNo());
                if (loan == null) {
                    fail(result, reportNo, "这张单没有预借款，请单独打开处理");
                    continue;
                }
                PaymentRequest one = new PaymentRequest();
                one.setLoanId(loan.getId());
                one.setPayAmount(report.getNetAmount() == null ? BigDecimal.ZERO : report.getNetAmount());
                one.setPayRemark(request.getPayRemark());
                financeService.pay(reportNo, one);
                result.setSuccessCnt(result.getSuccessCnt() + 1);
            } catch (BusinessException e) {
                fail(result, reportNo, e.getMessage());
            } catch (Exception e) {
                fail(result, reportNo, e);
            }
        }
        return done(result);
    }

    // ---------- 内部 ----------

    private BatchResultVO shell(List<String> nos) {
        BatchResultVO result = new BatchResultVO();
        result.setTotal(nos.size());
        return result;
    }

    private BatchResultVO done(BatchResultVO result) {
        result.setFailCnt(result.getFailed().size());
        return result;
    }

    /** 非业务异常不说细节：堆栈里的话给人看只会让人更懵 */
    private void fail(BatchResultVO result, String bizNo, Exception e) {
        log.warn("批量操作失败，单号 {}：{}", bizNo, e.getMessage());
        fail(result, bizNo, "处理失败，请单独打开这张单重试");
    }

    private void fail(BatchResultVO result, String bizNo, String msg) {
        BatchResultVO.FailVO item = new BatchResultVO.FailVO();
        item.setBizNo(bizNo);
        item.setMsg(msg);
        result.getFailed().add(item);
    }

    /**
     * 去空白、去重、保序。
     *
     * <p>去重是必须的：前端全选又手点了一次，同一个单号出现两遍，
     * 第二遍会因为「不在审批中」失败，白占一条失败记录。
     */
    private List<String> clean(List<String> nos) {
        if (nos == null) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String no : nos) {
            if (no == null || no.isBlank()) {
                continue;
            }
            set.add(no.trim());
        }
        return new ArrayList<>(set);
    }
}
