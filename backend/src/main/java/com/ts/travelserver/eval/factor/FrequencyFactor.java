package com.ts.travelserver.eval.factor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.entity.EvalResult;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.eval.EvalContext;
import com.ts.travelserver.eval.EvalFactor;
import com.ts.travelserver.mapper.TripApplyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * F3 频次异常度（权重 0.20）。
 *
 * <p>算法（设计报告 6.7 表 21）：距上次同对象出差的间隔，相对正常节奏（默认 30 天）的偏离度分档。
 * 间隔越短越异常，得分越低。首次出差不扣分。
 *
 * <p>档位：≥30 天 100 分（正常节奏）；15~29 天 70 分；7~14 天 45 分；＜7 天 20 分。
 * 30 天内第 3 次及以上去同一对象，引擎还会额外给一条事前提示。
 */
@Component
@RequiredArgsConstructor
public class FrequencyFactor extends EvalFactor {

    private final TripApplyMapper tripApplyMapper;

    /** 正常出差节奏（天） */
    @Value("${travel.eval.freq-window-days:30}")
    private long windowDays;

    @Value("${travel.eval.weight.freq:0.20}")
    private double weight;

    @Override
    public String key() {
        return EvalResult.KEY_F_FREQ;
    }

    @Override
    public String name() {
        return "频次异常度";
    }

    @Override
    public BigDecimal weight() {
        return BigDecimal.valueOf(weight);
    }

    @Override
    public EvalScore calc(EvalContext ctx) {
        if (ctx.getPartnerId() == null) {
            return new EvalScore(BigDecimal.valueOf(100), "未关联往来对象，不做频次判断");
        }

        List<TripApply> history = tripApplyMapper.selectList(new LambdaQueryWrapper<TripApply>()
                .eq(TripApply::getPartnerId, ctx.getPartnerId())
                .ne(ctx.getApplyNo() != null, TripApply::getApplyNo, ctx.getApplyNo())
                .orderByDesc(TripApply::getCreatedAt)
                .last("limit 1"));

        if (history.isEmpty()) {
            return new EvalScore(BigDecimal.valueOf(100), "首次去该对象，无频次可比");
        }

        LocalDateTime lastAt = history.get(0).getCreatedAt();
        long gap = lastAt == null
                ? windowDays
                : ChronoUnit.DAYS.between(lastAt.toLocalDate(), LocalDate.now());

        BigDecimal score;
        if (gap >= 30) {
            score = BigDecimal.valueOf(100);
        } else if (gap >= 15) {
            score = BigDecimal.valueOf(70);
        } else if (gap >= 7) {
            score = BigDecimal.valueOf(45);
        } else {
            score = BigDecimal.valueOf(20);
        }
        return new EvalScore(score, "距上次去该对象 " + gap + " 天，正常节奏参考 " + windowDays + " 天");
    }
}
