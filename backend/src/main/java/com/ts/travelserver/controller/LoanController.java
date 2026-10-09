package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.R;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.service.LoanService;
import com.ts.travelserver.vo.LoanVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预借款查询（D-01）。
 *
 * <p>单独一个 Controller 是因为路径前缀是 /loan 而不是 /expense，
 * 但业务上它是报销的前置：预检要用余额算冲抵上限，所以开发顺序排在 D-02 前面。
 */
@RestController
@RequestMapping("/loan")
@RequiredArgsConstructor
public class LoanController {

    private final LoanService loanService;

    /**
     * D-01 预借额度查询。员工看自己能冲抵多少，财务复核时对账。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER, SysUser.ROLE_FINANCE})
    @GetMapping("/{applyNo}")
    public R<LoanVO> get(@PathVariable String applyNo) {
        return R.ok(loanService.get(applyNo));
    }
}
