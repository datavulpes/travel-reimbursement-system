package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.entity.Dept;
import com.ts.travelserver.entity.LoanRecord;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.mapper.DeptMapper;
import com.ts.travelserver.mapper.LoanRecordMapper;
import com.ts.travelserver.vo.LoanVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预借款（loan_record）。
 *
 * <p>（applyNo, userId）联合唯一，一张申请一笔预借（BR-04）。
 * 复审通过的同事务内按「预估合计与部门月度预算取小者」生成，
 * 报销付款时先冲抵预借、只付差额，一笔预借对一笔付款，杜绝交叉冲抵（FR-0305）。
 */
@Service
@RequiredArgsConstructor
public class LoanService {

    private final LoanRecordMapper loanRecordMapper;
    private final DeptMapper deptMapper;

    /**
     * D-01：查某单的预借额度。
     */
    public LoanVO get(String applyNo) {
        LoanRecord record = load(applyNo);
        if (record == null) {
            throw BusinessException.paramError("该申请还没有预借款");
        }
        return toVO(record);
    }

    /**
     * 取预借记录，没有返回 null（报销单可能本来就没预借）。
     */
    public LoanRecord load(String applyNo) {
        List<LoanRecord> list = loanRecordMapper.selectList(
                new LambdaQueryWrapper<LoanRecord>().eq(LoanRecord::getApplyNo, applyNo));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 复审通过时生成预借款。额度取「预估合计与部门月度预算的较小值」（BR-04）。
     *
     * <p>已经有预借就不重复生成——A-05 驳回后重新提交走新申请号，不会撞上。
     */
    public LoanRecord create(TripApply apply) {
        if (load(apply.getApplyNo()) != null) {
            return null;
        }
        BigDecimal estTotal = apply.getEstTotal() == null ? BigDecimal.ZERO : apply.getEstTotal();
        BigDecimal budget = BigDecimal.ZERO;
        if (apply.getDeptId() != null) {
            Dept dept = deptMapper.selectById(apply.getDeptId());
            if (dept != null && dept.getMonthlyBudget() != null) {
                budget = dept.getMonthlyBudget();
            }
        }
        BigDecimal amount = budget.compareTo(BigDecimal.ZERO) <= 0
                ? estTotal
                : estTotal.min(budget);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        LoanRecord record = new LoanRecord();
        record.setApplyNo(apply.getApplyNo());
        record.setUserId(apply.getUserId());
        record.setAmount(amount);
        record.setStatus(LoanRecord.STATUS_ACTIVE);
        record.setOffsetAmount(BigDecimal.ZERO);
        loanRecordMapper.insert(record);
        return record;
    }

    /**
     * 付款时冲抵：累加已冲抵金额，冲完置为已结清。
     */
    public void offset(Long loanId, BigDecimal amount) {
        LoanRecord record = loanRecordMapper.selectById(loanId);
        if (record == null) {
            throw BusinessException.paramError("预借款不存在");
        }
        BigDecimal offset = record.getOffsetAmount() == null ? BigDecimal.ZERO : record.getOffsetAmount();
        record.setOffsetAmount(offset.add(amount));
        record.setStatus(record.remainAmount().compareTo(BigDecimal.ZERO) <= 0
                ? LoanRecord.STATUS_SETTLED
                : LoanRecord.STATUS_OFFSET);
        loanRecordMapper.updateById(record);
    }

    public LoanVO toVO(LoanRecord record) {
        LoanVO vo = new LoanVO();
        vo.setId(record.getId());
        vo.setApplyNo(record.getApplyNo());
        vo.setUserId(record.getUserId());
        vo.setAmount(record.getAmount());
        vo.setOffsetAmount(record.getOffsetAmount());
        vo.setRemainAmount(record.remainAmount());
        vo.setStatus(record.getStatus());
        vo.setCreatedAt(record.getCreatedAt());
        return vo;
    }
}
