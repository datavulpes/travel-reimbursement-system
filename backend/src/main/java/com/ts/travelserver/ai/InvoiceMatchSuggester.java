package com.ts.travelserver.ai;

import com.ts.travelserver.entity.TripItinerary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 票据挂段推荐（C-01 上传时顺带给一条建议）。
 *
 * <p>这是全系统里唯一一处「AI 真做一次决策」的地方：它真的会替人选一段。
 * 所以置信度是这套设计的主角而不是配角——
 * 高置信度自动选中、中置信度选中但提示核对、低置信度只给个默认值并明说「请人工确认」。
 * 兜底永远留给人（BR-07 要求票据必须挂到具体段，但挂哪段由人拍板）。
 *
 * <p>算法是确定性的两条硬证据，不进模型：
 * 开票日期落在某段行程区间里 > 销售方城市与某段吻合 > 都没有就给第一段。
 * 用不上模型的原因很简单——这是查区间和比字符串，模型也只会做同样的事，还更慢更贵。
 */
@Slf4j
@Component
public class InvoiceMatchSuggester {

    /** 日期落在行程区间内：最硬的证据 */
    private static final BigDecimal HIGH = new BigDecimal("0.92");
    /** 日期对不上但销售方城市吻合 */
    private static final BigDecimal MID = new BigDecimal("0.70");
    /** 都对不上，交给人 */
    private static final BigDecimal LOW = new BigDecimal("0.40");

    /**
     * 推荐挂接段。
     *
     * @param invoiceDate 开票日期，OCR 或手工录入；为空时只按城市判断
     * @param seller      销售方名称，用来认城市
     * @param itineraries 本次申请的行程段，按 seq 升序
     * @return 建议；行程段为空时返回 null
     */
    public Suggestion suggest(LocalDate invoiceDate, String seller, List<TripItinerary> itineraries) {
        if (itineraries == null || itineraries.isEmpty()) {
            return null;
        }

        if (invoiceDate != null) {
            for (TripItinerary item : itineraries) {
                if (item.getStartDate() == null || item.getEndDate() == null) {
                    continue;
                }
                boolean inRange = !invoiceDate.isBefore(item.getStartDate())
                        && !invoiceDate.isAfter(item.getEndDate());
                if (inRange) {
                    return new Suggestion(item.getId(), HIGH,
                            "开票日期 " + invoiceDate + " 落在第 " + seq(item) + " 段行程区间（"
                                    + item.getStartDate() + " ~ " + item.getEndDate() + "）");
                }
            }
        }

        String sellerCity = CityHints.cityOf(seller);
        if (!sellerCity.isEmpty()) {
            for (TripItinerary item : itineraries) {
                boolean hit = sellerCity.equals(CityHints.cityOf(item.getToCity()))
                        || sellerCity.equals(CityHints.cityOf(item.getFromCity()));
                if (hit) {
                    return new Suggestion(item.getId(), MID,
                            "开票日期不在行程区间内，但销售方城市与第 " + seq(item) + " 段（"
                                    + item.getFromCity() + "→" + item.getToCity() + "）吻合");
                }
            }
        }

        TripItinerary first = itineraries.get(0);
        return new Suggestion(first.getId(), LOW,
                "日期与城市都对不上，先挂到第 1 段，请人工确认");
    }

    private static int seq(TripItinerary item) {
        return item.getSeq() == null ? 1 : item.getSeq();
    }

    /**
     * 一条挂段建议。
     *
     * @param itineraryId 建议挂接的行程段 id
     * @param confidence  置信度 0~1，前端据此决定是自动选中还是只提示
     * @param reason      说给人听的理由，必须能直接展示
     */
    public record Suggestion(Long itineraryId, BigDecimal confidence, String reason) {
    }
}
