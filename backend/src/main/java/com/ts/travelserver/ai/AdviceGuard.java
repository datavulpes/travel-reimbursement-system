package com.ts.travelserver.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 填写助手输出的建议清单校验（APPLY_ASSIST 场景）。
 *
 * <h3>为什么要有这一层</h3>
 * <p>模型现在一次性返回多条建议（改写事由、建议某个字段填什么、建议某件事该写清），
 * 每条都可能一键写进表单。一条没人复核的建议进了表单，就是员工替模型签的字。
 * 所以这里不做「尽量放行」，而是逐条过闸，过不了的整条丢。
 *
 * <h3>五道闸</h3>
 * <ol>
 *   <li><b>kind / field 白名单</b>。不认识的取值一律丢——宁可少一条，也不能让它自己发明字段名。</li>
 *   <li><b>金额字段永远不许模型给</b>。{@code estTraffic / estHotel} 由库内的同类参考与差标算出，
 *       模型只要沾手编的就是数，一旦被采用，审批时那句「凭什么」答不上来。</li>
 *   <li><b>改写必须有原文</b>。事由为空时不接受 REWRITE：一个字没写的人，
 *       系统替他写的「预期 XXX」就是凭空捏的。</li>
 *   <li><b>suggest 要钉得住事实</b>。城市、出差类型、天数这三类硬事实，
 *       只要出现在建议里，就必须在输入里找得到同款，否则整条丢。</li>
 *   <li><b>每条必须带能在输入里找回的 evidence</b>。项目里所有 AI 结论共用这一条口径，
 *       没有出处的建议和没有建议是一回事。</li>
 * </ol>
 *
 * <h3>抓不到的一类</h3>
 * <p>员工写的对象名在系统里找不到字典可比，模型顺着它编一个别的公司名出来这里抓不到。
 * 能做的是把已知的对象名 {@code partner.name} 放进输入，prompt 里写明「只能用出现在输入里的名字」。
 * 这条局限要记得：它意味着校验是防洪堤，不是保险柜。
 */
@Slf4j
@Component
public final class AdviceGuard {

    /** 这条建议不针对某个具体字段，只是整体提醒 */
    public static final String FIELD_NONE = "-";

    /** 给现有的一句话换个写法：只有事由需要 */
    public static final String KIND_REWRITE = "REWRITE";
    /** 建议某个空字段填这个值 */
    public static final String KIND_FILL = "FILL";
    /** 只给方向，不给值——本人才知道的事走这一类 */
    public static final String KIND_HINT = "HINT";

    private static final Set<String> KINDS = Set.of(KIND_REWRITE, KIND_FILL, KIND_HINT);
    private static final Set<String> FIELDS = Set.of(
            "reason", "tripType", "exceedReason", "estTraffic", "estHotel", FIELD_NONE);

    /**
     * 允许模型给值的字段；其余字段只接受 HINT。
     *
     * <p>超标说明刻意不在里面：超出多少、按哪几段算的都由规则算好了，
     * 具体原因只有当事人知道，模型给的一句成品等于替员工签字。
     */
    private static final Set<String> ALLOW_FILL = Set.of("reason", "tripType");

    /** 金额类字段：在任何 kind 下都不许模型碰 */
    private static final Set<String> AMOUNT_FIELDS = Set.of("estTraffic", "estHotel");

    /** 出差类型六个取值。与 {@link com.ts.travelserver.entity.TripApply#getTripType} 注释同源 */
    private static final List<String> TRIP_TYPES = List.of(
            "验货", "商务拜访", "项目交付", "供应商审核", "展会", "其他");

    private static final int MAX_ITEMS = 6;
    private static final int MAX_SUGGEST = 80;
    private static final int MAX_WHY = 40;

    private static final Pattern DAY = Pattern.compile("(\\d+)\\s*天");
    /** 金额写法：¥2400 / 2400 元。事由里出现钱数一律按编处理 */
    private static final Pattern MONEY = Pattern.compile("[¥￥]\\s?[\\d,]+(\\.\\d+)?|[\\d,]+(\\.\\d+)?\\s?元");

    private final ObjectMapper objectMapper;

    public AdviceGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 一条通过校验的建议 */
    public record AdviceItem(String field, String kind, String level, String suggest,
                             String why, String evidenceField, String evidenceValue) {
    }

    /**
     * 校验时对照的事实基准。由调用方按「这次实际给了模型什么东西」生成。
     *
     * @param pool         输入里出现过的所有原文片段，evidence 与硬事实都跟它比对
     * @param allowedDays  允许被提到的天数（只有本次真实天数；没填日期时为空集）
     * @param reasonEmpty  事由为空，此时不许出现改写
     */
    public record Facts(Set<String> pool, Set<String> allowedDays, boolean reasonEmpty) {

        public static Facts of(Set<String> pool, int days, boolean reasonEmpty) {
            Set<String> daySet = new LinkedHashSet<>();
            if (days > 0) {
                daySet.add(String.valueOf(days));
            }
            return new Facts(pool, daySet, reasonEmpty);
        }
    }

    /**
     * 解析并校验模型输出。
     *
     * @return 通过校验的条目；一条都不过返回空表，绝不部分放行
     */
    public List<AdviceItem> accept(String content, Facts facts) {
        if (content == null || content.isBlank() || facts == null) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception e) {
            log.warn("填单建议输出不是合法 JSON：{}", e.getMessage());
            return List.of();
        }
        JsonNode array = root == null ? null : root.get("items");
        if (array == null || !array.isArray()) {
            // 旧版模板只回 {"rewrite":"..."}，那种输出没有 evidence，按口径丢掉不留
            log.info("模型没按建议清单格式输出，本次不采纳");
            return List.of();
        }

