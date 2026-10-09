package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.ai.RiskSummarizer;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.dto.ApprovalRequest;
import com.ts.travelserver.dto.ApplyQuery;
import com.ts.travelserver.dto.ResultBackfillRequest;
import com.ts.travelserver.entity.ApprovalRecord;
import com.ts.travelserver.entity.CostRef;
import com.ts.travelserver.entity.Dept;
import com.ts.travelserver.entity.EvalResult;
import com.ts.travelserver.entity.FieldDraft;
import com.ts.travelserver.entity.SysMessage;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.mapper.ApprovalRecordMapper;
import com.ts.travelserver.mapper.DeptMapper;
import com.ts.travelserver.mapper.EvalResultMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.mapper.TripItineraryMapper;
import com.ts.travelserver.vo.AiRiskVO;
import com.ts.travelserver.vo.ApprovalViewVO;
import com.ts.travelserver.vo.ApplyDetailVO;
import com.ts.travelserver.vo.ApplyListItemVO;
import com.ts.travelserver.vo.EvalResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两级审批（模块 01 的审批侧，B-01 ~ B-03）。
 *
 * <p>状态机：DEPT_REVIEW（部门初审）→ FINAL_REVIEW（管理层复审）→ APPROVED，
 * 任一环节驳回整单直接 REJECTED。复审通过的同事务内生成预借款（BR-04）。
 *
 * <p>审批那一刻把页面上展示的参考值、差标结论、评价结论序列化写进依据快照，
 * 做到「批有依据、拒有理由、事后可溯」（FR-0107）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final TripApplyMapper tripApplyMapper;
    private final TripItineraryMapper tripItineraryMapper;
    private final ApprovalRecordMapper approvalRecordMapper;
    private final EvalResultMapper evalResultMapper;
    private final DeptMapper deptMapper;
    private final ApplyService applyService;
    private final EvalService evalService;
    private final CostRefService costRefService;
    private final StandardService standardService;
    private final LoanService loanService;
    private final ObjectMapper objectMapper;
    private final RiskSummarizer riskSummarizer;
    private final FieldDraftService fieldDraftService;
    private final MessageService messageService;

    // ---------- B-01 待审列表 ----------

    /**
     * 待审列表。部门审批人看本部门的初审单，管理层看复审单。
     *
     * <p>视图 v_approval_todo 能省掉这层判断，但它的 WHERE 里写的是 LEADER_REVIEW，
     * 与前端 dict.js 的 FINAL_REVIEW 对不上，直接用会查不出复审单，
     * 所以这里用基础表拼，口径自己把住（见接口开发文档第 2 节第 3 条）。
     *
     * <p>{@link ApplyQuery#getListType()} 为 done 时改成查已办——
     * 审批人签过字的单，对应 ApprovalTodo.vue 的「已办」tab。
     */
    public PageResult<ApplyListItemVO> todo(ApplyQuery query) {
        SysUser current = UserContext.get();
        if (current == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }

        boolean done = "done".equalsIgnoreCase(query.getListType());

        Page<TripApply> page = new Page<>(query.safePageNum(), query.safePageSize());
        LambdaQueryWrapper<TripApply> wrapper = new LambdaQueryWrapper<TripApply>()
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(TripApply::getApplyNo, query.getKeyword())
                        .or().like(TripApply::getReason, query.getKeyword()))
                .orderByAsc(TripApply::getCreatedAt);

        if (done) {
            // 已办：approval_record 里我签过字的单，不管现在流转到哪一步
            List<ApprovalRecord> mine = approvalRecordMapper.selectList(
                    new LambdaQueryWrapper<ApprovalRecord>()
                            .eq(ApprovalRecord::getApproverId, current.getId()));
            List<String> applyNos = mine.stream()
                    .map(ApprovalRecord::getApplyNo)
                    .distinct()
                    .toList();
            if (applyNos.isEmpty()) {
                return PageResult.empty(query.safePageNum(), query.safePageSize());
            }
            wrapper.in(TripApply::getApplyNo, applyNos);
        } else if (SysUser.ROLE_MANAGER.equals(current.getRole())) {
            wrapper.eq(TripApply::getStatus, TripApply.STATUS_FINAL_REVIEW);
        } else {
            // 部门审批人只能看本部门、且自己是该部门 leader 的单
            List<Long> myDeptIds = deptIdsLedBy(current.getId());
            if (myDeptIds.isEmpty()) {
                return PageResult.empty(query.safePageNum(), query.safePageSize());
            }
            wrapper.eq(TripApply::getStatus, TripApply.STATUS_DEPT_REVIEW)
                    .in(TripApply::getDeptId, myDeptIds);
        }
        tripApplyMapper.selectPage(page, wrapper);
        PageResult<ApplyListItemVO> result = PageResult.of(page, applyService::toListItem);
        fillRemindCnt(result);
        return result;
    }

    /**
     * 待办列表上标出「这张单被催过几次」。
     *
     * <p>一条 SQL 批量查，不在每一行里各查一次——列表十行就是十次查询，
     * 而催办次数只是个提示性数字，为它拖慢列表不划算。
     */
    private void fillRemindCnt(PageResult<ApplyListItemVO> result) {
        if (result == null || result.getList() == null || result.getList().isEmpty()) {
            return;
        }
        List<String> nos = result.getList().stream()
                .map(ApplyListItemVO::getApplyNo)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (nos.isEmpty()) {
            return;
        }
        Map<String, Long> counts = applyService.remindCounts(nos);
        for (ApplyListItemVO row : result.getList()) {
            Long cnt = counts.get(row.getApplyNo());
            row.setRemindCnt(cnt == null ? 0 : cnt.intValue());
        }
    }

    // ---------- B-02 审批详情 ----------

    /**
     * 一次取齐：单据信息 + 同类参考 + 差标明细 + 评价结论 + 该对象历史出差与产出。
     * 审批人不用点四次请求才看全（设计报告 6.3.6）。
     *
     * <p>这里用的是「能不能看」而不是「能不能批」：从已办点进来的单状态早就走完了，
     * 用 canApprove 去卡，页面直接空掉，回看自己当初批的依据都看不到。
     * 能不能下结论单独算成 {@link ApprovalViewVO#getCanApprove()} 给前端，
     * 由前端决定是摆审批按钮还是摆只读提示。
     */
    /**
     * B-04 审批风险摘要：把详情页四张卡里的信号汇总成几条关注点。
     *
     * <p>复用 {@link #detail(String)} 的可见范围校验——能看这张单的人才能看摘要，
     * 不单独再开一次权限口子。
     *
     * <p>为什么是独立接口而不是塞进 B-02：detail 是打开页面就调的，
     * 而风险摘要将来接了模型就有成本，按需触发更划算（也更好加缓存）。
     */
    public AiRiskVO risk(String applyNo) {
        return riskSummarizer.summarize(detail(applyNo));
    }

    public ApprovalViewVO detail(String applyNo) {
        TripApply apply = applyService.load(applyNo);
        assertReadable(apply);

        ApprovalViewVO vo = new ApprovalViewVO();
        vo.setApply(applyService.detailForApproval(applyNo));
        vo.setStandardHints(applyService.standardHints(applyNo));

        boolean canApprove = canApprove(apply);
        vo.setCanApprove(canApprove);
        vo.setReadonlyReason(canApprove ? null : readonlyReason(apply));

        // 同类参考 + 本次预估在区间中的位置
        TripItinerary first = firstItinerary(applyNo);
        CostRef ref = first == null ? null : costRefService.match(first.getToCity(), apply.getTripType());
        if (ref != null && ref.available()) {
            ApprovalViewVO.CostRefVO refVO = new ApprovalViewVO.CostRefVO();
            refVO.setToCity(ref.getToCity());
            refVO.setTripType(ref.getTripType());
            refVO.setAvgAmount(ref.getAvgAmount());
            refVO.setMinAmount(ref.getMinAmount());
            refVO.setMaxAmount(ref.getMaxAmount());
            refVO.setSampleCnt(ref.getSampleCnt());
            vo.setCostRef(refVO);
            vo.setRefPosition(position(ref, apply.getEstTotal()));
        }

        // 评价结论
        EvalResult eval = evalResultMapper.selectOne(
                new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getApplyNo, applyNo));
        if (eval != null) {
            vo.setEval(evalService.get(applyNo));
        }

        vo.setRecords(applyService.flowRecords(applyNo));
        vo.setHistory(history(apply));
        return vo;
    }

    // ---------- B-03 审批结论 ----------

    @Transactional(rollbackFor = Exception.class)
    public void decision(String applyNo, ApprovalRequest request) {
        TripApply apply = applyService.load(applyNo);
        SysUser current = UserContext.get();
        assertCanApprove(apply);

        String step;
        if (TripApply.STATUS_DEPT_REVIEW.equals(apply.getStatus())) {
            step = ApprovalRecord.STEP_DEPT_REVIEW;
        } else if (TripApply.STATUS_FINAL_REVIEW.equals(apply.getStatus())) {
            step = ApprovalRecord.STEP_FINAL_REVIEW;
        } else {
            throw BusinessException.statusForbidden("该申请当前不在审批中，状态为 " + apply.getStatus());
        }

        // 复审只能管理层下结论；初审必须是本部门的 leader
        if (ApprovalRecord.STEP_FINAL_REVIEW.equals(step)
                && !SysUser.ROLE_MANAGER.equals(current.getRole())) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "复审只能由管理层操作");
        }
        if (ApprovalRecord.STEP_DEPT_REVIEW.equals(step) && !isDeptLeader(apply.getDeptId(), current.getId())) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "你不是该部门的初审人");
        }
        if (!ApprovalRecord.RESULT_APPROVED.equals(request.getResult())
                && !ApprovalRecord.RESULT_REJECTED.equals(request.getResult())) {
            throw BusinessException.paramError("审批结论只能是 APPROVED 或 REJECTED");
        }
        // 驳回必须写意见（BR-03）
        if (request.rejected() && !StringUtils.hasText(request.getOpinion())) {
            throw BusinessException.paramError("驳回时必须填写审批意见");
        }

        // 依据快照：把页面上当时展示的值存下来，事后可回溯
        ApprovalRecord record = new ApprovalRecord();
        record.setApplyNo(applyNo);
        record.setApproverId(current.getId());
        record.setStep(step);
        record.setResult(request.getResult());
        record.setOpinion(request.getOpinion());
        record.setSnapshot(snapshot(applyNo, apply));
        approvalRecordMapper.insert(record);

        // 意见已经正式留痕，草稿清掉
        fieldDraftService.clear(FieldDraft.BIZ_APPLY_REVIEW, applyNo, FieldDraft.FIELD_OPINION);

        if (request.rejected()) {
            apply.setStatus(TripApply.STATUS_REJECTED);
        } else if (ApprovalRecord.STEP_DEPT_REVIEW.equals(step)) {
            apply.setStatus(TripApply.STATUS_FINAL_REVIEW);
        } else {
            apply.setStatus(TripApply.STATUS_APPROVED);
        }
        apply.setStatusAt(LocalDateTime.now());
        tripApplyMapper.updateById(apply);

        // 复审通过的同事务内生成预借款（BR-04）
        if (TripApply.STATUS_APPROVED.equals(apply.getStatus())) {
            loanService.create(apply);
        }

        // 结论出来了，告诉申请人。他不打开「我的申请」就永远不知道单子走到哪一步
        notifyApplicant(apply, step, request);

        // 审批人可以在审批时顺手把业务结果填了（FR-0108）
        if (StringUtils.hasText(request.getResultType())) {
            ResultBackfillRequest backfill = new ResultBackfillRequest();
            backfill.setApplyNo(applyNo);
            backfill.setPartnerId(apply.getPartnerId());
            backfill.setResultType(request.getResultType());
            backfill.setResultDesc(request.getResultDesc());
            backfill.setOutputAmount(request.getOutputAmount() == null ? BigDecimal.ZERO : request.getOutputAmount());
            applyService.backfill(backfill);
        }
    }

    /**
     * 审批结论出来后给申请人发一条消息。
     *
     * <p>三种结论三种说法，不给「你的申请有更新」这种正确的废话：
     * 驳回必须把意见带过去（那是他修改重提的唯一依据）、
     * 初审通过要说明还得等复审、复审通过要告诉他下一步该干什么。
     *
     * <p>复审通过顺手提「回填业务结果」不是凑字数——产出那一半数据全靠员工自己填，
     * 忘了填这趟出差在月报里就是「花了钱、没结果」。
     */
    private void notifyApplicant(TripApply apply, String step, ApprovalRequest request) {
        if (apply.getUserId() == null) {
            return;
        }
        String title;
        String body;
        if (request.rejected()) {
            title = "申请 " + apply.getApplyNo() + " 被驳回";
            body = "驳回意见：" + (StringUtils.hasText(request.getOpinion())
                    ? request.getOpinion().trim()
                    : "审批人未填写")
                    + "。改完之后可以重新提交，会生成新的申请号。";
        } else if (ApprovalRecord.STEP_DEPT_REVIEW.equals(step)) {
            title = "申请 " + apply.getApplyNo() + " 已通过部门初审";
            body = "已进入管理层复审，复审通过后就可以订票出行。";
        } else {
            title = "申请 " + apply.getApplyNo() + " 已审批通过";
            body = "可以订票出行了。出差回来记得上传票据、提交报销，并把这趟的业务结果回填一下——"
                    + "没回填的话，这趟在月报里会算成「花了钱、没结果」。";
        }
        messageService.send(apply.getUserId(), SysMessage.TYPE_APPLY_RESULT,
                SysMessage.BIZ_APPLY, apply.getApplyNo(), title, body);
    }

    // ---------- 内部 ----------

    /**
     * 只有待审的单、且轮到自己这一级，才允许下结论（B-03 用）。
     */
    private void assertCanApprove(TripApply apply) {
        SysUser current = UserContext.get();
        if (current == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        if (!inApprovalStep(apply)) {
            throw BusinessException.statusForbidden("该申请当前不在审批中，状态为 " + apply.getStatus());
        }
        if (canApprove(apply)) {
            return;
        }
        throw new BusinessException(ResultCode.NO_PERMISSION, "你不是该单的审批人");
    }

    /** 单据状态是不是停在某个审批环节上。只看单据走到哪，不看是谁。 */
    private boolean inApprovalStep(TripApply apply) {
        return TripApply.STATUS_DEPT_REVIEW.equals(apply.getStatus())
                || TripApply.STATUS_FINAL_REVIEW.equals(apply.getStatus());
    }

    /**
     * 当前用户现在能不能下结论。不抛异常，结果交给调用方：
     * B-03 拿它决定要不要拦，B-02 拿它决定前端摆审批框还是只读提示。
     */
    private boolean canApprove(TripApply apply) {
        SysUser current = UserContext.get();
        if (current == null || !inApprovalStep(apply)) {
            return false;
        }
        if (SysUser.ROLE_MANAGER.equals(current.getRole())) {
            return true;
        }
        return isDeptLeader(apply.getDeptId(), current.getId());
    }

    /**
     * 不能批的时候给出人话说明。前端原样显示，别让人对着一个没有按钮的页面发愣。
     */
    private String readonlyReason(TripApply apply) {
        if (inApprovalStep(apply)) {
            return TripApply.STATUS_FINAL_REVIEW.equals(apply.getStatus())
                    ? "这单已经走到管理层复审，你这一级处理过了，现在只能查看"
                    : "这单正在部门初审，只能查看";
        }
        return switch (apply.getStatus() == null ? "" : apply.getStatus()) {
            case TripApply.STATUS_DRAFT -> "这单还是草稿，员工提交后才会进审批";
            case TripApply.STATUS_REJECTED -> "这单已被驳回，等员工修改重提";
            case TripApply.STATUS_APPROVED -> "这单已审批通过，不能再改结论";
            case TripApply.STATUS_CLOSED -> "这单已关闭";
            default -> "当前状态为 " + apply.getStatus();
        };
    }

    /**
     * 这张单的可见范围（B-02 用）。
     *
     * <p>比「能不能批」宽一档：通用范围是本人 / 本部门 / 财务 / 管理层，
     * 额外再放一条 —— 流转记录里有我签过字的也能看。
     * 「已办」就是这一条撑起来的：单子早就流转走了，但当初是我批的，
     * 事后回查依据（FR-0107）属于正常诉求，不该被权限挡在门外。
     */
    private void assertReadable(TripApply apply) {
        SysUser current = UserContext.get();
        if (current == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        if (SysUser.ROLE_FINANCE.equals(current.getRole()) || SysUser.ROLE_MANAGER.equals(current.getRole())) {
            return;
        }
        if (apply.getUserId() != null && apply.getUserId().equals(current.getId())) {
            return;
        }
        if (SysUser.ROLE_APPROVER.equals(current.getRole()) && apply.getDeptId() != null
                && apply.getDeptId().equals(current.getDeptId())) {
            return;
        }
        Long signed = approvalRecordMapper.selectCount(new LambdaQueryWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getApplyNo, apply.getApplyNo())
                .eq(ApprovalRecord::getApproverId, current.getId()));
        if (signed != null && signed > 0) {
            return;
        }
        throw new BusinessException(ResultCode.NO_PERMISSION, "只能查看本人或本部门的申请");
    }

    private boolean isDeptLeader(Long deptId, Long userId) {
        if (deptId == null || userId == null) {
            return false;
        }
        Dept dept = deptMapper.selectById(deptId);
        return dept != null && dept.getLeaderId() != null && dept.getLeaderId().equals(userId);
    }

    private List<Long> deptIdsLedBy(Long userId) {
        List<Dept> depts = deptMapper.selectList(
                new LambdaQueryWrapper<Dept>().eq(Dept::getLeaderId, userId));
        List<Long> ids = new ArrayList<>();
        for (Dept dept : depts) {
            ids.add(dept.getId());
        }
        return ids;
    }

    /**
     * 该对象历史出差与产出，用来判断这趟去得值不值。
     */
    private List<ApplyListItemVO> history(TripApply apply) {
        if (apply.getPartnerId() == null) {
            return List.of();
        }
        List<TripApply> list = tripApplyMapper.selectList(new LambdaQueryWrapper<TripApply>()
                .eq(TripApply::getPartnerId, apply.getPartnerId())
                .ne(TripApply::getApplyNo, apply.getApplyNo())
                .orderByDesc(TripApply::getCreatedAt)
                .last("limit 10"));
        List<ApplyListItemVO> result = new ArrayList<>();
        for (TripApply item : list) {
            result.add(applyService.toListItem(item));
        }
        return result;
    }

    /**
     * 依据快照：参考值、差标结论、评价结论，三项一起存，事后能还原当时的页面。
     */
    private String snapshot(String applyNo, TripApply apply) {
        Map<String, Object> root = new LinkedHashMap<>();
        TripItinerary first = firstItinerary(applyNo);
        CostRef ref = first == null ? null : costRefService.match(first.getToCity(), apply.getTripType());
        if (ref != null) {
            Map<String, Object> refMap = new LinkedHashMap<>();
            refMap.put("toCity", ref.getToCity());
            refMap.put("tripType", ref.getTripType());
            refMap.put("avgAmount", ref.getAvgAmount());
            refMap.put("minAmount", ref.getMinAmount());
            refMap.put("maxAmount", ref.getMaxAmount());
            refMap.put("sampleCnt", ref.getSampleCnt());
            root.put("costRef", refMap);
        }
        root.put("estTotal", apply.getEstTotal());
        root.put("standardResult", apply.getStandardResult());
        root.put("exceedReason", apply.getExceedReason());
        EvalResult eval = evalResultMapper.selectOne(
                new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getApplyNo, applyNo));
        if (eval != null) {
            Map<String, Object> evalMap = new LinkedHashMap<>();
            evalMap.put("level", eval.getLevel());
            evalMap.put("totalScore", eval.getTotalScore());
            root.put("eval", evalMap);
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("依据快照序列化失败，按空快照处理：{}", e.getMessage());
            return null;
        }
    }

    private TripItinerary firstItinerary(String applyNo) {
        List<TripItinerary> list = tripItineraryMapper.selectList(
                new LambdaQueryWrapper<TripItinerary>()
                        .eq(TripItinerary::getApplyNo, applyNo)
                        .orderByAsc(TripItinerary::getSeq)
                        .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private BigDecimal position(CostRef ref, BigDecimal estTotal) {
        if (estTotal == null || ref.getMinAmount() == null || ref.getMaxAmount() == null) {
            return null;
        }
        BigDecimal span = ref.getMaxAmount().subtract(ref.getMinAmount());
        if (span.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return estTotal.subtract(ref.getMinAmount()).divide(span, 4, RoundingMode.HALF_UP);
    }

    /**
     * 单据信息，供前端在审批页顶部复用。
     */
    public ApplyDetailVO applyDetail(String applyNo) {
        return applyService.detailForApproval(applyNo);
    }

    /**
     * 评价结论，审批页单独取一份用。
     */
    public EvalResultVO evalResult(String applyNo) {
        return evalService.get(applyNo);
    }

    /**
     * 差标上限合计，前端展示「上限 vs 预估」时要用到。
     */
    public BigDecimal standardCap(String applyNo) {
        List<TripItinerary> itineraries = tripItineraryMapper.selectList(
                new LambdaQueryWrapper<TripItinerary>().eq(TripItinerary::getApplyNo, applyNo));
        return standardService.capTotal(itineraries);
    }
}
