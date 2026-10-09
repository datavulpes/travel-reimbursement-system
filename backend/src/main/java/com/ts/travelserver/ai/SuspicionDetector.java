package com.ts.travelserver.ai;

import com.ts.travelserver.entity.Invoice;
import com.ts.travelserver.vo.AiPointVO;
import com.ts.travelserver.vo.AiSuspicionVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可疑模式识别（E-02 的补充，不是替代）。
 *
 * <p>四维比对抓的是「不一致」：人不对、日期不在行程里、金额对不上。
 * 这里抓的是「太巧了」：同一天同一家店开了好几张、发票号连号、单张金额显著偏离本单均值。
 * 两者互补，缺一个都会漏。
 *
 * <p>三条规则全是确定性判断，不进模型——它们本来就是数数与比较，
 * 交给我模型只会多花钱还可能漏报。
 *
 * <p>只提示，不判违规：拆票、连号在真实业务里都可能有正当理由，
 * 系统没有权力替业务认定这张票该不该报（BR-06）。
 */
@Slf4j
@Component
public class SuspicionDetector {

    /** 单张金额超过本单均值这个倍数就提一句 */
    private static final BigDecimal BIG_FACTOR = new BigDecimal("3");

    public AiSuspicionVO detect(String reportNo, List<Invoice> invoices) {
        AiSuspicionVO vo = new AiSuspicionVO();
        vo.setReportNo(reportNo);
        vo.setLocal(true);
        vo.setInvoiceCnt(invoices == null ? 0 : invoices.size());

        List<Invoice> valid = new ArrayList<>();
        if (invoices != null) {
            for (Invoice item : invoices) {
                if (item != null && item.getInvoiceNo() != null && !item.getInvoiceNo().isBlank()) {
                    valid.add(item);
                }
            }
        }
        if (valid.isEmpty()) {
            vo.getPoints().add(AiPointVO.of("low", "本单还没有可分析的票据，上传后这里会自动核对"));
            vo.setSummary("暂无结论");
            return vo;
        }

        vo.getPoints().addAll(sameDaySameSeller(valid));
        vo.getPoints().addAll(sequentialNumbers(valid));
        vo.getPoints().addAll(bigOutlier(valid));

        if (vo.getPoints().isEmpty()) {
            vo.getPoints().add(AiPointVO.of("low", "没发现规则之外的可疑模式，按常规复核即可"));
        }
        long mid = vo.getPoints().stream().filter(p -> "mid".equals(p.getLevel())).count();
        vo.setSummary(mid > 0 ? "有 " + mid + " 条建议人工确认" : "整体正常");
        return vo;
    }

    /**
     * 同一天、同一销售方开了好几张：可能是拆分开票。
     */
    private List<AiPointVO> sameDaySameSeller(List<Invoice> list) {
        Map<String, List<Invoice>> groups = new LinkedHashMap<>();
        for (Invoice item : list) {
            String key = String.valueOf(item.getInvoiceDate()) + "|" + item.getSeller();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        List<AiPointVO> points = new ArrayList<>();
        for (Map.Entry<String, List<Invoice>> entry : groups.entrySet()) {
            List<Invoice> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Invoice item : group) {
                sum = sum.add(item.getAmount() == null ? BigDecimal.ZERO : item.getAmount());
            }
            String date = String.valueOf(group.get(0).getInvoiceDate());
            points.add(AiPointVO.of("mid",
                    date + " 在「" + group.get(0).getSeller() + "」开了 " + group.size()
                            + " 张票，合计 ¥" + money(sum) + "，确认是不是拆分开票",
                    "invoice.invoiceDate", date));
        }
        return points;
    }

    /**
     * 连号发票。不违规，但值得一起看一眼。
     *
     * <p>坑：发票号是 20 位，转成 long 会超过 Long.MAX_VALUE，
     * 转成 double 会丢精度（相邻两个号差值变 0，连号永远抓不到），必须用 BigInteger 比。
     */
    private List<AiPointVO> sequentialNumbers(List<Invoice> list) {
        Map<String, List<String>> bySeller = new LinkedHashMap<>();
        for (Invoice item : list) {
            String no = item.getInvoiceNo().trim();
            if (!no.matches("\\d+")) {
                continue;
            }
            bySeller.computeIfAbsent(item.getSeller() == null ? "" : item.getSeller(), k -> new ArrayList<>()).add(no);
        }

        List<AiPointVO> points = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : bySeller.entrySet()) {
            List<String> numbers = new ArrayList<>(entry.getValue());
            numbers.sort((a, b) -> new BigInteger(a).compareTo(new BigInteger(b)));
            int seq = 0;
            for (int i = 1; i < numbers.size(); i++) {
                if (new BigInteger(numbers.get(i)).subtract(new BigInteger(numbers.get(i - 1)))
                        .equals(BigInteger.ONE)) {
                    seq++;
                }
            }
            if (seq >= 1) {
                points.add(AiPointVO.of("mid",
                        "「" + entry.getKey() + "」有 " + (seq + 1) + " 张发票号码连号，"
                                + "连号本身不违规，建议一并核对",
                        "invoice.invoiceNo", numbers.get(0)));
            }
        }
        return points;
    }

    /**
     * 单张金额显著高于本单均值：不一定是问题，但值得看一眼。
     */
    private List<AiPointVO> bigOutlier(List<Invoice> list) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Invoice item : list) {
            sum = sum.add(item.getAmount() == null ? BigDecimal.ZERO : item.getAmount());
        }
        if (sum.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.HALF_UP);
        BigDecimal threshold = avg.multiply(BIG_FACTOR);
        List<AiPointVO> points = new ArrayList<>();
        for (Invoice item : list) {
            BigDecimal amount = item.getAmount() == null ? BigDecimal.ZERO : item.getAmount();
            if (amount.compareTo(threshold) > 0) {
                points.add(AiPointVO.of("low",
                        "有一张 ¥" + money(amount) + "（" + item.getSeller() + "）明显高于本单均值 ¥" + money(avg),
                        "invoice.amount", amount.toPlainString()));
            }
        }
        return points;
    }

    private String money(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
