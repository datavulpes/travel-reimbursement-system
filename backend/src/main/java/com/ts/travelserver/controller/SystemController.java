package com.ts.travelserver.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.R;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.dto.LoginRequest;
import com.ts.travelserver.entity.Dept;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.mapper.DeptMapper;
import com.ts.travelserver.mapper.SysUserMapper;
import com.ts.travelserver.service.TokenService;
import com.ts.travelserver.vo.LoginVO;
import com.ts.travelserver.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;

/**
 * 系统管理（I-01 登录、I-02 退出、I-03 当前用户）。
 *
 * <p>登录用 BCrypt 校验密文，通过后签发 token（12 小时有效），
 * 之后的请求由 RoleInterceptor 按头里的 token 鉴权。不引 Spring Security，
 * 只借用它的 crypto 包做 BCrypt（设计报告 2.3 的三个主动简化之一）。
 */
@Slf4j
@RestController
@RequestMapping("/system")
@RequiredArgsConstructor
public class SystemController {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysUserMapper sysUserMapper;
    private final DeptMapper deptMapper;
    private final TokenService tokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * I-01 登录。账号不存在与密码错误统一提示，不告诉对方哪个对。
     */
    @PostMapping("/login")
    public R<LoginVO> login(@RequestBody LoginRequest request) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名或密码错误");
        }

        LoginVO vo = new LoginVO();
        vo.setToken(tokenService.create(user));
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        vo.setDeptId(user.getDeptId());
        vo.setDeptName(deptName(user.getDeptId()));
        vo.setExpiresAt(tokenService.expireAt().format(DATE_TIME));
        return R.ok("登录成功", vo);
    }

    /**
     * I-02 退出。服务端不维护会话，清掉线程上下文即可，前端负责删 localStorage。
     */
    @RequireRole
    @PostMapping("/logout")
    public R<Void> logout() {
        UserContext.clear();
        return R.ok();
    }

    /**
     * I-03 当前用户。前端刷新后靠它重新渲染菜单。
     */
    @RequireRole
    @GetMapping("/me")
    public R<UserInfoVO> me() {
        SysUser user = UserContext.get();
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        vo.setDeptId(user.getDeptId());
        vo.setDeptName(deptName(user.getDeptId()));
        vo.setLeaderId(leaderOf(user.getDeptId()));
        // 银行账号只有财务能看，而且脱敏（前 3 后 4）
        vo.setBankAccountMasked(SysUser.ROLE_FINANCE.equals(user.getRole())
                ? mask(user.getBankAccount())
                : null);
        return R.ok(vo);
    }

    private String deptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        Dept dept = deptMapper.selectById(deptId);
        return dept == null ? null : dept.getDeptName();
    }

    private Long leaderOf(Long deptId) {
        if (deptId == null) {
            return null;
        }
        Dept dept = deptMapper.selectById(deptId);
        return dept == null ? null : dept.getLeaderId();
    }

    /**
     * 银行账号脱敏：前 3 后 4，中间打星号。
     */
    private String mask(String bankAccount) {
        if (bankAccount == null || bankAccount.isBlank()) {
            return null;
        }
        int len = bankAccount.length();
        if (len <= 7) {
            return "***";
        }
        return bankAccount.substring(0, 3) + "****" + bankAccount.substring(len - 4);
    }
}
