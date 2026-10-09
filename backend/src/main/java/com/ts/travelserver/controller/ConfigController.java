package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.BudgetUpdateRequest;
import com.ts.travelserver.dto.ConfigLogQuery;
import com.ts.travelserver.dto.DeptCreateRequest;
import com.ts.travelserver.dto.StandardUpdateRequest;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.service.CostRefService;
import com.ts.travelserver.service.StandardService;
import com.ts.travelserver.vo.ConfigLogVO;
import com.ts.travelserver.vo.DeptVO;
import com.ts.travelserver.vo.StandardVO;
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
 * 差标与预算配置（模块 06，H-01 ~ H-04 及其增删改扩展），只有管理层能改。
 *
 * <p>差标按版本存：调整时不 UPDATE 旧行，而是插一条新的生效日期行，
 * 任何时点的标准都能追溯（BR-09）。删除只允许删最新一版，历史版本保留。
 *
 * <p>每一次新增 / 调整 / 删除都会往 config_log 追加一条（前后值 + 操作人 + 原因），
 * H-04 的留痕页面直接从那张表读，和差标版本链不再耦合。
 */
@RestController
@RequestMapping("/config")
@RequireRole(SysUser.ROLE_MANAGER)
@RequiredArgsConstructor
public class ConfigController {

    private final StandardService standardService;
    private final CostRefService costRefService;

    // ---------- 差标 ----------

    /**
     * H-01 差标列表，每组只返回当前生效的那一档。
     */
    @GetMapping("/standard")
    public R<List<StandardVO>> standards() {
        return R.ok(standardService.listStandards());
    }

    /**
     * 新增一档差标。这个组合从来没配过标准时用它，配过了请走 H-02「调整」。
     */
    @PostMapping("/standard")
    public R<StandardVO> createStandard(@Valid @RequestBody StandardUpdateRequest request) {
        return R.ok("差标已新增", standardService.createStandard(request));
    }

    /**
     * H-02 调整差标。插新版本，不动旧行，原因写进留痕。
     */
    @PutMapping("/standard")
    public R<StandardVO> updateStandard(@Valid @RequestBody StandardUpdateRequest request) {
        return R.ok("差标已更新，旧版本保留可追溯", standardService.updateStandard(request));
    }

    /**
     * 删除某一版差标，只能删该组最新的一条，历史版本留着追溯。
     *
     * <p>删除原因走 query 而不是 body：axios 的 DELETE 带 body 在部分网关上会被丢掉。
     */
    @DeleteMapping("/standard/{id}")
    public R<Void> deleteStandard(@PathVariable Long id,
                                  @RequestParam(required = false) String reason) {
        standardService.deleteStandard(id, reason);
        return R.<Void>ok("差标已删除，留痕里仍能看到这条记录", null);
    }

    // ---------- 部门与预算 ----------

    /**
     * 部门清单及预算，替掉前端写死的那份常量。查询需要登录，改才限管理层。
     */
    @RequireRole
    @GetMapping("/depts")
    public R<List<DeptVO>> depts() {
        return R.ok(standardService.listDepts());
    }

    /**
     * 新增部门，顺带给它一个初始月度预算。
     */
    @PostMapping("/dept")
    public R<DeptVO> createDept(@Valid @RequestBody DeptCreateRequest request) {
        return R.ok("部门已新增", standardService.createDept(request));
    }

    /**
     * H-03 调整部门月度预算，前后值与原因落到 config_log，留痕页能查到。
     */
    @PutMapping("/budget")
    public R<DeptVO> updateBudget(@Valid @RequestBody BudgetUpdateRequest request) {
        return R.ok("部门预算已更新", standardService.updateBudget(request));
    }

    /**
     * 删除部门。部门下还有员工或申请单时不给删，避免留下指向空部门的脏数据。
     */
    @DeleteMapping("/dept/{id}")
    public R<Void> deleteDept(@PathVariable Long id,
                              @RequestParam(required = false) String reason) {
        standardService.deleteDept(id, reason);
        return R.<Void>ok("部门已删除，留痕里仍能看到这条记录", null);
    }

    // ---------- 留痕与其他 ----------

    /**
     * H-04 调整留痕查询，分页，可按类型与关键字筛选。只读。
     *
     * <p>早先这里返回裸 List，前端 StandardConfig.vue 按 list / total 渲染，
     * 表格一片空白，后来改成统一的 PageResult，与 A-03 / D-03 / E-01 保持一致。
     */
    @GetMapping("/logs")
    public R<PageResult<ConfigLogVO>> logs(ConfigLogQuery query) {
        return R.ok(standardService.logs(query));
    }

    /**
     * 手动重算同类花费参考。没引定时任务框架，演示时点一下就能看到参考值更新。
     */
    @PostMapping("/cost-ref/refresh")
    public R<Integer> refreshCostRef() {
        return R.ok("同类花费参考已重算", costRefService.refresh());
    }
}
