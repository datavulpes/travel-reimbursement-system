package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ts.travelserver.common.ApplyNoGenerator;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.config.SlaConfig;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.common.UserContext;
import lombok.extern.slf4j.Slf4j;
import com.ts.travelserver.dto.ApplyQuery;
import com.ts.travelserver.dto.ApplySubmitRequest;
import com.ts.travelserver.dto.CostRefQuery;
import com.ts.travelserver.dto.ResultBackfillRequest;
import com.ts.travelserver.entity.ApprovalRecord;
import com.ts.travelserver.entity.ApplyRemind;
import com.ts.travelserver.entity.BizResult;
import com.ts.travelserver.entity.CostRef;
import com.ts.travelserver.entity.Dept;
import com.ts.travelserver.entity.EvalResult;
import com.ts.travelserver.entity.ExpenseReport;
import com.ts.travelserver.entity.FieldDraft;
import com.ts.travelserver.entity.Partner;
import com.ts.travelserver.entity.SysMessage;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.mapper.ApprovalRecordMapper;
import com.ts.travelserver.mapper.ApplyRemindMapper;
import com.ts.travelserver.mapper.BizResultMapper;
import com.ts.travelserver.mapper.DeptMapper;
import com.ts.travelserver.mapper.EvalResultMapper;
import com.ts.travelserver.mapper.ExpenseReportMapper;
import com.ts.travelserver.mapper.InvoiceMapper;
import com.ts.travelserver.mapper.PartnerMapper;
import com.ts.travelserver.mapper.SysUserMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.mapper.TripItineraryMapper;
import com.ts.travelserver.service.support.AccessGuard;
import com.ts.travelserver.service.support.FileStorage;
import com.ts.travelserver.vo.ApplyChainVO;
import com.ts.travelserver.vo.ApplyDetailVO;
import com.ts.travelserver.vo.ApplyListItemVO;
import com.ts.travelserver.vo.ApplySubmitResultVO;
import com.ts.travelserver.vo.ApprovalViewVO;
import com.ts.travelserver.vo.CostRefVO;
import com.ts.travelserver.vo.EvalResultVO;
import com.ts.travelserver.vo.InvoiceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 出差申请（模块 01 的员工侧，A-01 ~ A-06 与建议新增的 A-07）。
 *
 * <p>申请单是全系统的业务主线：行程段、审批记录、预借款、票据、报销单、
 * 业务结果、评价结果全部通过 applyNo 挂到这张单上（设计报告 4.1）。
 *
 * <p>提交瞬间后端做四件事（设计报告 7.4）：
 * ① 用服务端重算的合计覆盖前端传的 estTotal；
 * ② 差标校验出 NORMAL / HIGH / EXCEED；
 * ③ 调评价引擎拿事前提示并写 eval_result；
 * ④ EXCEED 且没填超标说明 → 抛 2003（BR-01）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplyService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TripApplyMapper tripApplyMapper;
    private final TripItineraryMapper tripItineraryMapper;
    private final ExpenseReportMapper expenseReportMapper;
    private final ApprovalRecordMapper approvalRecordMapper;
    private final EvalResultMapper evalResultMapper;
    private final InvoiceMapper invoiceMapper;
    private final BizResultMapper bizResultMapper;
    private final PartnerMapper partnerMapper;
    private final DeptMapper deptMapper;
    private final SysUserMapper sysUserMapper;
    private final ApplyNoGenerator applyNoGenerator;
    private final StandardService standardService;
    private final EvalService evalService;
    private final CostRefService costRefService;
    private final FieldDraftService fieldDraftService;
    private final FileStorage fileStorage;
    private final AccessGuard accessGuard;
    private final ApplyRemindMapper applyRemindMapper;
    private final SlaConfig slaConfig;
    private final MessageService messageService;

    // ---------- A-06 同类花费参考 ----------

    public CostRefVO costRef(CostRefQuery query) {
        return costRefService.queryLive(query);
    }

    // ---------- A-01 存草稿 ----------

    /**
     * 草稿只落库，不做差标校验、不算评价——半成品没必要提示超没超标。
     *
     * <p>带上 applyNo 时改写那张已有草稿，申请号保持不变。
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplySubmitResultVO draft(ApplySubmitRequest request) {
        return save(request, TripApply.STATUS_DRAFT);
    }

    // ---------- A-08 删除草稿 ----------

    /**
     * 删掉一张还没进审批流的草稿。行程段一并删干净，不然留下孤儿行。
     *
     * <p>只认「本人 + DRAFT」：已经交上去的单不能靠这个接口抹掉，
     * 那条链路得走审批驳回（B-03），否则审批人刚批完单子就没了，追溯链断掉。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(String applyNo) {
        TripApply apply = load(applyNo);
        if (!Objects.equals(apply.getUserId(), UserContext.currentId())) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "只能删除自己的草稿");
        }
        if (!TripApply.STATUS_DRAFT.equals(apply.getStatus())) {
            throw BusinessException.statusForbidden(
                    "只有草稿可以删除，当前状态为 " + apply.getStatus() + "，已进入审批的单请走驳回流程");
        }
        tripItineraryMapper.delete(new LambdaQueryWrapper<TripItinerary>()
                .eq(TripItinerary::getApplyNo, applyNo));
        tripApplyMapper.delete(new LambdaQueryWrapper<TripApply>()
                .eq(TripApply::getApplyNo, applyNo));
    }

    // ---------- A-02 提交 ----------

    @Transactional(rollbackFor = Exception.class)
    public ApplySubmitResultVO submit(ApplySubmitRequest request) {
        return save(request, TripApply.STATUS_DEPT_REVIEW);
    }

    /**
     * A-05：驳回后修改重新提交。按设计报告 6.3.1，重新提交生成新申请号，
     * 老单保留在 REJECTED 状态，审批链不被抹掉。
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplySubmitResultVO resubmit(String applyNo, ApplySubmitRequest request) {
        TripApply old = load(applyNo);
        if (!TripApply.STATUS_REJECTED.equals(old.getStatus())) {
            throw BusinessException.statusForbidden("只有被驳回的申请可以重新提交，当前状态为 " + old.getStatus());
        }
        if (!old.getUserId().equals(UserContext.currentId())) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "只能重新提交自己的申请");
        }
        return save(request, TripApply.STATUS_DEPT_REVIEW);
    }

    // ---------- A-03 我的申请 ----------

    public PageResult<ApplyListItemVO> page(ApplyQuery query) {
        Long userId = UserContext.currentId();
        Page<TripApply> page = new Page<>(query.safePageNum(), query.safePageSize());
        LambdaQueryWrapper<TripApply> wrapper = new LambdaQueryWrapper<TripApply>()
                .eq(TripApply::getUserId, userId)
                .eq(StringUtils.hasText(query.getStatus()), TripApply::getStatus, query.getStatus())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(TripApply::getApplyNo, query.getKeyword())
                        .or().like(TripApply::getReason, query.getKeyword()))
                .orderByDesc(TripApply::getCreatedAt);
        tripApplyMapper.selectPage(page, wrapper);
        return PageResult.of(page, this::toListItem);
    }

    // ---------- A-04 申请详情 ----------

    /**
     * 一次取齐：单据信息 + 行程段 + 已挂接票据 + 流转记录。
     *
     * <p>可见范围：申请人本人、本部门审批人、财务、管理层，其余返回 3001。
     */
    public ApplyDetailVO detail(String applyNo) {
        TripApply apply = load(applyNo);
        assertVisible(apply);

        ApplyDetailVO vo = new ApplyDetailVO();
        vo.setApplyNo(apply.getApplyNo());
        vo.setApplicant(name(apply.getUserId()));
        vo.setDeptName(deptName(apply.getDeptId()));
        vo.setPartnerId(apply.getPartnerId());
        vo.setPartnerName(partnerName(apply.getPartnerId()));
        vo.setReason(apply.getReason());
        vo.setTripType(apply.getTripType());
        vo.setTripTypeNote(apply.getTripTypeNote());
        vo.setEstTraffic(apply.getEstTraffic());
        vo.setEstHotel(apply.getEstHotel());
        vo.setEstTotal(apply.getEstTotal());
        vo.setStandardResult(apply.getStandardResult());
        vo.setExceedReason(apply.getExceedReason());
        vo.setStatus(apply.getStatus());
        vo.setCreatedAt(apply.getCreatedAt() == null ? null : apply.getCreatedAt().format(DATE_TIME));

        // 行程段，顺带统计每段挂了几张票
        List<TripItinerary> itineraries = tripItineraryMapper.selectList(
                new LambdaQueryWrapper<TripItinerary>()
                        .eq(TripItinerary::getApplyNo, applyNo)
                        .orderByAsc(TripItinerary::getSeq));
        List<ApplyDetailVO.ItineraryVO> itineraryVOs = new ArrayList<>();
        for (TripItinerary item : itineraries) {
            ApplyDetailVO.ItineraryVO itemVO = new ApplyDetailVO.ItineraryVO();
            itemVO.setId(item.getId());
            itemVO.setSeq(item.getSeq());
            itemVO.setStartDate(item.getStartDate() == null ? null : item.getStartDate().toString());
            itemVO.setEndDate(item.getEndDate() == null ? null : item.getEndDate().toString());
            itemVO.setFromCity(item.getFromCity());
            itemVO.setToCity(item.getToCity());
            itemVO.setTrafficType(item.getTrafficType());
            itemVO.setCityLevel(item.getCityLevel());
            Long cnt = invoiceMapper.selectCount(new LambdaQueryWrapper<com.ts.travelserver.entity.Invoice>()
                    .eq(com.ts.travelserver.entity.Invoice::getItineraryId, item.getId()));
            itemVO.setInvoiceCnt(cnt == null ? 0 : cnt.intValue());
            itineraryVOs.add(itemVO);
        }
        vo.setItineraries(itineraryVOs);

        // 本单票据
        List<com.ts.travelserver.entity.Invoice> invoices = invoiceMapper.selectList(
                new LambdaQueryWrapper<com.ts.travelserver.entity.Invoice>()
                        .eq(com.ts.travelserver.entity.Invoice::getApplyNo, applyNo)
                        .orderByDesc(com.ts.travelserver.entity.Invoice::getId));
        List<InvoiceVO> invoiceVOs = new ArrayList<>();
        for (com.ts.travelserver.entity.Invoice invoice : invoices) {
            /*
             * 这里必须带上影像地址。原先传 null，员工在「我的申请 → 详情」里能看到票号和金额，
             * 却看不到票长什么样——而详情接口本身是有归属校验的（不是本人的单直接 3001），
             * 带出去不会越权。地址同样是 30 分钟过期的签名链，跟票据页那套一致。
             */
            invoiceVOs.add(InvoiceService.toVO(invoice,
                    fileStorage.signedUrl(invoice.getImgPath()),
                    fileStorage.thumbUrl(invoice.getImgPath())));
        }
        vo.setInvoices(invoiceVOs);

        // 已回填的业务结果，按回填时间摆在最前
        List<BizResult> bizResults = bizResultMapper.selectList(
                new LambdaQueryWrapper<BizResult>()
                        .eq(BizResult::getApplyNo, applyNo)
                        .orderByAsc(BizResult::getId));
        List<ApplyDetailVO.BizResultVO> resultVOs = new ArrayList<>();
        for (BizResult item : bizResults) {
            ApplyDetailVO.BizResultVO itemVO = new ApplyDetailVO.BizResultVO();
            itemVO.setId(item.getId());
            itemVO.setResultType(item.getResultType());
            itemVO.setResultDesc(item.getResultDesc());
            itemVO.setOutputAmount(item.getOutputAmount());
            itemVO.setFilledBy(name(item.getFilledBy()));
            itemVO.setFilledAt(item.getFilledAt() == null ? null : item.getFilledAt().format(DATE_TIME));
            resultVOs.add(itemVO);
        }
        vo.setResults(resultVOs);

        // 流转记录：提交 + 初审 + 复审
        vo.setRecords(flowRecords(applyNo));
        return vo;
    }

    // ---------- A-07 业务结果回填（设计文档清单里没有，建议新增） ----------

    /**
     * 出差回来把「这趟到底办成什么」填回申请号（FR-0108）。
     *
     * <p>它是费效比月报里「产出」那一半的数据来源，也是 F1 历史产出率因子的依据。
     * 回填「无进展」后，下一次同对象出差的事前提示会命中「上一趟无进展且间隔太短」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void backfill(ResultBackfillRequest request) {
        TripApply apply = load(request.getApplyNo());
        if (!apply.getUserId().equals(UserContext.currentId())
                && !SysUser.ROLE_APPROVER.equals(UserContext.currentRole())
                && !SysUser.ROLE_MANAGER.equals(UserContext.currentRole())) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "只能回填自己的出差结果");
        }
        if (!TripApply.STATUS_APPROVED.equals(apply.getStatus())
                && !TripApply.STATUS_CLOSED.equals(apply.getStatus())) {
            throw BusinessException.statusForbidden("申请未通过，不能回填业务结果");
        }

        Long partnerId = request.getPartnerId() == null ? apply.getPartnerId() : request.getPartnerId();
        BizResult result = new BizResult();
        result.setApplyNo(apply.getApplyNo());
        result.setPartnerId(partnerId);
        result.setResultType(request.getResultType());
        result.setResultDesc(request.getResultDesc());
        result.setOutputAmount(request.getOutputAmount());
        result.setFilledBy(UserContext.currentId());
        bizResultMapper.insert(result);

        // 冗余汇总字段增量更新，月报不用每次全表汇总（设计报告 5.6）
        if (partnerId != null) {
            Partner partner = partnerMapper.selectById(partnerId);
            if (partner != null) {
                BigDecimal output = partner.getTotalOutput() == null ? BigDecimal.ZERO : partner.getTotalOutput();
                partner.setTotalOutput(output.add(request.getOutputAmount()));
                partnerMapper.updateById(partner);
            }
        }
    }

    // ---------- 供审批模块复用 ----------

    /**
     * 供 ApprovalService 取单据上下文，避免审批模块自己再拼一遍。
     */
    public ApplyDetailVO detailForApproval(String applyNo) {
        return detail(applyNo);
    }

    public List<ApplyDetailVO.FlowRecordVO> flowRecords(String applyNo) {
        List<ApprovalRecord> records = approvalRecordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getApplyNo, applyNo)
                        .orderByAsc(ApprovalRecord::getId));
        List<ApplyDetailVO.FlowRecordVO> result = new ArrayList<>();
        for (ApprovalRecord record : records) {
            ApplyDetailVO.FlowRecordVO vo = new ApplyDetailVO.FlowRecordVO();
            vo.setId(record.getId());
            vo.setApplyNo(record.getApplyNo());
            vo.setApprover(name(record.getApproverId()));
            vo.setStep(record.getStep());
            vo.setResult(record.getResult());
            vo.setOpinion(record.getOpinion());
            vo.setSnapshot(record.getSnapshot());
            vo.setApprovedAt(record.getApprovedAt() == null ? null : record.getApprovedAt().format(DATE_TIME));
            result.add(vo);
        }
        return result;
    }

    // ---------- 审批链 ----------

    /**
     * 审批链：把流转记录拼成固定四段（提交 → 部门初审 → 管理层复审 → 完成），
     * 每段带状态、办理人、结论、耗时，前端照着画时间轴即可。
     *
     * <p>为什么单开一个接口而不是塞进 A-04 详情：详情是打开页面就要的，
     * 而链条要额外查催办次数、算四段耗时，属于「展开看看」那一类东西。
     * 塞进详情会让每次打开详情都多背一份计算。
     *
     * <p>驳回不额外加段：在哪一级被驳回，那一段的结论就是「驳回」，
     * 后面没走到的段标 SKIPPED，一眼看得出卡在哪一级。
     */
    public ApplyChainVO chain(String applyNo) {
        TripApply apply = load(applyNo);
        assertVisible(apply);

        List<ApprovalRecord> records = approvalRecordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getApplyNo, applyNo)
                        .orderByAsc(ApprovalRecord::getId));

        // statusAt 为空（加这一列之前的老数据）时 waited 记 -1，后面一律按「算不出来」处理
        long waited = apply.getStatusAt() == null
                ? -1L
                : Duration.between(apply.getStatusAt(), LocalDateTime.now()).toHours();

        ApplyChainVO vo = new ApplyChainVO();
        vo.setApplyNo(applyNo);
        vo.setStatus(apply.getStatus());
        vo.setRemindCnt(remindCntOf(applyNo));
        int limit = slaConfig.hoursOf(apply.getStatus());
        boolean over = limit > 0 && waited > limit;
        vo.setOverdue(over);
        vo.setOverdueHours(over ? waited - limit : 0L);

        List<ApplyChainVO.StepVO> steps = new ArrayList<>();
        steps.add(submitStep(apply, records));
        steps.add(reviewStep(apply, records, ApprovalRecord.STEP_DEPT_REVIEW,
                TripApply.STATUS_DEPT_REVIEW, "部门初审", deptLeaderName(apply.getDeptId()),
                "部门负责人", slaConfig.getDeptReviewHours(), waited));
        steps.add(reviewStep(apply, records, ApprovalRecord.STEP_FINAL_REVIEW,
                TripApply.STATUS_FINAL_REVIEW, "管理层复审", "管理层", "管理层",
                slaConfig.getFinalReviewHours(), waited));
        steps.add(finishStep(apply, records));
        vo.setSteps(steps);

        String current = null;
        for (ApplyChainVO.StepVO step : steps) {
            if (ApplyChainVO.StepVO.STATE_CURRENT.equals(step.getState())) {
                current = step.getStep();
            }
        }
        vo.setCurrentStep(current);
        return vo;
    }

    /** 第一段：提交。草稿状态下它才可能是「进行中」——球还在申请人手上 */
    private ApplyChainVO.StepVO submitStep(TripApply apply, List<ApprovalRecord> records) {
        ApplyChainVO.StepVO step = new ApplyChainVO.StepVO();
        step.setStep(ApprovalRecord.STEP_SUBMIT);
        step.setStepText("提交申请");
        step.setActorName(name(apply.getUserId()));
        step.setActorRole("申请人");

        ApprovalRecord rec = lastOf(records, ApprovalRecord.STEP_SUBMIT);
        if (rec != null) {
            step.setState(ApplyChainVO.StepVO.STATE_DONE);
            step.setResult(rec.getResult());
            step.setResultText("已提交");
            step.setOpinion(rec.getOpinion());
            step.setAt(fmt(rec.getApprovedAt()));
            step.setWaitHours(hoursFrom(rec.getApprovedAt(), nextAt(records, rec)));
        } else if (TripApply.STATUS_DRAFT.equals(apply.getStatus())) {
            step.setState(ApplyChainVO.StepVO.STATE_CURRENT);
        } else {
            // 状态已经推进过，说明确实提交过，只是这一条留痕缺失（老数据）
            step.setState(ApplyChainVO.StepVO.STATE_DONE);
        }
        return step;
    }

    /**
     * 中间两段：部门初审与管理层复审共用这一套判断。
     *
     * <p>等待中（CURRENT）这两段才带限时与已等待时长——
     * 只有「现在正压在谁的桌上」这个信息对人有意义。
     */
    private ApplyChainVO.StepVO reviewStep(TripApply apply, List<ApprovalRecord> records,
                                          String stepCode, String waitStatus, String stepText,
                                          String fallbackActor, String actorRole,
                                          int limitHours, long waited) {
        ApplyChainVO.StepVO step = new ApplyChainVO.StepVO();
        step.setStep(stepCode);
        step.setStepText(stepText);
        step.setActorName(fallbackActor);
        step.setActorRole(actorRole);
        step.setLimitHours(limitHours);

        ApprovalRecord rec = lastOf(records, stepCode);
        if (rec != null) {
            step.setState(ApplyChainVO.StepVO.STATE_DONE);
            // 实际办理人优先于「该谁办」：转交、顶岗都会让这两者不一致
            step.setActorName(name(rec.getApproverId()));
            step.setResult(rec.getResult());
            step.setResultText(resultText(rec.getResult()));
            step.setOpinion(rec.getOpinion());
            step.setAt(fmt(rec.getApprovedAt()));
            step.setWaitHours(hoursFrom(rec.getApprovedAt(), nextAt(records, rec)));
            return step;
        }
        if (waitStatus.equals(apply.getStatus())) {
            step.setState(ApplyChainVO.StepVO.STATE_CURRENT);
            step.setWaitHours(waited < 0 ? null : waited);
            return step;
        }
        if (TripApply.STATUS_REJECTED.equals(apply.getStatus())) {
            step.setState(ApplyChainVO.StepVO.STATE_SKIPPED);
            return step;
        }
        boolean passed = TripApply.STATUS_DEPT_REVIEW.equals(waitStatus)
                ? TripApply.STATUS_FINAL_REVIEW.equals(apply.getStatus())
                || TripApply.STATUS_APPROVED.equals(apply.getStatus())
                || TripApply.STATUS_CLOSED.equals(apply.getStatus())
                : TripApply.STATUS_APPROVED.equals(apply.getStatus())
                || TripApply.STATUS_CLOSED.equals(apply.getStatus());
        step.setState(passed ? ApplyChainVO.StepVO.STATE_DONE : ApplyChainVO.StepVO.STATE_PENDING);
        return step;
    }

    /** 最后一段：通过 / 关闭是终点，驳回则整段跳过 */
    private ApplyChainVO.StepVO finishStep(TripApply apply, List<ApprovalRecord> records) {
        ApplyChainVO.StepVO step = new ApplyChainVO.StepVO();
        step.setStep(ApplyChainVO.STEP_FINISH);
        step.setStepText("完成");

        ApprovalRecord rec = lastOf(records, ApprovalRecord.STEP_CLOSE);
        if (rec != null) {
            step.setState(ApplyChainVO.StepVO.STATE_DONE);
            step.setActorName(name(rec.getApproverId()));
            step.setActorRole("申请人");
            step.setResult(rec.getResult());
            step.setResultText("已关闭归档");
            step.setOpinion(rec.getOpinion());
            step.setAt(fmt(rec.getApprovedAt()));
            return step;
        }
        if (TripApply.STATUS_CLOSED.equals(apply.getStatus())) {
            step.setState(ApplyChainVO.StepVO.STATE_DONE);
            step.setResult(ApprovalRecord.RESULT_CLOSED);
            step.setResultText("已关闭归档");
            return step;
        }
        if (TripApply.STATUS_APPROVED.equals(apply.getStatus())) {
            step.setState(ApplyChainVO.StepVO.STATE_DONE);
            step.setResult(ApprovalRecord.RESULT_APPROVED);
            step.setResultText("已通过，可以出行");
            return step;
        }
        if (TripApply.STATUS_REJECTED.equals(apply.getStatus())) {
            step.setState(ApplyChainVO.StepVO.STATE_SKIPPED);
            return step;
        }
        step.setState(ApplyChainVO.StepVO.STATE_PENDING);
        return step;
    }

    private ApprovalRecord lastOf(List<ApprovalRecord> records, String step) {
        ApprovalRecord found = null;
        for (ApprovalRecord record : records) {
            if (step.equals(record.getStep())) {
                found = record;
            }
        }
        return found;
    }

    /** 这条记录之后第一条留痕的时间，用来算「在这一步待了多久」 */
    private LocalDateTime nextAt(List<ApprovalRecord> records, ApprovalRecord rec) {
        if (rec.getId() == null) {
            return null;
        }
        for (ApprovalRecord record : records) {
            if (record.getId() != null && record.getId() > rec.getId()) {
                return record.getApprovedAt();
            }
        }
        return null;
    }

    /**
     * 从 from 到 to 多少小时。to 为空说明后面再没有动作，算到当下。
     */
    private Long hoursFrom(LocalDateTime from, LocalDateTime to) {
        if (from == null) {
            return null;
        }
        LocalDateTime end = to == null ? LocalDateTime.now() : to;
        long hours = Duration.between(from, end).toHours();
        return hours < 0 ? 0L : hours;
    }

    private int remindCntOf(String applyNo) {
        Long cnt = applyRemindMapper.selectCount(new LambdaQueryWrapper<ApplyRemind>()
                .eq(ApplyRemind::getApplyNo, applyNo));
        return cnt == null ? 0 : cnt.intValue();
    }

    private String deptLeaderName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        Dept dept = deptMapper.selectById(deptId);
        return dept == null ? null : name(dept.getLeaderId());
    }

    private String resultText(String result) {
        if (result == null) {
            return null;
        }
        return switch (result) {
            case ApprovalRecord.RESULT_APPROVED -> "通过";
            case ApprovalRecord.RESULT_REJECTED -> "驳回";
            case ApprovalRecord.RESULT_CLOSED -> "已关闭";
            default -> result;
        };
    }

    private String fmt(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME);
    }

    public TripApply load(String applyNo) {
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, applyNo));
        if (apply == null) {
            throw BusinessException.paramError("申请单不存在：" + applyNo);
        }
        return apply;
    }

    /**
     * 详情可见范围：本人、审过这一单的人、本部门审批人、财务、管理层。
     *
     * <p>「审过这一单」这一条不能省：复审与转交都可能跨部门，
     * 只按部门判的话，复审人打开详情会被挡在门外，而前端是静默处理的——
     * 页面上不报错，只是「恰好没有数据」，比直接报错难查得多。
     */
    public void assertVisible(TripApply apply) {
        SysUser current = UserContext.get();
        if (current == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        if (apply.getUserId() != null && apply.getUserId().equals(current.getId())) {
            return;
        }
        if (accessGuard.broadVisible(current.getRole())) {
            return;
        }
        if (apply.getDeptId() != null && apply.getDeptId().equals(current.getDeptId())
                && SysUser.ROLE_APPROVER.equals(current.getRole())) {
            return;
        }
        if (accessGuard.approvedByMe(apply.getApplyNo(), current.getId())) {
            return;
        }
        throw new BusinessException(ResultCode.NO_PERMISSION, "只能查看本人或本部门的申请");
    }

    public ApplyListItemVO toListItem(TripApply apply) {
        ApplyListItemVO vo = new ApplyListItemVO();
        vo.setApplyNo(apply.getApplyNo());
        vo.setReason(apply.getReason());
        vo.setApplicant(name(apply.getUserId()));
        vo.setDeptName(deptName(apply.getDeptId()));
        vo.setPartnerName(partnerName(apply.getPartnerId()));
        vo.setEstTotal(apply.getEstTotal());
        vo.setStandardResult(apply.getStandardResult());
        vo.setStatus(apply.getStatus());
        vo.setCreatedAt(apply.getCreatedAt() == null ? null : apply.getCreatedAt().format(DATE_TIME));
        fillSla(vo, apply);

        // 同类花费参考：按首段目的地取，列表页就能比，不用点进去
        TripItinerary first = firstItinerary(apply.getApplyNo());
        if (first != null) {
            CostRef ref = costRefService.match(first.getToCity(), apply.getTripType());
            if (ref != null && ref.available()) {
                vo.setAvgAmount(ref.getAvgAmount());
                vo.setMinAmount(ref.getMinAmount());
                vo.setMaxAmount(ref.getMaxAmount());
            }
        }

        // 评价档位
        EvalResult eval = evalResultMapper.selectOne(
                new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getApplyNo, apply.getApplyNo()));
        if (eval != null) {
            vo.setEvalLevel(eval.getLevel());
            vo.setTotalScore(eval.getTotalScore());
        }
        return vo;
    }

    // ---------- 手工关闭 ----------

    /**
     * 员工手工关闭自己的申请单，补的是「批了但没成行、也没费用要报」这一类单的出口。
     *
     * <p>系统里另一个写 CLOSED 的地方是 {@code FinanceService.pay()}——报销付完款才算结案。
     * 但压根没花钱的单永远走不到付款那一步，不改就一直停在 APPROVED：
     * 既占着列表里的位置让人以为还有事没办，月底看数据也分不清「没出差」和「还在途」。
     *
     * <h3>唯一会拒人的是在途报销单</h3>
     * 票还挂着没报就把单据归档，等于把这笔账压到台面底下，往后没人再看得见它。
     * 所以只要名下还有没走完的报销（含被退回待改的），先让人去处理报销。
     *
     * <p>关闭<b>不删任何数据</b>：单据进 CLOSED 之后照样能补票、能回填业务结果，
     * 历史出差次数与费效比照常统计，与付款自动结案那条路径保持同一个口径。
     */
    /**
     * 催办：申请人等不及了，让审批人看见这张单压住了。
     *
     * <p>三件事它都不做：<b>不改状态、不自动流转、不替人下结论</b>。
     * 只写一行记录（待办列表上多一个「已催 N 次」的标记），再给当前环节的审批人推一条站内消息。
     * 消息只做站内，不碰邮件短信——没有外网通道就别假装有，
     * 页面上写着「已通知对方」而对方其实什么都没收到，比不做更糟。
     *
     * <p>节流按「同一人 + 同一单 + 24 小时」：对着一张单连点二十次，
     * 列表上就一片红，审批人反而看不出哪张真的急。
     *
     * @param note 催办时留的一句话，可为空
     */
    public void remind(String applyNo, String note) {
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, applyNo));
        if (apply == null) {
            throw BusinessException.paramError("申请单不存在：" + applyNo);
        }
        Long me = UserContext.currentId();
        if (apply.getUserId() == null || !apply.getUserId().equals(me)) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "只能催自己提交的申请");
        }
        if (!TripApply.STATUS_DEPT_REVIEW.equals(apply.getStatus())
                && !TripApply.STATUS_FINAL_REVIEW.equals(apply.getStatus())) {
            throw BusinessException.statusForbidden("这张单现在不在审批中，不用催");
        }

        int interval = Math.max(1, slaConfig.getRemindIntervalHours());
        LocalDateTime earliest = LocalDateTime.now().minusHours(interval);
        Long recent = applyRemindMapper.selectCount(new LambdaQueryWrapper<ApplyRemind>()
                .eq(ApplyRemind::getApplyNo, applyNo)
                .eq(ApplyRemind::getFromUserId, me)
                .ge(ApplyRemind::getRemindAt, earliest));
        if (recent != null && recent > 0) {
            throw BusinessException.statusForbidden(
                    "已经催过了，" + interval + " 小时之内不能重复催");
        }

        ApplyRemind row = new ApplyRemind();
        row.setApplyNo(applyNo);
        row.setStep(apply.getStatus());
        row.setFromUserId(me);
        row.setNote(note == null || note.isBlank() ? null : note.trim());
        applyRemindMapper.insert(row);

        // 推给此刻该动手的人：初审找部门 leader，复审找全部管理层
        String who = name(me);
        String body = (note == null || note.isBlank())
                ? who + " 催了一下这张单"
                : who + " 催办：" + note.trim();
        messageService.sendTo(receiversOfStep(apply),
                SysMessage.TYPE_APPLY_REMIND, SysMessage.BIZ_APPLY, applyNo,
                "申请 " + applyNo + " 待你审批", body);
    }

    /**
     * 这一步该由谁处理，就给谁发。
     *
     * <p>初审找 {@code dept.leader_id}——与 {@code ApprovalService} 判「我是不是初审人」
     * 用的是同一个字段，两边对不上就会出现「催了不该催的人」。
     *
     * <p>复审环节找全部管理层：复审权不挂在某个部门上，
     * 任何一个管理层都能批，所以消息也得发给所有人，只发一个等于押注谁有空。
     */
    private List<Long> receiversOfStep(TripApply apply) {
        if (TripApply.STATUS_DEPT_REVIEW.equals(apply.getStatus())) {
            Dept dept = apply.getDeptId() == null ? null : deptMapper.selectById(apply.getDeptId());
            return dept == null || dept.getLeaderId() == null ? List.of() : List.of(dept.getLeaderId());
        }
        if (TripApply.STATUS_FINAL_REVIEW.equals(apply.getStatus())) {
            List<SysUser> managers = sysUserMapper.selectList(
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, SysUser.ROLE_MANAGER));
            List<Long> ids = new ArrayList<>();
            for (SysUser user : managers) {
                ids.add(user.getId());
            }
            return ids;
        }
        return List.of();
    }

    /** 这张单被催过几次。给待办列表用，取不到就当 0 次 */
    public Map<String, Long> remindCounts(List<String> applyNos) {
        Map<String, Long> out = new HashMap<>();
        if (applyNos == null || applyNos.isEmpty()) {
            return out;
        }
        try {
            for (ApplyRemindMapper.RemindCount c : applyRemindMapper.countBy(applyNos)) {
                out.put(c.getApplyNo(), c.getCnt() == null ? 0L : c.getCnt());
            }
        } catch (Exception e) {
            log.warn("催办次数查询失败，列表按没人催过显示：{}", e.getMessage());
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public void close(String applyNo) {
        TripApply apply = load(applyNo);
        if (!apply.getUserId().equals(UserContext.currentId())) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "只能关闭自己的出差申请");
        }
        // 已经是 CLOSED 就算成功返回：重复点、或者列表与抽屉两处入口前后脚触发都不该报错
        if (TripApply.STATUS_CLOSED.equals(apply.getStatus())) {
            return;
        }
        if (!TripApply.STATUS_APPROVED.equals(apply.getStatus())) {
            throw BusinessException.statusForbidden("只有已通过的申请单才能手工关闭");
        }

        Long openCnt = expenseReportMapper.selectCount(new LambdaQueryWrapper<ExpenseReport>()
                .eq(ExpenseReport::getApplyNo, applyNo)
                .in(ExpenseReport::getStatus, OPEN_EXPENSE_STATUS));
        if (openCnt != null && openCnt > 0) {
            throw BusinessException.statusForbidden(
                    "这张单还有 " + openCnt + " 张报销单没走完，请先把报销处理完再关闭");
        }

        apply.setStatus(TripApply.STATUS_CLOSED);
        apply.setStatusAt(LocalDateTime.now());
        tripApplyMapper.updateById(apply);
        writeCloseRecord(apply);
    }

    /**
     * 填时限相关三列：进入当前状态的时间、是否超时、超时多久。
     *
     * <p>只给在审环节计时（部门审批 / 复审）。草稿是员工自己的事，
     * 驳回之后球在员工手上，这两类催审批人没有意义；已通过、已关闭更没有。
     *
     * <p>statusAt 为 NULL（加这一列之前的老数据）时三列全留空，
     * 页面显示横杠——拿提交时间去算「压了几天」，算出来的是假数字。
     */
    private void fillSla(ApplyListItemVO vo, TripApply apply) {
        vo.setRemindCnt(0);
        if (apply.getStatusAt() == null) {
            return;
        }
        vo.setStatusAt(apply.getStatusAt().format(DATE_TIME));
        int limit = slaConfig.hoursOf(apply.getStatus());
        if (limit <= 0) {
            return;
        }
        long waited = java.time.Duration.between(apply.getStatusAt(), LocalDateTime.now()).toHours();
        boolean over = waited > limit;
        vo.setOverdue(over);
        vo.setOverdueHours(over ? waited - limit : 0L);
    }

    // ---------- 内部 ----------

    /**
     * 尚未完结的报销单状态。
     *
     * <p>被退回（RETURNED）也算在途：单据还压在员工手上等着改，
     * 这时候关申请，等于把一笔还没修好的账先盖上。只有 PAID 才算真的走完了。
     */
    private static final List<String> OPEN_EXPENSE_STATUS = List.of(
            ExpenseReport.STATUS_PENDING,
            ExpenseReport.STATUS_APPROVED,
            ExpenseReport.STATUS_PASS_WITH_ISSUE,
            ExpenseReport.STATUS_RETURNED);

    private ApplySubmitResultVO save(ApplySubmitRequest request, String status) {
        boolean draft = TripApply.STATUS_DRAFT.equals(status);
        // 草稿走宽松版：行程段允许没填完，甚至一段都没有。完整性交给提交那一道
        if (draft) {
            checkItinerariesLenient(request.getItineraries());
        } else {
            checkItineraries(request.getItineraries());
        }

        // 续写时复用原单；新建时才申请号。两处的差别只在这一个对象上
        TripApply reused = resolveReusable(request);
        TripApply apply = reused != null ? reused : new TripApply();
        String applyNo = reused != null ? reused.getApplyNo() : applyNoGenerator.nextApplyNo();

        // 金额三个列是 NOT NULL，草稿阶段没填就按 0 落，别让「还没估价」变成存不进去
        BigDecimal traffic = request.getEstTraffic() == null ? BigDecimal.ZERO : request.getEstTraffic();
        BigDecimal hotel = request.getEstHotel() == null ? BigDecimal.ZERO : request.getEstHotel();

        apply.setApplyNo(applyNo);
        apply.setUserId(UserContext.currentId());
        apply.setDeptId(UserContext.get() == null ? null : UserContext.get().getDeptId());
        apply.setReason(request.getReason());
        apply.setPartnerId(request.getPartnerId());
        apply.setTripType(request.getTripType());
        apply.setTripTypeNote(request.getTripTypeNote());
        apply.setEstTraffic(traffic);
        apply.setEstHotel(hotel);
        // 预估合计以后端重算为准，不认前端传的值（设计报告 5.6）
        apply.setEstTotal(traffic.add(hotel));
        apply.setExceedReason(request.getExceedReason());
        apply.setStatus(status);
        apply.setStatusAt(LocalDateTime.now());

        // 行程段采用「整删整插」：草稿里删了哪一段、调了顺序，前端只给最终态，
        // 逐段比对更新不划算，也容易留下改不干净的旧段
        List<TripItinerary> itineraries = buildItineraries(applyNo, request);
        if (reused != null) {
            tripItineraryMapper.delete(new LambdaQueryWrapper<TripItinerary>()
                    .eq(TripItinerary::getApplyNo, applyNo));
        }
        if (TripApply.STATUS_DRAFT.equals(status)) {
            apply.setStandardResult(null);
        } else {
            BigDecimal cap = standardService.capTotal(itineraries);
            apply.setStandardResult(standardService.judge(apply.getEstTotal(), cap));
            if (TripApply.STANDARD_EXCEED.equals(apply.getStandardResult())
                    && !StringUtils.hasText(request.getExceedReason())) {
                throw new BusinessException(ResultCode.EXCEED_REASON_REQUIRED,
                        "预估合计 " + apply.getEstTotal() + " 元超出差标上限 " + cap + " 元 10% 以上，请填写超标说明");
            }
        }
        if (reused != null) {
            tripApplyMapper.updateById(apply);
        } else {
            tripApplyMapper.insert(apply);
        }
        for (TripItinerary item : itineraries) {
            tripItineraryMapper.insert(item);
        }

        ApplySubmitResultVO vo = new ApplySubmitResultVO();
        vo.setApplyNo(applyNo);
        vo.setStatus(status);
        vo.setEstTotal(apply.getEstTotal());
        vo.setStandardResult(apply.getStandardResult());
        vo.setExceedReason(apply.getExceedReason());

        if (!TripApply.STATUS_DRAFT.equals(status)) {
            // 超标说明已经进了申请单，草稿清掉；存草稿那一次不动它，下次进来还要接着写
            fieldDraftService.clear(FieldDraft.BIZ_APPLY_SUBMIT, applyNo, FieldDraft.FIELD_EXCEED_REASON);
            vo.setStandardHints(standardService.hints(itineraries, apply.getEstTotal()));
            evalService.evaluateAndSave(applyNo, request);
            EvalResultVO preview = evalService.get(applyNo);
            vo.setEvalPreview(preview);
            writeSubmitRecord(apply);
        }
        return vo;
    }

    /**
     * 判断这次能不能落在已有单据上。
     *
     * <p>只有「本人 + DRAFT」可以原地改写。其余一律报错而不是静默新建——
     * 静默新建会让员工以为在同一个单上改，実際是又生成一张，比报错更难发现。
     *
     * <p>被驳回的单要走 A-05 修改重提（新申请号、老单留档），不能在这里复用，
     * 否则审批流转记录会被抹掉，「批有依据、拒有理由」的证据链就断了。
     */
    private TripApply resolveReusable(ApplySubmitRequest request) {
        if (!StringUtils.hasText(request.getApplyNo())) {
            return null;
        }
        TripApply exist = tripApplyMapper.selectOne(new LambdaQueryWrapper<TripApply>()
                .eq(TripApply::getApplyNo, request.getApplyNo().trim()));
        if (exist == null) {
            return null;
        }
        Long currentId = UserContext.currentId();
        if (currentId == null || !Objects.equals(exist.getUserId(), currentId)) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "这张申请不是你的，不能修改");
        }
        if (!TripApply.STATUS_DRAFT.equals(exist.getStatus())) {
            throw BusinessException.statusForbidden(
                    "这张申请已进入审批流程（状态 " + exist.getStatus() + "），不能再改动内容");
        }
        return exist;
    }

    /**
     * 提交动作也写一条留痕，流转记录看起来才完整（提交 → 初审 → 复审）。
     */
    private void writeSubmitRecord(TripApply apply) {
        ApprovalRecord record = new ApprovalRecord();
        record.setApplyNo(apply.getApplyNo());
        record.setApproverId(apply.getUserId());
        record.setStep(ApprovalRecord.STEP_SUBMIT);
        record.setResult(ApprovalRecord.RESULT_APPROVED);
        record.setOpinion("提交申请");
        record.setSnapshot(null);
        approvalRecordMapper.insert(record);
    }

    /**
     * 手工关闭的留痕。
     *
     * <p>与提交那条留痕同一类写法：不是审批结论，而是「谁在什么时候动了这张单」。
     * 单据状态从 APPROVED 落到 CLOSED 是台账级变动，事后有人问起来得查得到。
     * 这里不存快照——关闭那一刻没有参考价值需要固化，真要复盘有 Evaluation 那张表。
     */
    private void writeCloseRecord(TripApply apply) {
        ApprovalRecord record = new ApprovalRecord();
        record.setApplyNo(apply.getApplyNo());
        record.setApproverId(UserContext.currentId());
        record.setStep(ApprovalRecord.STEP_CLOSE);
        record.setResult(ApprovalRecord.RESULT_CLOSED);
        record.setOpinion("员工手工关闭，名下没有在途报销单");
        record.setSnapshot(null);
        approvalRecordMapper.insert(record);
    }

    private void checkItineraries(List<ApplySubmitRequest.ItineraryItem> items) {
        if (items == null || items.isEmpty()) {
            throw BusinessException.paramError("至少填写一段行程");
        }
        for (ApplySubmitRequest.ItineraryItem item : items) {
            LocalDate start = parseDate(item.getStartDate());
            LocalDate end = parseDate(item.getEndDate());
            if (start == null || end == null) {
                throw BusinessException.paramError("行程日期格式不正确，应为 yyyy-MM-dd");
            }
            if (end.isBefore(start)) {
                throw BusinessException.paramError("返程日期不能早于出发日期");
            }
        }
    }

    /**
     * 草稿阶段的行程段校验：只管一件事——两个日期都填了，就检查返程不早于出发。
     *
     * <p>其余一律放行：事由没写、对象没选、一段行程都还没加，都得能存成草稿，
     * 不然「先存一下回头再填」这个动作本身就做不到。
     * 日期写成 2026/09/20 这种解析不出来的，按没填处理——回显时空着让人重选，
     * 比在保存这一步弹错误、把人堵在页面上强。
     */
    private void checkItinerariesLenient(List<ApplySubmitRequest.ItineraryItem> items) {
        if (items == null) {
            return;
        }
        for (ApplySubmitRequest.ItineraryItem item : items) {
            LocalDate start = parseDate(item.getStartDate());
            LocalDate end = parseDate(item.getEndDate());
            if (start != null && end != null && end.isBefore(start)) {
                throw BusinessException.paramError("返程日期不能早于出发日期");
            }
        }
    }

    private List<TripItinerary> buildItineraries(String applyNo, ApplySubmitRequest request) {
        List<TripItinerary> result = new ArrayList<>();
        if (request.getItineraries() == null) {
            return result;
        }
        int seq = 1;
        for (ApplySubmitRequest.ItineraryItem item : request.getItineraries()) {
            TripItinerary itinerary = new TripItinerary();
            itinerary.setApplyNo(applyNo);
            itinerary.setSeq(item.getSeq() == null ? seq : item.getSeq());
            itinerary.setStartDate(parseDate(item.getStartDate()));
            itinerary.setEndDate(parseDate(item.getEndDate()));
            itinerary.setFromCity(item.getFromCity());
            itinerary.setToCity(item.getToCity());
            itinerary.setTrafficType(item.getTrafficType());
            itinerary.setCityLevel(StringUtils.hasText(item.getCityLevel())
                    ? item.getCityLevel()
                    : standardService.cityLevelOf(item.getToCity()));
            result.add(itinerary);
            seq++;
        }
        return result;
    }

    private TripItinerary firstItinerary(String applyNo) {
        List<TripItinerary> list = tripItineraryMapper.selectList(
                new LambdaQueryWrapper<TripItinerary>()
                        .eq(TripItinerary::getApplyNo, applyNo)
                        .orderByAsc(TripItinerary::getSeq)
                        .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private String name(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : user.getRealName();
    }

    private String deptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        Dept dept = deptMapper.selectById(deptId);
        return dept == null ? null : dept.getDeptName();
    }

    private String partnerName(Long partnerId) {
        if (partnerId == null) {
            return null;
        }
        Partner partner = partnerMapper.selectById(partnerId);
        return partner == null ? null : partner.getPartnerName();
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 差标明细给审批页复用，见 {@link ApprovalViewVO.StandardHintVO}。
     */
    public List<ApprovalViewVO.StandardHintVO> standardHints(String applyNo) {
        TripApply apply = load(applyNo);
        List<TripItinerary> itineraries = tripItineraryMapper.selectList(
                new LambdaQueryWrapper<TripItinerary>().eq(TripItinerary::getApplyNo, applyNo));
        return standardService.hints(itineraries, apply.getEstTotal());
    }
}
