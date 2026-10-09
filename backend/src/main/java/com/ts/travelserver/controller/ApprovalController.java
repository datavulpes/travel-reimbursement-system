package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.ApplyQuery;
import com.ts.travelserver.dto.ApprovalRequest;
import com.ts.travelserver.dto.BatchApprovalRequest;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.service.ApprovalService;
import com.ts.travelserver.service.BatchService;
import com.ts.travelserver.vo.AiRiskVO;
import com.ts.travelserver.vo.ApprovalViewVO;
import com.ts.travelserver.vo.ApplyListItemVO;
import com.ts.travelserver.vo.BatchResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 两级审批（模块 01 审批侧）。
 *
 * <p>初审由 dept.leader_id 指定的人做，复审由管理层做，
 * 所以 B-03 的允许角色是 APPROVER 与 MANAGER 两种。
 */
@RestController
@RequestMapping("/approval")
@RequireRole({SysUser.ROLE_APPROVER, SysUser.ROLE_MANAGER})
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;
    private final BatchService batchService;

    /**
     * B-01 待审列表。部门审批人看本部门初审单，管理层看复审单。
     */
    @GetMapping("/todo")
    public R<PageResult<ApplyListItemVO>> todo(ApplyQuery query) {
        return R.ok(approvalService.todo(query));
    }

    /**
     * B-02 审批详情。单据 + 参考 + 差标 + 评价 + 对象历史一次取齐。
     */
    @GetMapping("/{applyNo}")
    public R<ApprovalViewVO> detail(@PathVariable String applyNo) {
        return R.ok(approvalService.detail(applyNo));
    }

    /**
     * B-04 审批风险摘要：预估超区间、差标超限未说明、评价档位偏低、曾被驳回、事由过短。
     *
     * <p>按需触发（点一下算一次），不挂在 B-02 详情里——将来接了模型就有成本，
     * 而且摘要本来也不是每次打开都必须看的东西。
     */
    @PostMapping("/{applyNo}/risk")
    public R<AiRiskVO> risk(@PathVariable String applyNo) {
        return R.ok(approvalService.risk(applyNo));
    }

    /**
     * B-03 审批结论。同意或驳回，同时存依据快照；复审通过会生成预借款。
     */
    @PostMapping("/{applyNo}/decision")
    public R<Void> decision(@PathVariable String applyNo, @Valid @RequestBody ApprovalRequest request) {
        approvalService.decision(applyNo, request);
        return R.<Void>ok("审批已提交", null);
    }

    /**
     * 批量审批：一次给多张单下同一个结论，共用一份意见。
     *
     * <p>逐单独立处理，失败的单独列出来，不会因为一张单状态不对把整批回滚。
     * 上限 50 张，见 BatchService 的说明。
     */
    @PostMapping("/batch/decision")
    public R<BatchResultVO> batchDecision(@Valid @RequestBody BatchApprovalRequest request) {
        BatchResultVO vo = batchService.decision(request);
        return R.ok(batchMsg("审批", vo), vo);
    }

    /**
     * 批量结果的提示语。成了几张、没成几张，一句话说清，
     * 明细让前端照着 failed 列表去摆——那里面每张单的原因都不一样。
     */
    static String batchMsg(String action, BatchResultVO vo) {
        if (vo.getFailCnt() == 0) {
            return action + "完成，共 " + vo.getSuccessCnt() + " 张";
        }
        if (vo.getSuccessCnt() == 0) {
            return "一张都没" + action + "成功，共 " + vo.getFailCnt() + " 张失败";
        }
        return action + "完成 " + vo.getSuccessCnt() + " 张，" + vo.getFailCnt() + " 张未成功";
    }
}
