package com.ts.travelserver.controller;

import com.ts.travelserver.ai.AiStatusService;
import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.DeptInfoUpdateRequest;
import com.ts.travelserver.dto.PartnerSaveRequest;
import com.ts.travelserver.dto.UserSaveRequest;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.service.AdminService;
import com.ts.travelserver.service.BrowseService;
import com.ts.travelserver.vo.AdminUserVO;
import com.ts.travelserver.vo.AiStatusVO;
import com.ts.travelserver.vo.BrowseVO;
import com.ts.travelserver.vo.DeptVO;
import com.ts.travelserver.vo.PartnerVO;
import com.ts.travelserver.vo.TableVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 基础档案与数据浏览（系统管理员）。
 *
 * <p>这一层只有这个角色能进。拆出来的思路是「能不能改」不按表名划，
 * 按<strong>这个字段参不参与运行时计算</strong>划：
 * 参与计算的（差标金额、部门月度预算）留在管理层——
 * {@code dept.monthly_budget} 决定 BR-04 的预借额度上限，差标决定要不要强制写超标说明，
 * 这属于财权，动手的人得对费效分析页面上的结论负责。
 * 其余只被引用、不算数的档案交给这里。
 *
 * <p>业务单据、派生数据与三张日志表一个写入口都没有，
 * 连本文件里也不出现——不存在的接口不用去解释为什么不开放。
 * 要看可以走 {@code GET /admin/tables} 那一路，只查不改。
 */
@RestController
@RequestMapping("/admin")
@RequireRole(SysUser.ROLE_SYS_ADMIN)
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final BrowseService browseService;
    private final AiStatusService aiStatusService;

    // ---------- 往来对象 ----------

    /**
     * 往来对象列表。applyCount 大于 0 的不允许删除。
     */
    @GetMapping("/partners")
    public R<PageResult<PartnerVO>> partners(@RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "1") int pageNum,
                                             @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(adminService.partners(keyword, pageNum, pageSize));
    }

    /**
     * 新增往来对象。累计投入与产出从 0 起，不接受外部指定。
     */
    @PostMapping("/partner")
    public R<PartnerVO> createPartner(@Valid @RequestBody PartnerSaveRequest request) {
        return R.ok("往来对象已新增", adminService.createPartner(request));
    }

    /**
     * 修改往来对象的名称与类型。累计的两个金额不接受修改。
     */
    @PutMapping("/partner/{id}")
    public R<PartnerVO> updatePartner(@PathVariable Long id, @Valid @RequestBody PartnerSaveRequest request) {
        return R.ok("往来对象已更新", adminService.updatePartner(id, request));
    }

    /**
     * 删除往来对象。被单据引用过时后端直接拒掉（1001），前端按 applyCount 提前置灰按钮。
     *
     * <p>删除原因走 query：axios 的 DELETE 带 body 在部分网关上会被丢掉。
     */
    @DeleteMapping("/partner/{id}")
    public R<Void> deletePartner(@PathVariable Long id,
                                 @RequestParam(required = false) String reason) {
        adminService.deletePartner(id, reason);
        return R.<Void>ok("往来对象已删除，留痕里仍能看到这条记录", null);
    }

    // ---------- 用户账号 ----------

    /**
     * 用户账号列表，可按角色、部门、姓名或登录名筛选。
     */
    @GetMapping("/users")
    public R<PageResult<AdminUserVO>> users(@RequestParam(required = false) String role,
                                            @RequestParam(required = false) Long deptId,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") int pageNum,
                                            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(adminService.users(role, deptId, keyword, pageNum, pageSize));
    }

    /**
     * 新增用户。初始密码必须填，BCrypt 存密文。
     */
    @PostMapping("/user")
    public R<AdminUserVO> createUser(@Valid @RequestBody UserSaveRequest request) {
        return R.ok("用户已新增", adminService.createUser(request));
    }

    /**
     * 修改用户。password 留空表示不重置；自己的角色不能在这里改。
     */
    @PutMapping("/user/{id}")
    public R<AdminUserVO> updateUser(@PathVariable Long id, @Valid @RequestBody UserSaveRequest request) {
        return R.ok("用户已更新", adminService.updateUser(id, request));
    }

    /**
     * 删除用户（逻辑删，行还在）。有单据引用或还挂着部门负责人的会被拦下。
     */
    @DeleteMapping("/user/{id}")
    public R<Void> deleteUser(@PathVariable Long id,
                              @RequestParam(required = false) String reason) {
        adminService.deleteUser(id, reason);
        return R.<Void>ok("用户已删除，留痕里仍能看到这条记录", null);
    }

    // ---------- 部门档案 ----------

    /**
     * 改部门名称与负责人。月度预算与差标等级不在这里，那两项归管理层。
     */
    @PutMapping("/dept/{id}")
    public R<DeptVO> updateDeptInfo(@PathVariable Long id, @Valid @RequestBody DeptInfoUpdateRequest request) {
        return R.ok("部门档案已更新", adminService.updateDeptInfo(id, request));
    }

    // ---------- 全表只读浏览 ----------

    /**
     * 可以浏览哪些表。返回每张表的中文名、行数、列清单和注意事项。
     */
    @GetMapping("/tables")
    public R<List<TableVO>> tables() {
        return R.ok(browseService.tables());
    }

    /**
     * 浏览一张表的一页数据。
     *
     * <p>只有 SELECT。表名必须在白名单内，不在就返回 1001 而不是抛 SQL 异常——
     * 表名的白名单也是防注入的边界，别图省事改成拼 SQL。
     *
     * @param id 可选，填了就只取这一行，排查具体记录时不用翻页
     */
    @GetMapping("/tables/{name}")
    public R<BrowseVO> browse(@PathVariable String name,
                              @RequestParam(defaultValue = "1") int pageNum,
                              @RequestParam(defaultValue = "20") int pageSize,
                              @RequestParam(required = false) Long id) {
        return R.ok(browseService.browse(name, pageNum, pageSize, id));
    }

    // ---------- AI 运行状况 ----------

    /**
     * AI 运行状况：最近几天的调用量、成功率、降级次数与失败原因。
     *
     * <p>放在系统管理员端，是因为它是「运维视角」的东西——
     * 员工端与审批端一律不放这类说明性内容。
     *
     * <p><b>实时聚合 ai_log，不建统计表</b>：这几个数的量级现算就够，
     * 建表反而要处理回补与清理，且一旦和业务表不同步，页面上的数就没人敢信。
     *
     * @param days 统计窗口，默认 7 天
     */
    @GetMapping("/ai/status")
    public R<AiStatusVO> aiStatus(@RequestParam(defaultValue = "7") int days) {
        return R.ok(aiStatusService.status(days));
    }
}
