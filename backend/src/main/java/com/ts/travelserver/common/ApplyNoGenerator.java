package com.ts.travelserver.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.entity.ExpenseReport;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.mapper.ExpenseReportMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 单号生成器。
 *
 * <p>申请号：TA + yyyyMMdd + 4 位当日序号，例如 TA202609190042（设计报告 6.3.2）。
 * 报销单号同理，前缀换 ER。
 *
 * <p>做法是取当天已有单号的最大值 +1。并发下可能撞唯一索引，
 * 所以插入失败要重试一次——一个月工期的项目不做分布式发号器（设计报告 2.3）。
 */
@Component
@RequiredArgsConstructor
public class ApplyNoGenerator {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TripApplyMapper tripApplyMapper;
    private final ExpenseReportMapper expenseReportMapper;

    /**
     * 生成申请号，如 TA202609210001。
     */
    public String nextApplyNo() {
        String prefix = "TA" + LocalDate.now().format(YMD);
        List<TripApply> list = tripApplyMapper.selectList(new LambdaQueryWrapper<TripApply>()
                .likeRight(TripApply::getApplyNo, prefix)
                .orderByDesc(TripApply::getApplyNo)
                .last("limit 1"));
        return prefix + seq(list.isEmpty() ? null : list.get(0).getApplyNo(), prefix);
    }

    /**
     * 生成报销单号，如 ER202609210001。
     */
    public String nextReportNo() {
        String prefix = "ER" + LocalDate.now().format(YMD);
        List<ExpenseReport> list = expenseReportMapper.selectList(new LambdaQueryWrapper<ExpenseReport>()
                .likeRight(ExpenseReport::getReportNo, prefix)
                .orderByDesc(ExpenseReport::getReportNo)
                .last("limit 1"));
        return prefix + seq(list.isEmpty() ? null : list.get(0).getReportNo(), prefix);
    }

    /**
     * 从已有最大单号里截出流水号并 +1，当天还没开单就从 1 开始。
     */
    private String seq(String maxNo, String prefix) {
        int next = 1;
        if (maxNo != null && maxNo.length() == prefix.length() + 4) {
            try {
                next = Integer.parseInt(maxNo.substring(prefix.length())) + 1;
            } catch (NumberFormatException e) {
                next = 1;
            }
        }
        return String.format("%04d", next);
    }
}