        List<AdviceItem> out = new ArrayList<>();
        for (int i = 0; i < array.size() && out.size() < MAX_ITEMS; i++) {
            JsonNode node = array.get(i);
            String kind = upper(text(node, "kind"));
            String field = trimToNull(text(node, "field"));
            String suggest = trimToNull(text(node, "suggest"));
            String why = trimToNull(text(node, "why"));
            String level = levelOf(text(node, "level"));
            JsonNode ev = node.get("evidence");
            String evField = trimToNull(text(ev, "field"));
            String evValue = trimToNull(text(ev, "value"));

            if (!KINDS.contains(kind)) {
                log.info("丢弃一条建议：kind 不在白名单（{}）", kind);
                continue;
            }
            if (field == null || !FIELDS.contains(field)) {
                log.info("丢弃一条建议：field 不在白名单（{}）", field);
                continue;
            }
            if (AMOUNT_FIELDS.contains(field)) {
                log.info("丢弃一条建议：模型试图给金额字段 {}", field);
                continue;
            }
            if (KIND_HINT.equals(kind)) {
                // 提示类哪怕带了 suggest 也不往下传：系统不替他填只有本人才知道的东西
                suggest = null;
            } else {
                if (suggest == null) {
                    log.info("丢弃一条建议：{} 没给建议值", kind);
                    continue;
                }
                if (suggest.length() > MAX_SUGGEST) {
                    log.info("丢弃一条建议：建议值 {} 字，超出上限", suggest.length());
                    continue;
                }
                if (!ALLOW_FILL.contains(field)) {
                    log.info("丢弃一条建议：{} 字段不在可给值清单里", field);
                    continue;
                }
                if (KIND_REWRITE.equals(kind)) {
                    if (!"reason".equals(field)) {
                        log.info("丢弃一条建议：改写只能针对事由，收到的是 {}", field);
                        continue;
                    }
                    if (facts.reasonEmpty()) {
                        log.info("丢弃一条建议：事由为空时不许改写");
                        continue;
                    }
                }
                if ("tripType".equals(field) && !TRIP_TYPES.contains(suggest)) {
                    log.info("丢弃一条建议：出差类型取值不在值域里（{}）", suggest);
                    continue;
                }
                if (!anchored(suggest, facts)) {
                    log.info("丢弃一条建议：建议值里有对不上的事实 -> {}", suggest);
                    continue;
                }
            }
            if (why == null) {
                log.info("丢弃一条建议：没给理由");
                continue;
            }
            if (evField == null || evValue == null || !inPool(evValue, facts.pool())) {
                log.info("丢弃一条建议：依据对不上输入（{}={}）", evField, evValue);
                continue;
            }
            out.add(new AdviceItem(field, kind, level, suggest,
                    why.length() > MAX_WHY ? why.substring(0, MAX_WHY) : why,
                    evField, evValue));
        }
        return out;
    }

    /**
     * 建议值里的硬事实能不能在输入里找着同源出处。
     *
     * <p>只查这三类，是因为它们各自都有确定的取值集合：城市认 {@link CityHints} 那二十个，
     * 出差类型认六个值域，天数认本次行程天数。再往下查（比如 verbs、对象名）要么没字典，
     * 要么误杀率高于收益，所以到此为止。
     */
    private boolean anchored(String suggest, Facts facts) {
        if (MONEY.matcher(suggest).find()) {
            return false;
        }
        Matcher m = DAY.matcher(suggest);
        while (m.find()) {
            if (!facts.allowedDays().contains(m.group(1))) {
                return false;
            }
        }
        for (String city : CityHints.cities()) {
            if (suggest.contains(city) && !inPool(city, facts.pool())) {
                return false;
            }
        }
        for (String type : TRIP_TYPES) {
            if (suggest.contains(type) && !inPool(type, facts.pool())) {
                return false;
            }
        }
        return true;
    }

    /** token 是否被输入里的某个值包含。用包含而非全等：模型给的常常是长句里的一个片段 */
    private static boolean inPool(String token, Set<String> pool) {
        if (token == null || token.isBlank() || pool == null) {
            return false;
        }
        String t = token.trim().replaceAll("\\s+", "");
        for (String value : pool) {
            if (value != null && value.replaceAll("\\s+", "").contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把交给模型的输入摊平成一张原文表。
     *
     * <p>不做裁剪、不做标准化：只要有一条原文能兜住模型的引用就算数。
     * 走 Map 而不是反序列化后的 JsonNode，是为了绕开 Jackson 2/3 之间那批改过名的遍历 API。
     * 这张表同时也是「模型能看到什么」的完整清单——它的引用范围不应该比这个更大。
     */
    public Set<String> pool(Map<String, Object> input) {
        Set<String> pool = new LinkedHashSet<>();
        walk(input, pool);
        // 值域常量也算原文：出差类型由上下文推荐出来时，模型引用它不该被判成编造
        pool.addAll(TRIP_TYPES);
        return pool;
    }

    private void walk(Object node, Set<String> pool) {
        if (node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            map.values().forEach(v -> walk(v, pool));
            return;
        }
        if (node instanceof Iterable<?> list) {
            list.forEach(v -> walk(v, pool));
            return;
        }
        if (node instanceof BigDecimal || node instanceof Number || node instanceof Boolean) {
            pool.add(String.valueOf(node));
            return;
        }
        String value = String.valueOf(node).trim();
        if (!value.isBlank()) {
            pool.add(value);
        }
    }

    private static String levelOf(String value) {
        if (value == null) {
            return "low";
        }
        String v = value.trim().toLowerCase();
        return "high".equals(v) || "mid".equals(v) ? v : "low";
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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
}
