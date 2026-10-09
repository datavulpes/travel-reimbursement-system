package com.ts.travelserver.service;

import com.ts.travelserver.dto.AiSemanticRequest;
import com.ts.travelserver.dto.ApplySubmitRequest;
import com.ts.travelserver.ai.AiSemanticEvaluator;
import com.ts.travelserver.entity.Partner;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.eval.EvalContext;
import com.ts.travelserver.eval.EvalEngine;
import com.ts.travelserver.mapper.PartnerMapper;
import com.ts.travelserver.vo.AiSemanticVO;
import com.ts.travelserver.vo.EvalResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 合理性评价的服务门面（模块 05）。
 *
 * <p>引擎在 eval 包里，这里只做两件事：把入参翻译成 {@link EvalContext}，
 * 以及给申请模块提供一个「提交时顺便算一次评价」的入口。
 *
 * <p>依赖方向是 eval → apply 之外的独立分支：评价只读申请与历史数据，不反过来被申请改。
 */
@Service
@RequiredArgsConstructor
public class EvalService {

    private final EvalEngine evalEngine;
    private final PartnerMapper partnerMapper;
    private final StandardService standardService;
    private final AiSemanticEvaluator aiSemanticEvaluator;

    /**
     * G-02：事前预演，不落库。
     */
    public EvalResultVO preview(ApplySubmitRequest request) {
        return evalEngine.preview(context(null, request));
    }

    /**
     * A-02：提交时算分并落库。
     */
    public void evaluateAndSave(String applyNo, ApplySubmitRequest request) {
        evalEngine.evaluateAndSave(context(applyNo, request));
    }

    /**
     * G-01：查某单的评价结论。
     */
    public EvalResultVO get(String applyNo) {
        return evalEngine.get(applyNo);
    }

    /**
     * G-03：只算语义 Δ，不跑规则四因子、不落库。
     *
     * <p>填写页每改一次事由就想看一眼「这次说得通吗」，没必要为了看 Δ 把整份评价重算一遍。
     * 提交（A-02）时那次才算全量并写库。
     */
    public AiSemanticVO semantic(AiSemanticRequest request) {
        return aiSemanticEvaluator.evaluate(context(null, adapt(request)));
    }

    /**
     * G-03 的宽松入参转成评价上下文能吃的形状。
     *
     * <p>这层适配是有意为之：评价需要的字段就这五个，但为了躲开
     * {@link ApplySubmitRequest} 上那一堆必填校验才单独收了一遍。
     * 字段缺失一律按"没有"处理，不在这里补第 j 个必填判断。
     */
    private ApplySubmitRequest adapt(AiSemanticRequest request) {
        if (request == null) {
            return new ApplySubmitRequest();
        }
        ApplySubmitRequest target = new ApplySubmitRequest();
        target.setReason(request.getReason());
        target.setTripType(request.getTripType());
        target.setPartnerId(request.getPartnerId());
        target.setEstTraffic(request.getEstTraffic());
        target.setEstHotel(request.getEstHotel());
        List<ApplySubmitRequest.ItineraryItem> items = new ArrayList<>();
        if (request.getItineraries() != null) {
            for (AiSemanticRequest.ItineraryItem item : request.getItineraries()) {
                if (item == null) {
                    continue;
                }
                ApplySubmitRequest.ItineraryItem target0 = new ApplySubmitRequest.ItineraryItem();
                target0.setSeq(item.getSeq());
                target0.setStartDate(item.getStartDate());
                target0.setEndDate(item.getEndDate());
                target0.setFromCity(item.getFromCity());
                target0.setToCity(item.getToCity());
                target0.setTrafficType(item.getTrafficType());
                target0.setCityLevel(item.getCityLevel());
                items.add(target0);
            }
        }
        target.setItineraries(items);
        return target;
    }

    /**
     * 把提交入参翻译成评价上下文。
     *
     * @param applyNo 申请号；预演时传 null
     */
    public EvalContext context(String applyNo, ApplySubmitRequest request) {
        List<TripItinerary> itineraries = new ArrayList<>();
        List<ApplySubmitRequest.ItineraryItem> items = request.getItineraries();
        if (items != null) {
            int seq = 1;
            for (ApplySubmitRequest.ItineraryItem item : items) {
                TripItinerary itinerary = new TripItinerary();
                itinerary.setApplyNo(applyNo);
                itinerary.setSeq(item.getSeq() == null ? seq : item.getSeq());
                itinerary.setStartDate(parseDate(item.getStartDate()));
                itinerary.setEndDate(parseDate(item.getEndDate()));
                itinerary.setFromCity(item.getFromCity());
                itinerary.setToCity(item.getToCity());
                itinerary.setTrafficType(item.getTrafficType());
                String cityLevel = item.getCityLevel() == null || item.getCityLevel().isBlank()
                        ? standardService.cityLevelOf(item.getToCity())
                        : item.getCityLevel();
                itinerary.setCityLevel(cityLevel);
                itineraries.add(itinerary);
                seq++;
            }
        }
        Partner partner = request.getPartnerId() == null
                ? null
                : partnerMapper.selectById(request.getPartnerId());

        return EvalContext.builder()
                .applyNo(applyNo)
                .partnerId(request.getPartnerId())
                .reason(request.getReason())
                .tripType(request.getTripType())
                .estTotal(request.calcEstTotal())
                .itineraries(itineraries)
                .partner(partner)
                .build();
    }

    /**
     * 后端重算预估合计：以「交通 + 住宿」为准，不认前端传来的 estTotal。
     */
    public BigDecimal estTotal(ApplySubmitRequest request) {
        return request.calcEstTotal();
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
}
