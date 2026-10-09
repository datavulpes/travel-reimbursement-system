package com.ts.travelserver.eval.factor;

import com.ts.travelserver.entity.EvalResult;
import com.ts.travelserver.entity.Partner;
import com.ts.travelserver.eval.EvalContext;
import com.ts.travelserver.eval.EvalFactor;
import com.ts.travelserver.mapper.PartnerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * F2 成本占对象产出比（权重 0.30）。
 *
 * <p>算法（设计报告 6.7 表 21）：本次成本 ÷ 该对象累计产出。
 * ≤5% 得 100 分，线性递减，≥50% 得 0 分；对象还没有产出记录时，
 * 分母为 0 没法算比例，改成按本次成本绝对值分档。
 *
 * <p>直白讲：为了一个年产出百万的客户花三千块出差，和为一年没下单的客户花三千，
 * 后者更该被问一句。
 */
@Component
@RequiredArgsConstructor
public class CostRatioFactor extends EvalFactor {

    /** 成本占产出比 ≤ 这个值得满分 */
    private static final BigDecimal BEST_RATIO = new BigDecimal("0.05");
    /** 成本占产出比 ≥ 这个值得零分 */
    private static final BigDecimal WORST_RATIO = new BigDecimal("0.50");

    private final PartnerMapper partnerMapper;

    @Value("${travel.eval.weight.cost:0.30}")
    private double weight;

    @Override
    public String key() {
        return EvalResult.KEY_F_COST;
    }

    @Override
    public String name() {
        return "成本占对象产出比";
    }

    @Override
    public BigDecimal weight() {
        return BigDecimal.valueOf(weight);
    }

    @Override
    public EvalScore calc(EvalContext ctx) {
        BigDecimal cost = ctx.getEstTotal() == null ? BigDecimal.ZERO : ctx.getEstTotal();
        Partner partner = ctx.getPartner() != null && ctx.getPartnerId() != null
                && ctx.getPartner().getId().equals(ctx.getPartnerId())
                ? ctx.getPartner()
                : (ctx.getPartnerId() == null ? null : partnerMapper.selectById(ctx.getPartnerId()));

        BigDecimal output = partner == null || partner.getTotalOutput() == null
                ? BigDecimal.ZERO
                : partner.getTotalOutput();

        if (output.compareTo(BigDecimal.ZERO) <= 0) {
            return scoreByAmount(cost);
        }

        BigDecimal ratio = cost.divide(output, 6, RoundingMode.HALF_UP);
        BigDecimal score;
        if (ratio.compareTo(BEST_RATIO) <= 0) {
            score = BigDecimal.valueOf(100);
        } else if (ratio.compareTo(WORST_RATIO) >= 0) {
            score = BigDecimal.ZERO;
        } else {
            // 在 5%~50% 之间线性递减：100 × (0.5 − ratio) ÷ 0.45
            score = WORST_RATIO.subtract(ratio)
                    .divide(WORST_RATIO.subtract(BEST_RATIO), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
        return new EvalScore(score, "本次预估 " + cost.setScale(2, RoundingMode.HALF_UP)
                + " 元，占该对象累计产出 " + ratio.multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP) + "%");
    }

    /**
     * 对象没有产出记录时的兜底：按本次成本的绝对值分档。
     */
    private EvalScore scoreByAmount(BigDecimal cost) {
        BigDecimal amount = cost == null ? BigDecimal.ZERO : cost;
        BigDecimal score;
        if (amount.compareTo(new BigDecimal("1000")) <= 0) {
            score = BigDecimal.valueOf(90);
        } else if (amount.compareTo(new BigDecimal("3000")) <= 0) {
            score = BigDecimal.valueOf(70);
        } else if (amount.compareTo(new BigDecimal("5000")) <= 0) {
            score = BigDecimal.valueOf(50);
        } else if (amount.compareTo(new BigDecimal("10000")) <= 0) {
            score = BigDecimal.valueOf(30);
        } else {
            score = BigDecimal.valueOf(10);
        }
        return new EvalScore(score, "该对象暂无产出记录，按本次预估 " + amount.setScale(2, RoundingMode.HALF_UP)
                + " 元绝对值分档");
    }
}
