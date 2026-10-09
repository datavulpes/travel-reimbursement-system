package com.ts.travelserver.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地启发式通道（travel.ai.enabled=false 时生效）。
 *
 * <p>存在的意义不是"假装是 AI"，而是两件事：
 * <ol>
 *   <li>没配密钥、断网、答辩现场没外网时，全链路照常跑通，页面照常有结论，
 *       只是角标写「本地演示」——既不冒充模型，也不留空白。</li>
 *   <li>它给出的 Δ 是确定性的，可以当作一条基线：真模型接上以后，
 *       拿同一批单对比「规则 Δ vs 模型 Δ」，才知道模型到底有没有带来增量。</li>
 * </ol>
 *
 * <p>规则与前端 api/ai.js 保持同一套口径（同一句话在前后端得到同一个 Δ），
 * 改一边就要改另一边。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "travel.ai", name = "enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class MockAiService implements AiService {

    private static final String LOCAL_MODEL = "local-heuristic";

    /** 事由里交代了「这趟要拿到什么」 */
    private static final List<String> OUTPUT_WORDS = List.of(
            "签单", "订单", "验收", "交付", "样品", "报价", "合同", "回款", "节点", "审厂", "试产", "对账");

    /** 事由里交代了「去干什么」 */
    private static final List<String> PURPOSE_WORDS = List.of(
            "拜访", "验货", "审核", "展会", "交付", "培训", "谈判", "对接", "巡店", "驻场");

    private final ObjectMapper objectMapper;

    @Override
    public boolean remote() {
        return false;
    }

    @Override
    public String model() {
        return LOCAL_MODEL;
    }

    @Override
    public AiTextResult complete(String scene, String inputJson) {
        long start = System.currentTimeMillis();
        String json;
        try {
            if (AiScene.SEMANTIC_EVAL.equals(scene)) {
                json = semantic(inputJson);
            } else if (AiScene.APPLY_ASSIST.equals(scene)) {
                json = assist(inputJson);
            } else {
                // 别的场景本地给不出东西，返回 null 让调用方走各自的规则兜底
                return null;
            }
        } catch (Exception e) {
            log.warn("本地启发式生成失败（{}）：{}", scene, e.getMessage());
            return null;
        }
        return AiTextResult.builder()
                .content(json)
                .model(LOCAL_MODEL)
                .costMs(System.currentTimeMillis() - start)
                .remote(false)
                .build();
    }

    /**
     * 问数助手的原始调用：本地启发式给不出叙事，返回 null。
     *
     * <p>这与语义评价 / 填写助手不同——那两个本地能算出确定性的 Δ 与建议清单，
     * 问数助手要的是「把真实数字讲成一句人话」，本地没有这条能力。
     * 返回 null 后，{@code DataQaService} 会直接用 Java 把算好的真实数字原样陈列出来，
     * 不是装作模型、也不是留空。
     */
    @Override
    public AiTextResult completeRaw(String scene, String prompt) {
        return null;
    }

    // ---------- 本地规则：填单建议 ----------

    /**
     * 本地版建议清单。
     *
     * <p>与远程模型共用同一份契约（{@code items}），差别只在胆子大小：
     * 本地只陈述能从输入里直接读出来的事，<b>不给 REWRITE</b>——
     * 写句子要的是组织能力，规则没有；硬拼一句套话还不如让调用方去用模板。
     *
     * <p>每条的 evidence 都必须引到输入里真实存在的值：后面那道校验不认本地的身份，
     * 自己造一条依据出来跟模型幻觉没有区别。
     */
    private String assist(String inputJson) throws Exception {
        JsonNode root = objectMapper.readTree(inputJson == null ? "{}" : inputJson);
        JsonNode form = root.get("form");
        JsonNode context = root.get("context");
        List<String> unfilled = toStringList(root.get("unfilled"));

        String reason = text(form, "reason") == null ? "" : text(form, "reason");
        String tripType = text(form, "tripType");

        JsonNode history = context == null ? null : context.get("history");
        String destTopType = text(history, "destTopType");
        JsonNode costRef = context == null ? null : context.get("costRef");
        String refAvg = costRef == null ? null : str(costRef.get("avg"));

        List<Map<String, Object>> items = new ArrayList<>();
        if (reason.isBlank()) {
            items.add(item("reason", "HINT", "mid", null,
                    "事由还没填，写清「去做什么 + 预期拿到什么」这趟才讲得清",
                    "unfilled", "出差事由"));
        } else {
            if (!containsAny(reason, PURPOSE_WORDS)) {
                items.add(item("reason", "HINT", "mid", null,
                        "没写明去做什么，补一个动词（拜访 / 验货 / 审核 / 交付）",
                        "reason", clip(reason, 24)));
            }
            if (!containsAny(reason, OUTPUT_WORDS)) {
                items.add(item("reason", "HINT", "high", null,
                        "没写预期产出，补一句能验收的结果，合理性评分会上去",
                        "reason", clip(reason, 24)));
            }
        }
        if ((tripType == null || tripType.isBlank()) && destTopType != null && !destTopType.isBlank()) {
            items.add(item("tripType", "FILL", "low", destTopType,
                    "你在这一带的历史行程多数填的是这个类型",
                    "history.destTopType", destTopType));
        }
        if (unfilled.contains("预估金额") && refAvg != null && !refAvg.isBlank()) {
            items.add(item("estTraffic", "HINT", "low", null,
                    "预估金额还没填，同类参考的均值已经在下面的建议里按天摊好了",
                    "costRef.avg", refAvg));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return objectMapper.writeValueAsString(out);
    }

    private static Map<String, Object> item(String field, String kind, String level, String suggest,
                                            String why, String evField, String evValue) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("field", evField);
        evidence.put("value", evValue == null ? "" : evValue);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("field", field);
        map.put("kind", kind);
        map.put("level", level);
        map.put("suggest", suggest);
        map.put("why", why);
        map.put("evidence", evidence);
        return map;
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (int i = 0; i < node.size(); i++) {
            String value = node.get(i).asString();
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    private static String str(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    // ---------- 本地规则：与前端 api/ai.js 的 aiSemanticEval 同一套口径 ----------

    private String semantic(String inputJson) {
        JsonNode root = objectMapper.readTree(inputJson == null ? "{}" : inputJson);
        String reason = text(root, "reason");
        String tripType = text(root, "tripType");
        String partnerName = text(root, "partnerName");

        int days = 0;
        String dest = "";
        String itineraryRange = "";
        JsonNode list = root.get("itineraries");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                JsonNode item = list.get(i);
                // 优先读调用方算好的 days，读不到再从区间字符串反推——
                // 两边字段口径必须一致，否则本地启发式会永远算成 0 天
                int segDays = intOf(item, "days");
                String range = text(item, "dateRange");
                if (segDays <= 0 && range != null && range.contains("~")) {
                    String[] parts = range.split("~");
                    segDays = daysBetween(parts[0], parts[1]);
                }
                days += segDays;
                String to = text(item, "to");
                if (dest.isEmpty() && to != null && !to.isBlank()) {
                    dest = to;
                }
                if (itineraryRange.isEmpty() && range != null) {
                    itineraryRange = range;
                }
            }
        }
        String partnerCity = cityOf(partnerName);
        String destCity = cityOf(dest);

        List<Map<String, Object>> reasons = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int delta = 0;

        if (!containsAny(reason, OUTPUT_WORDS)) {
            missing.add("预期产出");
            delta -= 3;
            reasons.add(reason("事由没写预期产出，判断不了这趟值不值",
                    "reason", clip(reason, 24)));
        }
        if (days >= 4 && ("验货".equals(tripType) || "商务拜访".equals(tripType))) {
            delta -= 7;
            // 依据要挂到真实字段上，写「共 5 天」这种自造值会被 evidence 校验整条丢掉
            reasons.add(reason(tripType + "一般 1~2 天，本次行程 " + days + " 天偏长",
                    "itinerary", itineraryRange.isEmpty() ? dest : itineraryRange));
        }
        if (!partnerCity.isEmpty() && !destCity.isEmpty() && !partnerCity.equals(destCity)) {
            delta -= 3;
            reasons.add(reason("对象在" + partnerCity + "，行程目的地是" + destCity + "，事由里没交代为什么",
                    "itinerary[0].toCity", destCity));
        }
        if (reason != null && !reason.isBlank() && !containsAny(reason, PURPOSE_WORDS)) {
            delta -= 3;
            reasons.add(reason("事由里看不出具体去做什么", "reason", clip(reason, 24)));
        }

        boolean good = containsAny(reason, OUTPUT_WORDS) && days > 0 && days <= 3
                && reason != null && reason.length() >= 15;
        if (good) {
            delta = Math.min(delta + 5, 5);
            reasons.add(0, reason("事由写明了目的与预期产出，行程天数也匹配", "reason", clip(reason, 24)));
        }

        delta = Math.max(-10, Math.min(5, delta));
        String grade = gradeOf(delta);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grade", grade);
        out.put("delta", delta);
        out.put("reasons", reasons);
        out.put("missing", missing);
        // 本地启发式给低置信度，前端会提示「低置信度，仅供参考」
        out.put("confidence", 0.6);
        return objectMapper.writeValueAsString(out);
    }

    private static Map<String, Object> reason(String point, String field, String value) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("field", field);
        evidence.put("value", value == null ? "" : value);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("point", point);
        item.put("evidence", evidence);
        return item;
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

    private static boolean containsAny(String text, List<String> words) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String cityOf(String text) {
        return CityHints.cityOf(text);
    }

    /** 行程天数（含首尾），解析不出来按 0 处理，不让它算出负数把判断带偏 */
    private static int daysBetween(String start, String end) {
        if (start == null || end == null) {
            return 0;
        }
        try {
            LocalDate a = LocalDate.parse(start.trim());
            LocalDate b = LocalDate.parse(end.trim());
            long d = java.time.temporal.ChronoUnit.DAYS.between(a, b) + 1;
            return d > 0 ? (int) d : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int intOf(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        if (child == null || child.isNull()) {
            return 0;
        }
        try {
            return child.isNumber() ? child.asInt() : Integer.parseInt(child.asString().trim());
        } catch (Exception e) {
            return 0;
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

    private static String clip(String text, int max) {
        if (text == null) {
            return "(空)";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
