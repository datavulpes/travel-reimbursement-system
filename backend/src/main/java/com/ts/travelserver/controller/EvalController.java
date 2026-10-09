package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.AiSemanticRequest;
import com.ts.travelserver.dto.ApplySubmitRequest;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.service.EvalService;
import com.ts.travelserver.vo.AiSemanticVO;
import com.ts.travelserver.vo.EvalResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出差合理性评价（模块 05）。
 *
 * <p>结论只作建议，不自动拦截（BR-08）。G-02 是提交前的预演，不落库；
 * G-01 查的是提交时写进 eval_result 的那一份。
 */
@RestController
@RequestMapping("/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;

    /**
     * G-02 事前提示预演。填完表单、点提交之前调一次，看看会落在哪一档。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @PostMapping("/preview")
    public R<EvalResultVO> preview(@Valid @RequestBody ApplySubmitRequest request) {
        return R.ok(evalService.preview(request));
    }

    /**
     * G-03 只算语义 Δ：读一遍事由与行程，给出 −10 ~ +5 的调节量与带依据的理由。
     *
     * <p>不跑规则四因子、不落库，纯粹给填写页「边写边看」用。
     * 它改不了分数——真正计入总分的是提交（A-02）那一次算的 Δ，
     * 前端拿到的这个值只用来提示「现在这样写会落在哪一档」。
     *
     * <p><b>刻意不加 {@code @Valid}</b>：这是填表过程中被调用的接口，事由刚敲两个字、
     * 对象还没选的时刻恰恰是它最有用的时候。套上 {@code ApplySubmitRequest} 上那堆
     * {@code @NotBlank/@NotNull} 会让这类请求直接 400，页面上的 AI 区块就永远空白。
     * 缺失字段由 {@code EvalService.context()} 容错处理：日期解析不出来记 null、
     * partnerId 为空就不带对象信息，Δ 照算，只是依据少一条。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @PostMapping("/semantic")
    public R<AiSemanticVO> semantic(@RequestBody AiSemanticRequest request) {
        return R.ok(evalService.semantic(request == null ? new AiSemanticRequest() : request));
    }

    /**
     * G-01 评价结论与依据明细。四个角色都能看自己权限范围内的单。
     */
    @RequireRole
    @GetMapping("/{applyNo}")
    public R<EvalResultVO> get(@PathVariable String applyNo) {
        return R.ok(evalService.get(applyNo));
    }
}
