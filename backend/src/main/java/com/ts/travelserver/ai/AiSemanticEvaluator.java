package com.ts.travelserver.ai;

import com.ts.travelserver.config.AiConfig;
import com.ts.travelserver.eval.EvalContext;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.vo.AiPointVO;
import com.ts.travelserver.vo.AiSemanticVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 语义评价：把「事由说不说得通」这一维交给模型，输出 Δ 与带依据的理由。
 *
 * <h3>为什么不让模型直接打分</h3>
 * <p>三条：不可复现（同样输入两次不同分）、改不动权重（那张权重表是设计报告的核心）、
 * 会编数字。所以模型只出 Δ ∈ [−10, +5]，规则分与权重一个字不动，
 * 总分 = clamp(规则分 + Δ)，档位仍按原阈值切。AI 挂了 Δ 取 0，退化成纯规则模型。
 *
 * <h3>evidence 校验是这里的生命线</h3>
 * <p>模型返回的每条理由必须指回一个真实字段，且 value 必须是输入里出现过的原文。
 * 对不上就<b>整条丢弃</b>；一条都不剩就把 Δ 归零——
 * 没有依据的调节量比没有调节量更危险，它会让分数变得无法解释。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSemanticEvaluator {

    /** 模型给的置信度不可信时的兜底值 */
    private static final BigDecimal DEFAULT_CONFIDENCE = new BigDecimal("0.85");
    private static final BigDecimal LOCAL_CONFIDENCE = new BigDecimal("0.6");

    private final AiService aiService;
    private final AiCache aiCache;
    private final AiLogService aiLogService;
    private final AiConfig config;
    private final ObjectMapper objectMapper;

    /**
     * 算一次语义 Δ。
     *
     * @return 永不返回 null；不可用时返回 delta=0 且 enhanced=false 的降级结论
     */
    public AiSemanticVO evaluate(EvalContext ctx) {
        Input input = Input.of(ctx);
        String inputJson = toJson(input);
        String refNo = ctx.getApplyNo();

        // 同一份输入（含 prompt 版本）短时间内不重复烧 token
        String cached = aiCache.get(AiScene.SEMANTIC_EVAL, inputJson);
        if (cached != null) {
            AiSemanticVO hit = parse(cached, input);
            if (hit != null) {
                return hit;
            }
        }

        AiTextResult result = aiService.complete(AiScene.SEMANTIC_EVAL, inputJson);
        if (result == null || result.getContent() == null || result.getContent().isBlank()) {
            AiSemanticVO vo = degraded("AI 不可用，本次按纯规则评分");
            aiLogService.write(AiScene.SEMANTIC_EVAL, refNo, inputJson, null,
                    BigDecimal.ZERO, null, 0L, false, true,
                    "模型未返回内容（超时 / 不可用）", null, null);
            return vo;
        }

        AiSemanticVO vo = parse(result.getContent(), input);
        boolean accepted = vo != null && Boolean.TRUE.equals(vo.getEnhanced());
        String err = null;
        if (vo == null) {
            vo = degraded("模型输出无法解析，本次按纯规则评分");
            err = "模型输出解析失败或 evidence 校验不过，整条丢弃";
        }
        vo.setModel(result.getModel());
        aiLogService.write(AiScene.SEMANTIC_EVAL, refNo, inputJson, result.getContent(),
                BigDecimal.valueOf(vo.getDelta()), vo.getGrade(), result.getCostMs(), accepted,
                !Boolean.TRUE.equals(result.isRemote()),
                err, result.getRequestTokens(), result.getResponseTokens());

        if (accepted) {
            aiCache.put(AiScene.SEMANTIC_EVAL, inputJson, result.getContent());
        }
        return vo;
    }

    // ---------- 解析与校验 ----------

    /**
     * 把模型原文解析成结论，并做 evidence 校验。解析不出来返回 null。
     */
    private AiSemanticVO parse(String content, Input input) {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception e) {
            log.warn("语义评价输出不是合法 JSON：{}", e.getMessage());
            return null;
        }
        if (root == null || root.isNull()) {
            return null;
        }

        int delta = intOf(root.get("delta"), 0);
        delta = Math.max(-10, Math.min(5, delta));

        List<AiPointVO> reasons = new ArrayList<>();
        JsonNode array = root.get("reasons");
        if (array != null && array.isArray()) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode item = array.get(i);
                String point = text(item, "point");
                JsonNode ev = item.get("evidence");
                String field = text(ev, "field");
                String value = text(ev, "value");
                if (point == null || point.isBlank()) {
                    continue;
                }
                // 防幻觉：依据对不上就整条丢，绝不把没有出处的结论摆到审批人面前
                if (!evidenceValid(field, value, input)) {
                    log.info("丢弃一条无依据的理由：field={} value={}", field, value);
                    continue;
                }
                reasons.add(AiPointVO.of("mid", point, field, value));
            }
        }

        AiSemanticVO vo = new AiSemanticVO();
        vo.setMissing(readMissing(root));
        vo.setPromptVersion(config.getPromptVersion());
        vo.setLocal(!aiService.remote());
        vo.setConfidence(confidenceOf(root.get("confidence"), aiService.remote()));

        if (delta != 0 && reasons.isEmpty()) {
            // 有调节量却没有一条站得住的依据：归零，宁可不用
            vo.setDelta(0);
            vo.setGrade("B");
            vo.setEnhanced(false);
            vo.setNote("模型给出的理由全部缺少有效依据，Δ 已归零");
        } else {
            vo.setDelta(delta);
            vo.setGrade(gradeOf(delta));
            vo.setEnhanced(delta != 0 || !reasons.isEmpty());
        }
        vo.setReasons(reasons);
        return vo;
    }

    /**
     * evidence 校验：value 必须是对应字段里真实出现的原文。
     *
     * <p>认字段前缀而不认全等，是因为模型会给 {@code itinerary[0].toCity} 这种带下标的写法，
     * 全等匹配会把正确依据误杀。
     */
    private boolean evidenceValid(String field, String value, Input input) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        String f = field == null ? "" : field.trim().toLowerCase();
        if (f.startsWith("reason")) {
            return contains(input.reason, v);
        }
        if (f.startsWith("triptype")) {
            return contains(input.tripType, v);
        }
        if (f.startsWith("partner")) {
            return contains(input.partnerName, v);
        }
        if (f.startsWith("esttotal")) {
            return contains(String.valueOf(input.estTotal), v.replaceAll("[^0-9.]", ""));
        }
        if (f.startsWith("itinerary")) {
            for (Segment seg : input.segments) {
                if (contains(seg.from, v) || contains(seg.to, v) || contains(seg.range, v)) {
                    return true;
                }
                // 天数也算行程段的真实取值：「行程 5 天偏长」这类理由挂的是它
                if (String.valueOf(seg.days()).equals(v)) {
                    return true;
                }
            }
            return false;
        }
        // 不认识的字段一律不认：宁可少一条理由，也不能让模型自己造字段名
        return false;
    }

    private static boolean contains(String source, String target) {
        if (source == null || target == null) {
            return false;
        }
        return source.replaceAll("\\s+", "").toLowerCase()
                .contains(target.replaceAll("\\s+", "").toLowerCase());
    }

    private List<String> readMissing(JsonNode root) {
        List<String> missing = new ArrayList<>();
        JsonNode array = root.get("missing");
        if (array == null || !array.isArray()) {
            return missing;
        }
        for (int i = 0; i < array.size(); i++) {
            String item = array.get(i).asString();
            if (item != null && !item.isBlank()) {
                missing.add(item);
            }
        }
        return missing;
    }

    private BigDecimal confidenceOf(JsonNode node, boolean remote) {
        if (node != null && !node.isNull()) {
            try {
                double value = node.isNumber() ? node.asDouble() : Double.parseDouble(node.asString().trim());
                if (value > 0 && value <= 1) {
                    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
                }
            } catch (Exception e) {
                // 置信度是锦上添花，解析不出来就用默认值
            }
        }
        return remote ? DEFAULT_CONFIDENCE : LOCAL_CONFIDENCE;
    }

    private static String gradeOf(int delta) {
        if (delta >= 5) {
            return "A";
        }
        if (delta == 0) {
            return "B";
        }
        if (delta >= -3) {
            return "C";
        }
        if (delta >= -7) {
            return "D";
        }
        return "E";
    }

    private AiSemanticVO degraded(String note) {
        AiSemanticVO vo = new AiSemanticVO();
        vo.setGrade("B");
        vo.setDelta(0);
        vo.setEnhanced(false);
        vo.setLocal(!aiService.remote());
        vo.setConfidence(LOCAL_CONFIDENCE);
        vo.setPromptVersion(config.getPromptVersion());
        vo.setNote(note);
        return vo;
    }

    private static int intOf(JsonNode node, int fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        try {
            if (node.isNumber()) {
                return node.asInt();
            }
            return (int) Math.round(Double.parseDouble(node.asString().trim()));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        return child.asString();
    }

    // ---------- 入参 ----------

    /**
     * 交给模型的输入。只放判断语义必需的东西：
     * 对象名只传名称不传 id，金额只传合计——能不给的原始数据就不给。
     */
    private record Input(String reason, String tripType, String partnerName,
                         BigDecimal estTotal, List<Segment> segments) {

        static Input of(EvalContext ctx) {
            List<Segment> segments = new ArrayList<>();
            List<TripItinerary> list = ctx.getItineraries();
            if (list != null) {
                for (TripItinerary item : list) {
                    long days = 0;
                    String range = "";
                    if (item.getStartDate() != null && item.getEndDate() != null) {
                        days = ChronoUnit.DAYS.between(item.getStartDate(), item.getEndDate()) + 1;
                        range = item.getStartDate() + "~" + item.getEndDate();
                    }
                    segments.add(new Segment(
                            item.getFromCity(), item.getToCity(),
                            range, days > 0 ? (int) days : 0));
                }
            }
            String partnerName = ctx.getPartner() == null ? null : ctx.getPartner().getPartnerName();
            return new Input(ctx.getReason(), ctx.getTripType(), partnerName, ctx.getEstTotal(), segments);
        }
    }

    private record Segment(String from, String to, String range, int days) {
    }

    /**
     * 入参序列化。用 LinkedHashMap 而不是实体，是为了控制字段顺序与数量——
     * 模型看到的东西越少，编造的空间越小。
     */
    private String toJson(Input input) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("reason", input.reason() == null ? "" : input.reason());
        root.put("tripType", input.tripType() == null ? "" : input.tripType());
        root.put("partnerName", input.partnerName() == null ? "" : input.partnerName());
        root.put("estTotal", input.estTotal() == null ? "0" : input.estTotal().toPlainString());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Segment seg : input.segments()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", seg.from() == null ? "" : seg.from());
            item.put("to", seg.to() == null ? "" : seg.to());
            item.put("dateRange", seg.range());
            item.put("days", seg.days());
            list.add(item);
        }
        root.put("itineraries", list);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }
}
