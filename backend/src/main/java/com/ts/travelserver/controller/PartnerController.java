package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.R;
import com.ts.travelserver.service.PartnerService;
import com.ts.travelserver.vo.PartnerOptionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 往来对象下拉数据。
 *
 * <p>填单页原来把对象写死在前端常量里，库里新增或改名之后下拉对不上，
 * 员工会选到一个数据库里查不到名字的对象，审批页上关联对象那一栏就空着。
 * 这个接口把下拉接回 partner 表，改名字、加对象都由库说了算。
 *
 * <p>{@code @RequireRole} 不写 value：任意已登录角色都能取。
 * 对象名称不是敏感数据，先卡角色只会让「谁都能看到的一张下拉」多一道会过期的名单。
 *
 * <p>这里没有任何写入口——新增与改名走 {@code /admin/partner}，那是系统管理员的活。
 */
@RestController
@RequestMapping("/partner")
@RequireRole
@RequiredArgsConstructor
public class PartnerController {

    private final PartnerService partnerService;

    /**
     * 全部往来对象，供填单页的下拉框使用。
     */
    @GetMapping("/list")
    public R<List<PartnerOptionVO>> list() {
        return R.ok(partnerService.options());
    }
}
