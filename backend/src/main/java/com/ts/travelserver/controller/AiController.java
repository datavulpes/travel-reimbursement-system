package com.ts.travelserver.controller;

import com.ts.travelserver.ai.AiLogService;
import com.ts.travelserver.ai.DataQaService;
import com.ts.travelserver.ai.PolicyQaService;
import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.AiAdoptRequest;
import com.ts.travelserver.dto.DataQaRequest;
import com.ts.travelserver.dto.PolicyQaRequest;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.vo.DataQaVO;
import com.ts.travelserver.vo.PolicyQaVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 相关的两类接口：使用反馈（采纳回执）与制度问答。
 *
 * <p>采纳回执：<b>把「人后来到底用没用」回写给当初那条留痕</b>。
 * 留痕是出结论那一刻写的，那时人还没做决定，采纳与否只能事后补。
 * 没有这一路，ai_log 就只是「调用日志」，有了它才是「评测数据集」——
 * 能算出「AI 建议采纳率」，而不是靠一句「我觉得挺好用」。
 *
 * <p>制度问答：员工问一句差旅 / 报销规定，系统照着库里现行的规定答一句，
 * 答不上来就说答不上来，不用行业惯例填补。
 *
 * <p>两类接口的失败都一律吞掉：记不上一笔采纳、或者这一问没答上来，
 * 都不能让员工的这次操作失败。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
// 类级留空 = 任意已登录角色。制度问答的口径（差标、预算）不是机密，
// 但也不是给没登录的人看的——不设这一层，接口就成了谁都能调的公开问答。
@RequireRole
public class AiController {

    private final AiLogService aiLogService;
    private final PolicyQaService policyQaService;
    private final DataQaService dataQaService;

    /**
     * 填写助手的采纳回执。
     *
     * <p>前端提交申请成功后调用，把每条建议的最终取值发回来。
     * 只在 AI 远端可用、且这次真的出过建议时才有意义，其余情况静默跳过。
     */
    @RequireRole(SysUser.ROLE_EMPLOYEE)
    @PostMapping("/advice/adopt")
    public R<Boolean> adopt(@Valid @RequestBody AiAdoptRequest request) {
        boolean done = aiLogService.markAdviceAdopted(request.getBizNo(), request.getItems());
        return R.ok(done);
    }

    /**
     * 制度问答。
     *
     * <p>四个角色都能问——差标对谁都不是秘密，审批人一样会碰到「这趟能报多少」。
     * 返回值里带着答案依据的原文，页面上摆出来给问的人自己核对。
     */
    @PostMapping("/policy/qa")
    public R<PolicyQaVO> policyQa(@Valid @RequestBody PolicyQaRequest request) {
        return R.ok(policyQaService.answer(request.getQuestion()));
    }

    /**
     * 问数助手：管理层用大白话问业务数据，系统把真实数字算出来、讲成一句人话。
     *
     * <p>只有管理层能问（设计报告 6.8 权限矩阵：费效、预算、对象投入产出都是管理层视角）。
     * 数字来自本库 Service，模型只做叙事；模型没接上时直接陈列真实数字，不会返回空——
     * 问数助手要的是「把数说出来」，不是「把模型接上」。
     */
    @RequireRole(SysUser.ROLE_MANAGER)
    @PostMapping("/data/qa")
    public R<DataQaVO> dataQa(@Valid @RequestBody DataQaRequest request) {
        return R.ok(dataQaService.ask(request.getQuestion()));
    }
}
