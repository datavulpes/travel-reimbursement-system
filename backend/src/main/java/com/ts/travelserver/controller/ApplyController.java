package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.ApplyQuery;
import com.ts.travelserver.dto.ApplySubmitRequest;
import com.ts.travelserver.dto.CostRefQuery;
import com.ts.travelserver.dto.ResultBackfillRequest;
import com.ts.travelserver.ai.AiAdviceService;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.service.ApplyService;
import com.ts.travelserver.vo.AiAdviceVO;
import com.ts.travelserver.vo.ApplyChainVO;
import com.ts.travelserver.vo.ApplyDetailVO;
import com.ts.travelserver.vo.ApplyListItemVO;
import com.ts.travelserver.vo.ApplySubmitResultVO;
import com.ts.travelserver.vo.CostRefVO;
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

import java.math.BigDecimal;

/**
 * 出差申请（模块 01 员工侧）。
 *
 * <p>角色口径按设计报告 6.8：员工与审批人都能填申请单。
 * 四个角色都能看详情（A-04），具体可见范围在 Service 里按「本人 / 本部门 / 财务 / 管理层」判断。
 */
@RestController
@RequestMapping("/apply")
@RequiredArgsConstructor
public class ApplyController {

    private final ApplyService applyService;
    private final AiAdviceService aiAdviceService;

    /**
     * A-09 填写建议：指出这份申请缺什么，并给一句可以直接采用的改写。
     *
     * <p>刻意不加 {@code @Valid}：草稿阶段本来就是缺胳膊少腿的，
     * 事由还没填完也该能拿到建议——那正是它最有用的时候。
     * 不落库、不改草稿，纯粹是给填写页的一句提示。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @PostMapping("/advice")
    public R<AiAdviceVO> advice(@RequestBody ApplySubmitRequest request) {
        return R.ok(aiAdviceService.advice(request == null ? new ApplySubmitRequest() : request));
    }

    /**
     * A-06 同类花费参考。填预估金额时给个参照，样本不足 5 条时 lowSample=true。
     *
     * <p>四个维度都可以只填一部分：填一项前端就重查一次。查不到样本时后端会按
     * 交通方式 → 城市等级 → 出差类型的顺序放宽，实际生效的维度随结果一起回来（relaxed=true）。
     * 一个维度都没填时前端不该发这个请求，直接显示空表格。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER, SysUser.ROLE_MANAGER})
    @GetMapping("/cost-ref")
    public R<CostRefVO> costRef(CostRefQuery query) {
        return R.ok(applyService.costRef(query));
    }

    /**
     * A-08 删除草稿。只有本人、且还停在 DRAFT 的单能删。
     *
     * <p>设计文档接口清单里没有这条，但草稿存了不用就必须能清掉，
     * 否则员工列表里堆一堆废弃半成品，反而找不到真正要处理那张。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @DeleteMapping("/{applyNo}")
    public R<Void> deleteDraft(@PathVariable String applyNo) {
        applyService.deleteDraft(applyNo);
        return R.<Void>ok("草稿 " + applyNo + " 已删除", null);
    }

    /**
     * A-01 保存草稿。不做差标校验、不算评价，也不做必填校验。
     *
     * <p>入参里带 applyNo 表示改写那张已有草稿，申请号保持不变；不带则新建一张。
     *
     * <p>这里刻意不挂 @Valid（A-02 提交仍挂）：草稿就是没填完的东西，
     * 事由没写、对象没选、行程段只有一段填了目的地，都应该能先存下来下班走人。
     * 完整性由提交那一道口子把关，草稿阶段拦必填只会逼着人一次填完。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @PostMapping("/draft")
    public R<ApplySubmitResultVO> draft(@RequestBody ApplySubmitRequest request) {
        ApplySubmitResultVO vo = applyService.draft(request);
        return R.ok("草稿已保存，申请号 " + vo.getApplyNo(), vo);
    }

    /**
     * A-02 提交申请。一次做四件事：重算合计、差标校验、写事前评价、校验超标说明。
     *
     * <p>把草稿直接提交也算在这里：入参带上那张草稿的 applyNo，
     * 后端把单据状态从 DRAFT 推到 DEPT_REVIEW，申请号不变。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @PostMapping("/submit")
    public R<ApplySubmitResultVO> submit(@Valid @RequestBody ApplySubmitRequest request) {
        ApplySubmitResultVO vo = applyService.submit(request);
        return R.ok("提交成功，申请号 " + vo.getApplyNo(), vo);
    }

    /**
     * A-03 我的申请列表。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @GetMapping("/page")
    public R<PageResult<ApplyListItemVO>> page(ApplyQuery query) {
        return R.ok(applyService.page(query));
    }

    /**
     * A-04 申请详情。单据 + 行程段 + 票据 + 流转记录一次取齐。
     */
    @RequireRole
    @GetMapping("/{applyNo}")
    public R<ApplyDetailVO> detail(@PathVariable String applyNo) {
        return R.ok(applyService.detail(applyNo));
    }

