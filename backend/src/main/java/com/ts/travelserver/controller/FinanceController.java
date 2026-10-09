package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.ApplyQuery;
import com.ts.travelserver.dto.BatchPayRequest;
import com.ts.travelserver.dto.BatchReviewRequest;
import com.ts.travelserver.dto.FinanceReviewRequest;
import com.ts.travelserver.dto.PaymentRequest;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.service.BatchService;
import com.ts.travelserver.service.FinanceService;
import com.ts.travelserver.vo.AiSuspicionVO;
import com.ts.travelserver.vo.BatchResultVO;
import com.ts.travelserver.vo.ExpenseListItemVO;
import com.ts.travelserver.vo.FinanceExceptionVO;
import com.ts.travelserver.vo.PaymentVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 财务复核与付款（模块 03）。
 *
 * <p>四维比对只列事实，不判违规（BR-06），所以带异常通过永远允许，
 * 但选了这一档必须写说明，退回同理。
 */
@RestController
@RequestMapping("/finance")
@RequireRole(SysUser.ROLE_FINANCE)
@RequiredArgsConstructor
public class FinanceController {

    private final FinanceService financeService;
    private final BatchService batchService;

    /**
     * E-01 待复核列表，带异常标记数。
     */
    @GetMapping("/pending")
    public R<PageResult<ExpenseListItemVO>> pending(ApplyQuery query) {
        return R.ok(financeService.pending(query));
    }

    /**
     * E-02 四维比对结果，按人 / 事 / 票 / 账分组。
     */
    /**
     * E-04 待付款列表：复核通过但还没打款的单，付款入口在这里。
     */
    @GetMapping("/payable")
    public R<PageResult<ExpenseListItemVO>> payable(ApplyQuery query) {
        return R.ok(financeService.payable(query));
    }

    @GetMapping("/{reportNo}/check")
    public R<FinanceExceptionVO.FourDimResult> check(@PathVariable String reportNo) {
        return R.ok(financeService.check(reportNo));
    }

    /**
     * E-05 可疑模式提示：同日同商户多张票、连号发票、单张金额显著高于本单均值。
     *
     * <p>与 E-02 的四维比对互补：四维抓「不一致」，这里抓「太巧了」。
     * 只提示不判违规，财务可以全部忽略。
     */
    @RequireRole({SysUser.ROLE_FINANCE, SysUser.ROLE_MANAGER})
    @GetMapping("/{reportNo}/suspicion")
    public R<AiSuspicionVO> suspicion(@PathVariable String reportNo) {
        return R.ok(financeService.suspicion(reportNo));
    }

    /**
     * E-03 复核结论：通过 / 带异常通过 / 退回，后两种必须写说明。
     */
    @PostMapping("/{reportNo}/review")
    public R<Void> review(@PathVariable String reportNo, @Valid @RequestBody FinanceReviewRequest request) {
        financeService.review(reportNo, request);
        return R.<Void>ok("复核结论已保存", null);
    }

    /**
     * E-04 付款并冲抵预借。一个事务里写付款记录、置报销单为已付款、累加预借已冲抵。
     *
     * <p>返回付款流水（PaymentVO）而不是空：付完款要给凭证，
     * 前端 ReviewDetail.vue 也读 id 提示「付款完成，流水号 xx」。
     */
    @PostMapping("/{reportNo}/pay")
    public R<PaymentVO> pay(@PathVariable String reportNo, @Valid @RequestBody PaymentRequest request) {
        PaymentVO vo = financeService.pay(reportNo, request);
        return R.ok("付款完成，预借已冲抵", vo);
    }

    /**
     * 批量复核：一次给多张待复核单下同一个结论。
     *
     * <p>逐单独立处理，失败的单列出来单独说明原因，不整批回滚。上限 50 张。
     */
    @PostMapping("/batch/review")
    public R<BatchResultVO> batchReview(@Valid @RequestBody BatchReviewRequest request) {
        BatchResultVO vo = batchService.review(request);
        return R.ok(ApprovalController.batchMsg("复核", vo), vo);
    }

    /**
     * 批量付款：照单付，每单付它的净额，预借按申请号自动找。
     *
     * <p>同样逐单独立处理。已经付过的单、没有预借款的单会进失败列表，
     * 不影响同一批里的其他单。
     */
    @PostMapping("/batch/pay")
    public R<BatchResultVO> batchPay(@Valid @RequestBody BatchPayRequest request) {
        BatchResultVO vo = batchService.pay(request);
        return R.ok(ApprovalController.batchMsg("付款", vo), vo);
    }
}
