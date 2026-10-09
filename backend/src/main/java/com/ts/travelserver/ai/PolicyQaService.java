package com.ts.travelserver.ai;

import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.config.AiConfig;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.service.StandardService;
import com.ts.travelserver.vo.DeptVO;
import com.ts.travelserver.vo.PolicyQaVO;
import com.ts.travelserver.vo.StandardVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 制度问答（POLICY_QA）：员工问一句出差或报销的规定，系统照着库里的现行规定答一句。
 *
 * <h3>为什么不做向量检索</h3>
 * <p>知识源全在 MySQL 里而且是结构化的：差标是 trip_standard 表、
 * 超标判定是 travel.standard.high-ratio、部门预算在 dept 表。
 * 一条 SQL 就取全了，语义检索这一步省掉，换来的是<b>答案一定能指回某一行数据</b>。
 * 只有开始问「出差期间生病怎么报销」这类散在 PDF 里的问题，才需要真 RAG。
 *
 * <h3>三条边界</h3>
 * <ol>
 *   <li><b>不算数</b>：金额、比例全部由规则层查出来放进输入，模型只组织语言。</li>
 *   <li><b>数字必须落在事实里</b>：答案里出现任何输入中没有的数字，整条丢弃回落到规则答案。</li>
 *   <li><b>查不到就说查不到</b>：这是它区别于别的场景的关键。差标表里没有的东西
 *       （报销时限、补贴天数）必须回答「库里没查到」，不许用行业惯例补——
 *       补出来的答案看着专业，但没人能对它负责。</li>
 * </ol>
 *
 * <h3>降级</h3>
 * <p>模型不可用时不是返回空，而是走 {@link #ruleAnswer}：按问题里出现的关键词
 * （城市等级、交通方式、城市名）把相关的差标行挑出来，原样列给员工看。
 * 列的是库里的真实数值，一句解释都不加——这比一段编出来的话有用得多。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyQaService {

    /** 关键词命中不了任何一行时，判定这一问不在库里 */
    private static final String NOT_FOUND = "库里没查到这一条，去「标准配置」里看现行差标，或问行政。";

    private static final int MAX_ANSWER = 200;
    private static final int MAX_QUESTION = 100;

    /** 出差类型六值，与 TripApply、schema.sql、dict.js 四处必须逐字一致 */
    private static final List<String> TRIP_TYPES =
            List.of("验货", "商务拜访", "项目交付", "供应商审核", "展会", "其他");

    /** 常见城市 → 城市等级，给「去深圳能报多少」这类问法兜底命中 */
    private static final Map<String, String> CITY_LEVEL = Map.ofEntries(
            Map.entry("北京", "一级"), Map.entry("上海", "一级"),
            Map.entry("广州", "一级"), Map.entry("深圳", "一级"),
            Map.entry("杭州", "二级"), Map.entry("南京", "二级"),
            Map.entry("成都", "二级"), Map.entry("武汉", "二级"),
            Map.entry("西安", "二级"), Map.entry("苏州", "二级"),
            Map.entry("天津", "二级"), Map.entry("重庆", "二级"),
            Map.entry("东莞", "三级"), Map.entry("佛山", "三级"),
            Map.entry("珠海", "三级"), Map.entry("中山", "三级"));

    private final StandardService standardService;
    private final AiService aiService;
    private final AiLogService aiLogService;
    private final AiConfig config;
    private final ObjectMapper objectMapper;

    /** 超标判定比例，与 StandardService 同源 */
    @Value("${travel.standard.high-ratio:0.10}")
    private double highRatio;

    /**
     * 答一句。
     *
     * @param question 员工原话，为空或超长直接按「没问」处理
     */
    public PolicyQaVO answer(String question) {
        PolicyQaVO vo = new PolicyQaVO();
        vo.setQuestion(question == null ? "" : question.trim());
        vo.setModel(aiService.model());
        vo.setPromptVersion(config.getPromptVersion());
        vo.setLocal(Boolean.TRUE);
        vo.setAnswerable(Boolean.FALSE);

        if (vo.getQuestion().isBlank() || vo.getQuestion().length() > MAX_QUESTION) {
            vo.setAnswer("把想问的写清楚，比如「去深圳坐飞机能报多少」。");
            return vo;
        }

        List<StandardVO> standards = safeStandards();
        List<String> allFacts = new ArrayList<>();
        for (StandardVO s : standards) {
            allFacts.add(factOf(s));
        }
        // 不带「超标判定：」这个前缀：它会被规则答案整句引用，
        // 拼出来就成了「库里现行规定：超标判定：超过上限…」，读起来是两段话
        allFacts.add("超过上限 " + pct(highRatio) + " 以内算「接近上限」；"
                + "超过 " + pct(highRatio) + " 算超标，必须填超标说明才能提交。");
        DeptVO dept = myDept();
        if (dept != null && dept.getMonthlyBudget() != null) {
            allFacts.add("所在部门「" + dept.getDeptName() + "」月预算 ¥"
                    + money(dept.getMonthlyBudget()) + "。");
        }
        allFacts.add("出差类型共六个：" + String.join("、", TRIP_TYPES) + "，选「其他」要补充说明。");
        allFacts.add("常见城市等级：" + cityLevelText());
        vo.setAllFacts(allFacts);

        String factsJson = toJson(facts(standards, dept));
        Set<String> allowed = AiTextGuard.allowedNumbers(factsJson);

        // 走多占位符那条：问题与事实是两段来源完全不同的文本，
        // 合成一个 {input} 会让模型分不清哪句是人问的
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("question", vo.getQuestion());
        vars.put("facts", factsJson);
        AiTextResult result = aiService.complete(AiScene.POLICY_QA, vo.getQuestion(), vars);
        String content = result == null ? null : result.getContent();
        boolean ok = false;
        String err = null;
        if (content != null) {
            JsonNode root = safeTree(content);
            Boolean answerable = root == null ? null : boolOf(root.get("answerable"));
            String answer = root == null ? null : AiTextGuard.text(root, "answer");
            List<String> used = usedFacts(root);
            if (Boolean.TRUE.equals(answerable) && answer != null && !answer.isBlank()
                    && answer.length() <= MAX_ANSWER
                    && AiTextGuard.numbersAllowed(answer, allowed)) {
                vo.setAnswerable(Boolean.TRUE);
                vo.setAnswer(answer.trim());
                vo.setFacts(used.isEmpty() ? matchedFacts(vo.getQuestion(), standards) : used);
                vo.setLocal(!aiService.remote());
                ok = true;
            } else {
                err = "模型输出未通过数字 / 长度校验，或它自己判定查不到，本次回落规则答案";
            }
        } else {
            err = "模型未返回内容（超时 / 不可用）";
        }
        if (result != null) {
            aiLogService.write(AiScene.POLICY_QA, null, vo.getQuestion(), content,
                    null, null, result.getCostMs(), ok, !ok,
                    err, result.getRequestTokens(), result.getResponseTokens());
        }
        if (!ok) {
            ruleAnswer(vo, standards);
        }
        return vo;
    }

    // ---------- 规则答案：模型不可用时的唯一来源 ----------

    /**
     * 按问题里的关键词把相关差标挑出来原样列出。
     *
     * <p>一句解释都不加是刻意的：规则层不知道员工的问法到底想问什么，
     * 与其猜着说一句，不如把相关的行摆出来让他自己看——
     * 一行真实数值比一段猜出来的话有用得多。
     */
    private void ruleAnswer(PolicyQaVO vo, List<StandardVO> standards) {
        List<String> hit = matchedFacts(vo.getQuestion(), standards);
        if (hit.isEmpty()) {
            // 差标行没命中，再看看问的是不是超标比例 / 部门预算 / 出差类型这类整体规则：
            // 它们不在差标表里，但库里确实有，说「查不到」是冤枉的
            hit = topicFacts(vo.getQuestion(), vo.getAllFacts());
        }
        if (hit.isEmpty()) {
            vo.setAnswerable(Boolean.FALSE);
            vo.setAnswer(NOT_FOUND);
            vo.setFacts(List.of());
            return;
        }
        vo.setAnswerable(Boolean.TRUE);
        vo.setFacts(hit);
        vo.setAnswer("库里现行规定：" + String.join("；", hit));
        vo.setLocal(Boolean.TRUE);
    }

    /**
     * 问题里出现的城市名 / 城市等级 / 交通方式，命中的差标行。
     */
    private List<String> matchedFacts(String question, List<StandardVO> standards) {
        String q = question == null ? "" : question;
        String level = null;
        for (Map.Entry<String, String> e : CITY_LEVEL.entrySet()) {
            if (q.contains(e.getKey())) {
                level = e.getValue();
                break;
            }
        }
        if (level == null) {
            for (String lv : List.of("一级", "二级", "三级", "1级", "2级", "3级")) {
                if (q.contains(lv)) {
                    level = lv.replace("1", "一").replace("2", "二").replace("3", "三");
                    break;
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (StandardVO s : standards) {
            boolean cityHit = level != null && level.equals(s.getCityLevel());
            boolean typeHit = q.contains(s.getTrafficType() == null ? "###" : s.getTrafficType());
            // 既没认出城市也没认出交通方式时一条都不给：
            // 摆一堆不相干的标准行，看着像答了，其实问的人什么都拿不到
            if (cityHit || typeHit) {
                out.add(factOf(s));
            }
        }
        return out.size() > 4 ? out.subList(0, 4) : out;
    }

    /**
     * 按主题词挑事实。
     *
     * <p>超标比例、部门预算、出差类型这三类不在差标表里，
     * 关键词直接对差标行永远命中不了，但它们确实是库里现行的规定——
     * 因为匹配不上就说「查不到」，是把自己的检索方式当成了事实边界。
     */
    private List<String> topicFacts(String question, List<String> allFacts) {
        String q = question == null ? "" : question;
        Map<String, String> topics = new LinkedHashMap<>();
        topics.put("超标", "超过上限");
        topics.put("上限", "超过上限");
        topics.put("预算", "月预算");
        topics.put("类型", "出差类型共六个");
        for (Map.Entry<String, String> e : topics.entrySet()) {
            if (!q.contains(e.getKey())) {
                continue;
            }
            List<String> hit = allFacts.stream()
                    .filter(f -> f != null && f.contains(e.getValue()))
                    .toList();
            if (!hit.isEmpty()) {
                return hit;
            }
        }
        return List.of();
    }

    // ---------- 事实采集 ----------

    private Map<String, Object> facts(List<StandardVO> standards, DeptVO dept) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (StandardVO s : standards) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cityLevel", s.getCityLevel());
            item.put("trafficType", s.getTrafficType());
            item.put("maxAmount", money(s.getMaxAmount()));
            item.put("effectiveAt", s.getEffectiveAt() == null ? null : s.getEffectiveAt().toString());
            list.add(item);
        }
        root.put("standards", list);
        root.put("highRatio", pct(highRatio));
        // 城市 → 等级这条映射必须给：差标表里只有等级没有城市名，
        // 不给它，员工问「去深圳」，模型在【事实】里找不到深圳，就只能答「查不到」——
        // 拒得没错，但对问的人来说等于没答。
        Map<String, String> levels = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : CITY_LEVEL.entrySet()) {
            levels.merge(e.getValue(), e.getKey(), (a, b) -> a + " / " + b);
        }
        root.put("cityLevels", levels);
        if (dept != null && dept.getMonthlyBudget() != null) {
            Map<String, Object> budget = new LinkedHashMap<>();
            budget.put("deptName", dept.getDeptName());
            budget.put("monthlyBudget", money(dept.getMonthlyBudget()));
            root.put("budget", budget);
        }
        root.put("tripTypes", TRIP_TYPES);
        return root;
    }

    private List<StandardVO> safeStandards() {
        try {
            List<StandardVO> list = standardService.listStandards();
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("查差标失败，本次问答只给部门预算：{}", e.getMessage());
            return List.of();
        }
    }

    /** 本人所在部门的预算。取不到就算了，不是每个人都有部门 */
    private DeptVO myDept() {
        try {
            SysUser user = UserContext.get();
            if (user == null || user.getDeptId() == null) {
                return null;
            }
            return standardService.listDepts().stream()
                    .filter(d -> user.getDeptId().equals(d.getDeptId()))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 小工具 ----------

    private JsonNode safeTree(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            log.warn("制度问答解析失败：{}", e.getMessage());
            return null;
        }
    }

    private static Boolean boolOf(JsonNode node) {
        return node == null || node.isNull() ? null : node.asBoolean();
    }

    private List<String> usedFacts(JsonNode root) {
        if (root == null) {
            return List.of();
        }
        JsonNode arr = root.get("usedFacts");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size() && out.size() < 4; i++) {
            String v = arr.get(i).asString();
            if (v != null && !v.isBlank()) {
                out.add(v.trim());
            }
        }
        return out;
    }

    /** 「一级：北京 / 上海 / 广州 / 深圳」这类一句，给页面折叠区用 */
    private static String cityLevelText() {
        Map<String, StringBuilder> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : CITY_LEVEL.entrySet()) {
            grouped.computeIfAbsent(e.getValue(), k -> new StringBuilder()).append(e.getKey()).append(" / ");
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> e : grouped.entrySet()) {
            String cities = e.getValue().toString();
            parts.add(e.getKey() + "：" + cities.substring(0, cities.length() - 3));
        }
        return String.join("；", parts);
    }

    private static String factOf(StandardVO s) {
        return s.getCityLevel() + "城市 · " + s.getTrafficType() + " 上限 ¥" + money(s.getMaxAmount())
                + (s.getEffectiveAt() == null ? "" : "（" + s.getEffectiveAt() + " 起）");
    }

    private static String money(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String pct(double v) {
        BigDecimal b = BigDecimal.valueOf(v).multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
        return b.toPlainString() + "%";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