    /**
     * 审批链：这张单走到哪一步、每段谁办的、压了多久。
     *
     * <p>与 A-04 详情里的 records 是同一批留痕，但这里把它拼成了固定四段
     * （提交 → 部门初审 → 管理层复审 → 完成）并算好每段耗时，
     * 前端不用在页面里做「办没办、轮到谁」的判断。
     */
    @RequireRole
    @GetMapping("/{applyNo}/chain")
    public R<ApplyChainVO> chain(@PathVariable String applyNo) {
        return R.ok(applyService.chain(applyNo));
    }

    /**
     * A-05 驳回后修改重新提交，会生成新申请号（设计报告 6.3.1）。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @PutMapping("/{applyNo}")
    public R<ApplySubmitResultVO> resubmit(@PathVariable String applyNo,
                                           @Valid @RequestBody ApplySubmitRequest request) {
        ApplySubmitResultVO vo = applyService.resubmit(applyNo, request);
        return R.ok("已重新提交，新申请号 " + vo.getApplyNo(), vo);
    }

    /**
     * A-07 业务结果回填（设计文档接口清单里没有，建议新增）。
     *
     * <p>出差回来把「这趟办成什么」填回申请号，是费效比月报里「产出」那一半的数据来源。
     * 替代方案是把它并进 B-03（审批时顺手回填，DTO 字段已预留），
     * 但独立成接口员工自己回填更自然。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER, SysUser.ROLE_MANAGER})
    @PostMapping("/{applyNo}/result")
    public R<Void> backfill(@PathVariable String applyNo,
                            @Valid @RequestBody ResultBackfillRequest request) {
        request.setApplyNo(applyNo);
        applyService.backfill(request);
        return R.<Void>ok("业务结果已回填", null);
    }

    /**
     * 手工关闭申请单（设计文档接口清单里没有，与 A-07 一样是补齐闭环用的）。
     *
     * <p>补的是这样一个场景：申请批了，但因为取消行程、对方改期等原因没成行，
     * 也就没有任何费用要报销——这张单永远走不到「报销付完款」那条自动结案路径，
     * 只能一直停在 APPROVED。这里是让人手动给它一个终点。
     *
     * <p>只有本人员工能关，且名下不能有在途报销单，具体约束见
     * {@code ApplyService.close}。关完之后页面仍然可以补票、回填业务结果。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @PostMapping("/{applyNo}/close")
    public R<Void> close(@PathVariable String applyNo) {
        applyService.close(applyNo);
        return R.<Void>ok("申请单 " + applyNo + " 已关闭归档", null);
    }

    /**
     * A-08 催办：申请人等不及了，让审批人看见这张单压住了。
     *
     * <p>只写一行记录，不改状态、不自动流转、不发消息。
     * 同一张单 24 小时内只能催一次，具体约束见 {@code ApplyService.remind}。
     *
     * <p>note 走请求参数而不是请求体，是因为它通常就一句话甚至没有，
     * 为一个可选字段让前端多包一层 JSON 不划算。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @PostMapping("/{applyNo}/remind")
    public R<Void> remind(@PathVariable String applyNo,
                          @RequestParam(required = false) String note) {
        applyService.remind(applyNo, note);
        return R.<Void>ok("已提醒审批人，24 小时内不必重复催", null);
    }
}
