package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.ApplyQuery;
import com.ts.travelserver.dto.ExpenseSubmitRequest;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.service.ExpenseService;
import com.ts.travelserver.vo.ExpenseDetailVO;
import com.ts.travelserver.vo.ExpenseListItemVO;
import com.ts.travelserver.vo.PrecheckVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报销单（模块 02）。
 *
 * <p>提交时后端按申请号归集票据算三个金额：
 * total = 票据金额之和，offset = min(预借余额, total)，net = total − offset。
 * 预检为 FORCE 且没带强制说明返回 2004（BR-05）。
 */
@RestController
@RequestMapping("/expense")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    /**
     * D-02 前置预检。提交前让员工先看结论，FORCE 时页面把强制说明输入框亮出来。
     * 与提交时同一个 computeChecks，口径保证一致。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @GetMapping("/preview")
    public R<PrecheckVO> preview(@RequestParam String applyNo) {
        return R.ok(expenseService.preview(applyNo));
    }

    /**
     * D-02 提交报销。预检 + 生成报销单一步完成。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @PostMapping("/submit")
    public R<ExpenseDetailVO> submit(@Valid @RequestBody ExpenseSubmitRequest request) {
        ExpenseDetailVO vo = expenseService.submit(request);
        return R.ok("报销单已提交，单号 " + vo.getReportNo(), vo);
    }

    /**
     * D-03 我的报销列表。财务调用时能看到全公司的单。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER, SysUser.ROLE_FINANCE})
    @GetMapping("/page")
    public R<PageResult<ExpenseListItemVO>> page(ApplyQuery query) {
        return R.ok(expenseService.page(query));
    }

    /**
     * D-04 报销单详情，含票据与预检结论。员工与财务可见。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER, SysUser.ROLE_FINANCE, SysUser.ROLE_MANAGER})
    @GetMapping("/{reportNo}")
    public R<ExpenseDetailVO> detail(@PathVariable String reportNo) {
        return R.ok(expenseService.detail(reportNo));
    }
}
