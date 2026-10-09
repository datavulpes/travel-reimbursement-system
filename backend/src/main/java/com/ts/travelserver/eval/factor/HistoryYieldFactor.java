package com.ts.travelserver.eval.factor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.entity.BizResult;
import com.ts.travelserver.entity.EvalResult;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.eval.EvalContext;
import com.ts.travelserver.eval.EvalFactor;
import com.ts.travelserver.mapper.BizResultMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * F1 历史产出率（权重 0.35）。
 *
 * <p>算法（设计报告 6.7 表 21）：该对象历史回填里「有效产出次数 ÷ 历史出差次数」，
 * 直接换算成百分比；没有历史记录时取中性分 60，既不奖励也不惩罚。
 *
 * <p>有效产出 = 回填结果不是「无进展」。历史出差只数已通过（APPROVED）与已关闭（CLOSED）的单，
 * 草稿和审批中的不算数——还没成行，谈不上产出。
 */
@Component
@RequiredArgsConstructor
public class HistoryYieldFactor extends EvalFactor {

    private final TripApplyMapper tripApplyMapper;
    private final BizResultMapper bizResultMapper;

    /** 无历史数据时的中性分 */
    @Value("${travel.eval.default-history-score:60}")
    private double defaultScore;

    /** 权重 */
    @Value("${travel.eval.weight.history:0.35}")
    private double weight;

    @Override
    public String key() {
        return EvalResult.KEY_F_HISTORY;
    }

    @Override
    public String name() {
        return "历史产出率";
    }

    @Override
    public BigDecimal weight() {
        return BigDecimal.valueOf(weight);
    }

    @Override
    public EvalScore calc(EvalContext ctx) {
        BigDecimal neutral = BigDecimal.valueOf(defaultScore);
        if (ctx.getPartnerId() == null) {
            return new EvalScore(neutral, "未关联往来对象，取中性分 " + defaultScore);
        }

        long tripCnt = count(tripApplyMapper.selectCount(new LambdaQueryWrapper<TripApply>()
                .eq(TripApply::getPartnerId, ctx.getPartnerId())
                .ne(ctx.getApplyNo() != null, TripApply::getApplyNo, ctx.getApplyNo())
                .in(TripApply::getStatus, List.of(TripApply.STATUS_APPROVED, TripApply.STATUS_CLOSED))));

        if (tripCnt == 0) {
            return new EvalScore(neutral, "该对象暂无历史出差记录，取中性分 " + defaultScore);
        }

        long okCnt = count(bizResultMapper.selectCount(new LambdaQueryWrapper<BizResult>()
                .eq(BizResult::getPartnerId, ctx.getPartnerId())
                .ne(BizResult::getResultType, BizResult.TYPE_NO_PROGRESS)));

        BigDecimal rate = BigDecimal.valueOf(okCnt)
                .divide(BigDecimal.valueOf(tripCnt), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return new EvalScore(rate, "该对象历史 " + tripCnt + " 趟，有效产出 " + okCnt
                + " 次，产出率 " + rate.setScale(1, RoundingMode.HALF_UP) + "%");
    }

    private long count(Long value) {
        return value == null ? 0L : value;
    }
}
