package com.ts.travelserver.ai;

import com.ts.travelserver.vo.AiPointVO;
import com.ts.travelserver.vo.MonthlyReportVO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 月报结论生成（F-01 的一段人话总结）。
 *
 * <p>刻意不进模型：结论里的每个数字都来自月报自己算出来的统计，
 * 让模型复述一遍唯一的收益是措辞好听一点，代价是不可复现 + 可能把数字说错。
 * 「数字来自统计、AI 只做表述」这条口径要守住，所以这里用模板拼——
 * 想让它更好听，将来只改这个类的措辞，不动数据。
 *
 * <p>排序口径：费效比 = 投入 ÷ 产出，<b>越低越划算</b>，所以升序排，
 * 头是最好、尾是最差。按降序排会把最差的当成最好的推荐出去。
 */
@Component
public class MonthlyConclusionBuilder {

    /** 费效比超过这个值就算花得多产出少，与月报页面的色阶一致 */
    private static final BigDecimal WORST_THRESHOLD = new BigDecimal("0.45");

    public MonthlyReportVO.ConclusionVO build(List<MonthlyReportVO.DeptRowVO> rows) {
        MonthlyReportVO.ConclusionVO vo = new MonthlyReportVO.ConclusionVO();
        if (rows == null || rows.isEmpty()) {
            vo.setHighlights(new ArrayList<>());
            vo.setText("本月没有可统计的出差数据。");
            return vo;
        }

        List<MonthlyReportVO.DeptRowVO> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(MonthlyConclusionBuilder::sortKey));

        MonthlyReportVO.DeptRowVO best = sorted.get(0);
        MonthlyReportVO.DeptRowVO worst = sorted.get(sorted.size() - 1);

        List<AiPointVO> highlights = new ArrayList<>();
        /*
         * 产出没回填的部门比值记 0，按升序排会排到最前面，被说成"费效比最好"——
         * 那是最不该被表扬的一类：钱花了、结果没填。这里单独判一下，宁可少说一句。
         */
        if (blankOutput(best)) {
            highlights.add(AiPointVO.of("mid",
                    name(best) + " 本月有投入但没回填产出，暂时比不出哪个部门划算"));
        } else {
            highlights.add(AiPointVO.of("low",
                    name(best) + " 费效比最好（" + ratio(best) + "），投入产出最划算"));
        }
        if (sorted.size() > 1) {
            boolean bad = worst.getRatio() != null && worst.getRatio().compareTo(WORST_THRESHOLD) > 0;
            highlights.add(AiPointVO.of(bad ? "high" : "mid",
                    name(worst) + " 费效比最差（" + ratio(worst) + "），"
                            + "花得多产出少，下次出差前建议先看历史产出"));
        }
        vo.setHighlights(highlights);
        vo.setText(highlights.stream()
                .map(AiPointVO::getText)
                .reduce((a, b) -> a + "；" + b)
                .orElse("") + "。");
        return vo;
    }

    /** 排序键：产出为 0 的部门排到最后（那不是划算，是没填结果） */
    private static BigDecimal sortKey(MonthlyReportVO.DeptRowVO row) {
        if (blankOutput(row)) {
            return new BigDecimal("999");
        }
        return row.getRatio() == null ? BigDecimal.ZERO : row.getRatio();
    }

    private static boolean blankOutput(MonthlyReportVO.DeptRowVO row) {
        return row.getTotalOutput() == null || row.getTotalOutput().compareTo(BigDecimal.ZERO) <= 0;
    }

    private String name(MonthlyReportVO.DeptRowVO row) {
        return row.getDeptName() == null || row.getDeptName().isBlank() ? "未分配部门" : row.getDeptName();
    }

    private String ratio(MonthlyReportVO.DeptRowVO row) {
        return row.getRatio() == null ? "—" : row.getRatio().toPlainString();
    }
}
