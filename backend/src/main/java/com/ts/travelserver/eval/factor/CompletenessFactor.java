package com.ts.travelserver.eval.factor;

import com.ts.travelserver.entity.EvalResult;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.eval.EvalContext;
import com.ts.travelserver.eval.EvalFactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * F4 事由完整度（权重 0.15）。
 *
 * <p>算法（设计报告 6.7 表 21）：事由、关联对象、行程三要素齐全度逐项计分加和。
 * 事由按长度分档（写得越具体分越高，鼓励员工别写「出差」两个字），
 * 关联对象填了得满分，行程段齐全得满分、缺一项扣 10 分。
 *
 * <p>这一项满分 100，是四个因子里唯一完全由填报质量决定的——填得认真分数就高。
 */
@Component
public class CompletenessFactor extends EvalFactor {

    @Value("${travel.eval.weight.complete:0.15}")
    private double weight;

    @Override
    public String key() {
        return EvalResult.KEY_F_COMPLETE;
    }

    @Override
    public String name() {
        return "事由完整度";
    }

    @Override
    public BigDecimal weight() {
        return BigDecimal.valueOf(weight);
    }

    @Override
    public EvalScore calc(EvalContext ctx) {
        // ---- 事由 40 分：按字数分档 ----
        int length = ctx.getReason() == null ? 0 : ctx.getReason().trim().length();
        int reasonScore;
        if (length >= 50) {
            reasonScore = 40;
        } else if (length >= 30) {
            reasonScore = 32;
        } else if (length >= 15) {
            reasonScore = 24;
        } else if (length >= 5) {
            reasonScore = 12;
        } else {
            reasonScore = 0;
        }

        // ---- 关联对象 30 分 ----
        int partnerScore = ctx.getPartnerId() != null ? 30 : 0;

        // ---- 行程 30 分：有段就给基础分，缺字段扣 10 ----
        List<TripItinerary> itineraries = ctx.getItineraries();
        int itineraryScore = 0;
        boolean itineraryComplete = false;
        if (itineraries != null && !itineraries.isEmpty()) {
            itineraryScore = 30;
            itineraryComplete = true;
            for (TripItinerary item : itineraries) {
                if (item.getStartDate() == null || item.getEndDate() == null
                        || blank(item.getFromCity()) || blank(item.getToCity())
                        || blank(item.getTrafficType())) {
                    itineraryScore = 20;
                    itineraryComplete = false;
                    break;
                }
            }
        }

        int total = reasonScore + partnerScore + itineraryScore;
        return new EvalScore(BigDecimal.valueOf(total),
                "事由 " + length + " 字（" + reasonScore + "/40）"
                        + "，关联对象（" + partnerScore + "/30）"
                        + "，行程" + (itineraryComplete ? "填写完整" : "有缺失项") + "（" + itineraryScore + "/30）");
    }

    private boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
