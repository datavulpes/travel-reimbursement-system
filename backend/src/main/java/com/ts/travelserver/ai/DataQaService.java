package com.ts.travelserver.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.config.AiConfig;
import com.ts.travelserver.config.SlaConfig;
import com.ts.travelserver.entity.Dept;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.mapper.DeptMapper;
import com.ts.travelserver.mapper.SysUserMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.service.AnalyticsService;
import com.ts.travelserver.vo.DataQaVO;
import com.ts.travelserver.vo.MonthlyReportVO;
import com.ts.travelserver.vo.PartnerRoiVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问数助手（DATA_QA）：管理层用大白话问业务数据，系统把真实数字算出来，让模型讲成一句人话。
 *
 * <h3>架构：Java 固定能力目录 + 模型只做叙事</h3>
 * <p>能问什么由本类一份写死的能力目录决定（{@link #CAPABILITY_CATALOG}），
 * 不依赖模型端的 function calling——换一个不认 tool_calling 的国产模型也不会失效，
 * 也不会出现「模型调了个不存在的接口」这种幻觉出口。两段式：
 * <ol>
 *   <li><b>意图</b>：优先让模型从目录里挑能力、抽参数；模型不可用或挑错时，
 *       退回 Java 关键词重判（{@link #capabilityByKeywords}）——离线也照样能答。</li>
 *   <li><b>数字</b>：全部由 {@code AnalyticsService} / Mapper 算好，模型碰不到库、不算数。</li>
 *   <li><b>叙事</b>：数字算好后让模型组织成一句人话；模型不可用或数字校验没过，
 *       直接用 Java 把真实数字原样陈列（{@link #buildRuleAnswer}），不是装模型、也不是留空。</li>
 * </ol>
 *
 * <h3>三条边界（与全系统一致）</h3>
 * <ul>
 *   <li><b>不联网</b>：数字来自本库 Service。</li>
 *   <li><b>不算数</b>：意图与参数解析都是 Java 做的，模型只叙事。</li>
 *   <li><b>每条带 evidence</b>：叙事里每个数字都要能在真实数字里找到（{@code AiTextGuard}），
 *       落不回的整句丢弃，回落规则文案。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQaService {

    /** 能力代码 */
    private static final String MONTHLY_RATIO = "MONTHLY_RATIO";
    private static final String PARTNER_TOP = "PARTNER_TOP";
    private static final String APPLY_SUMMARY = "APPLY_SUMMARY";
    private static final String BUDGET_LEFT = "BUDGET_LEFT";
    private static final String OVERDUE_LIST = "OVERDUE_LIST";

    /** 能力目录（原文发给模型做意图抽取；与 {@link #CAPABILITY_TEXT} 口径一致） */
    private static final String CAPABILITY_CATALOG = String.join("\n",
            "- MONTHLY_RATIO  问「某月（或某月某部门）的费效比、投入、产出、可疑差旅占比」",
            "- PARTNER_TOP    问「哪个往来对象最不划算、投入产出对照、对象排名」",
            "- APPLY_SUMMARY  问「某人某月出差几次、花了多少」",
            "- BUDGET_LEFT    问「某部门某月预算还剩多少、预算余额」",
            "- OVERDUE_LIST   问「有哪些单超时没批、压了多久」");

    private static final Map<String, String> CAPABILITY_TEXT = Map.of(
            MONTHLY_RATIO, "费效比",
            PARTNER_TOP, "对象投入产出",
            APPLY_SUMMARY, "个人出差",
            BUDGET_LEFT, "部门预算余额",
            OVERDUE_LIST, "超时未批单");

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AnalyticsService analyticsService;
    private final TripApplyMapper tripApplyMapper;
    private final DeptMapper deptMapper;
    private final SysUserMapper sysUserMapper;
    private final SlaConfig slaConfig;
    private final AiService aiService;
    private final AiLogService aiLogService;
    private final AiConfig config;
    private final ObjectMapper objectMapper;

    /**
     * 答一句。
     *
     * @param question 管理层原话，为空或超长直接按「没问」处理
     */
    public DataQaVO ask(String question) {
        DataQaVO vo = new DataQaVO();
        vo.setQuestion(question == null ? "" : question.trim());
        vo.setModel(aiService.model());
        vo.setPromptVersion(config.getPromptVersion());
        vo.setLocal(Boolean.TRUE);
        vo.setAnswerable(Boolean.FALSE);

        if (vo.getQuestion().isBlank() || vo.getQuestion().length() > 200) {
            vo.setCapability(null);
            vo.setCapabilityText("AI助手");
            vo.setAnswer("把想问的写清楚，比如「9 月技术部费效比多少」「哪个客户最不划算」"
                    + "「张三 9 月出差几次」「技术部 9 月预算还剩多少」「有哪些单超时没批」。");
            return vo;
        }

        // 1) 意图：模型优先，Java 关键词兜底
        String capability = resolveCapability(vo.getQuestion());
        if (capability == null) {
            vo.setCapability(null);
            vo.setCapabilityText("AI助手");
            vo.setAnswer("没太看懂这一问。可以问我：9 月技术部费效比多少 / 哪个客户最不划算 /"
                    + " 张三 9 月出差几次 / 技术部 9 月预算还剩多少 / 有哪些单超时没批。");
            return vo;
        }
        vo.setCapability(capability);
        vo.setCapabilityText(CAPABILITY_TEXT.getOrDefault(capability, capability));

        // 2) 参数：月份 / 部门 / 人名一律 Java 抽，不信任模型给的原文
        String month = parseMonth(vo.getQuestion());
        String deptName = resolveDept(vo.getQuestion());
        String empName = resolveEmp(vo.getQuestion());

        // 3) 算数（数字永远来自库）
        boolean ok = compute(vo, capability, month, deptName, empName);
        if (!ok) {
            // compute 内部已把 answer 写成引导语、answerable=false
            return vo;
        }

        // 4) 叙事：模型讲人话，数字校验没过或无模型时回落规则文案
        narrate(vo);

        return vo;
    }

    // ---------- 意图 ----------

    /**
     * 模型先挑能力；挑错 / 不可用 / 返回 NONE 时退回 Java 关键词。
     */
    private String resolveCapability(String question) {
        String byModel = modelCapability(question);
        if (byModel != null) {
            return byModel;
        }
        return capabilityByKeywords(question);
    }

    private String modelCapability(String question) {
        try {
            String prompt = AiPrompts.dataQaIntent(CAPABILITY_CATALOG, question);
            AiTextResult result = aiService.completeRaw(AiScene.DATA_QA, prompt);
            if (result == null) {
                return null;
            }
            JsonNode root = safeTree(result.getContent());
            if (root == null) {
                return null;
            }
            String cap = AiTextGuard.text(root, "capability");
            if (cap == null || cap.isBlank() || "NONE".equals(cap.trim())) {
                return null;
            }
            return CAPABILITY_TEXT.containsKey(cap.trim()) ? cap.trim() : null;
        } catch (Exception e) {
            log.warn("问数助手意图抽取失败，退回关键词：{}", e.getMessage());
            return null;
        }
    }

    /**
     * Java 关键词判定。顺序即优先级：超时 / 预算 / 对象 / 个人 / 费效。
     * 关键词命中不了任何一条时返回 null——这一问不在能力目录里。
     */
    private String capabilityByKeywords(String q) {
        if (containsAny(q, "超时", "逾期", "压了", "压住", "没批", "未批", "卡住", "催")) {
            return OVERDUE_LIST;
        }
        if (containsAny(q, "预算", "余额", "还剩", "剩多少", "花了多少预算")) {
            return BUDGET_LEFT;
        }
        if (containsAny(q, "客户", "对象", "往来", "投入产出", "划算", "排名", "top", "TOP", "最不值")) {
            return PARTNER_TOP;
        }
        if (containsAny(q, "出差几次", "出了几次", "花了多少", "报了多少", "跑了几次")
                || (resolveEmp(q) != null && containsAny(q, "出差", "花", "报", "跑", "几次"))) {
            return APPLY_SUMMARY;
        }
        if (containsAny(q, "费效", "费效比", "投入", "产出", "占比", "月报", "花了多少钱", "花掉")) {
            return MONTHLY_RATIO;
        }
        return null;
    }

    // ---------- 算数 ----------

    /**
     * 按能力算数并填进 vo.data / vo.facts。返回 false 表示参数不齐、答不了（已写好引导语）。
     */
    private boolean compute(DataQaVO vo, String capability, String month, String deptName, String empName) {
        switch (capability) {
            case MONTHLY_RATIO -> {
                return computeMonthly(vo, month, deptName);
            }
            case PARTNER_TOP -> {
                return computePartner(vo, month);
            }
            case APPLY_SUMMARY -> {
                return computeApply(vo, month, empName);
            }
            case BUDGET_LEFT -> {
                return computeBudget(vo, month, deptName);
            }
            case OVERDUE_LIST -> {
                return computeOverdue(vo, deptName);
            }
            default -> {
                vo.setAnswer("这一问不在我问数范围里。");
                return false;
            }
        }
    }

    private boolean computeMonthly(DataQaVO vo, String month, String deptName) {
        Long deptId = deptName == null ? null : deptIdOf(deptName);
        MonthlyReportVO report = analyticsService.monthly(month, deptId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", month);
        List<String> facts = new ArrayList<>();

        if (deptId != null) {
            MonthlyReportVO.DeptRowVO row = report.getRows() == null ? null
                    : report.getRows().stream().filter(r -> deptId.equals(r.getDeptId())).findFirst().orElse(null);
            if (row == null) {
                vo.setAnswerable(Boolean.FALSE);
                vo.setAnswer("库里没有「" + deptName + "」这个部门，检查下部门名。");
                return false;
            }
            data.put("scope", "dept");
            data.put("deptName", row.getDeptName());
            data.put("totalPay", money(row.getTotalPay()));
            data.put("totalOutput", money(row.getTotalOutput()));
            data.put("ratio", ratio(row.getRatio()));
            data.put("applyCnt", row.getApplyCnt());
            facts.add(month + " " + row.getDeptName() + " 费效比 " + ratio(row.getRatio())
                    + "，投入 ¥" + money(row.getTotalPay()) + "，产出 ¥" + money(row.getTotalOutput())
                    + "，出差 " + nz(row.getApplyCnt()) + " 单");
        } else {
            data.put("scope", "company");
            data.put("totalPay", money(report.getTotalPay()));
            data.put("totalOutput", money(report.getTotalOutput()));
            data.put("ratio", ratio(report.getRatio()));
            data.put("attentionRatio", ratio(report.getAttentionRatio()));
            data.put("fillRate", ratio(report.getFillRate()));
            data.put("applyCnt", report.getApplyCnt());
            data.put("paidCnt", report.getPaidCnt());
            facts.add(month + " 全公司费效比 " + ratio(report.getRatio())
                    + "，投入 ¥" + money(report.getTotalPay()) + "，产出 ¥" + money(report.getTotalOutput())
                    + "，出差 " + nz(report.getApplyCnt()) + " 单，可疑占比 " + pct(report.getAttentionRatio()));
        }
        vo.setData(data);
        vo.setFacts(facts);
        vo.setAnswerable(Boolean.TRUE);
        return true;
    }

    private boolean computePartner(DataQaVO vo, String month) {
        PartnerRoiVO partner = analyticsService.partner(month);
        // 当月没有任何付款记录：投入为 0，给不出对象排名，直接说清楚（不编「最不划算」）
        BigDecimal invest = partner.getTotalInvest();
        if (invest == null || invest.compareTo(BigDecimal.ZERO) == 0) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("month", month);
            empty.put("noSpend", true);
            empty.put("rows", List.of());
            vo.setData(empty);
            vo.setFacts(List.of(month + " 还没有付款数据，没法给对象投入产出排名。"));
            vo.setAnswerable(Boolean.TRUE);
            return true;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", month);
        data.put("totalInvest", money(partner.getTotalInvest()));
        data.put("totalOutput", money(partner.getTotalOutput()));
        data.put("ratio", ratio(partner.getRatio()));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        if (partner.getRows() != null) {
            // 费效比从高到低（最不划算的排最前），取前 5 给管理层看
            for (PartnerRoiVO.PartnerRowVO r : partner.getRows()) {
                if (rows.size() >= 5) {
                    break;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("partnerName", r.getPartnerName());
                item.put("totalInvest", money(r.getTotalInvest()));
                item.put("totalOutput", money(r.getTotalOutput()));
                item.put("ratio", ratio(r.getRatio()));
                item.put("tripCnt", r.getTripCnt());
                rows.add(item);
            }
            PartnerRoiVO.PartnerRowVO worst = partner.getRows().isEmpty() ? null : partner.getRows().get(0);
            if (worst != null) {
                facts.add("投入产出最不划算的是 " + worst.getPartnerName() + "，费效比 "
                        + ratio(worst.getRatio()) + "（投入 ¥" + money(worst.getTotalInvest())
                        + "，产出 ¥" + money(worst.getTotalOutput()) + "）");
            }
        }
        data.put("rows", rows);
        vo.setData(data);
        vo.setFacts(facts);
        vo.setAnswerable(Boolean.TRUE);
        return true;
    }

    private boolean computeApply(DataQaVO vo, String month, String empName) {
        if (empName == null) {
            vo.setAnswerable(Boolean.FALSE);
            vo.setAnswer("没听出你要查哪位员工。可以这么说：「张三 9 月出差几次」。");
            return false;
        }
        SysUser user = userOf(empName);
        if (user == null) {
            vo.setAnswerable(Boolean.FALSE);
            vo.setAnswer("库里没有叫「" + empName + "」的员工，检查下姓名。");
            return false;
        }
        int[] ym = parseYm(month);
        LocalDateTime from = LocalDateTime.of(ym[0], ym[1], 1, 0, 0);
        LocalDateTime to = from.plusMonths(1);
        List<TripApply> applies = tripApplyMapper.selectList(new LambdaQueryWrapper<TripApply>()
                .eq(TripApply::getUserId, user.getId())
                .ge(TripApply::getCreatedAt, from)
                .lt(TripApply::getCreatedAt, to));

        int cnt = applies.size();
        BigDecimal total = BigDecimal.ZERO;
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (TripApply a : applies) {
            total = total.add(a.getEstTotal() == null ? BigDecimal.ZERO : a.getEstTotal());
            byStatus.merge(a.getStatus() == null ? "其他" : a.getStatus(), 1, Integer::sum);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("empName", user.getRealName());
        data.put("month", month);
        data.put("applyCnt", cnt);
        data.put("totalEst", money(total));
        data.put("byStatus", byStatus);
        vo.setData(data);
        vo.setFacts(List.of(user.getRealName() + " " + month + " 出差 " + cnt + " 次，预估合计 ¥" + money(total)));
        vo.setAnswerable(Boolean.TRUE);
        return true;
    }

    private boolean computeBudget(DataQaVO vo, String month, String deptName) {
        if (deptName == null) {
            if (isDeptAll(vo.getQuestion())) {
                return computeBudgetAll(vo, month);
            }
            vo.setAnswerable(Boolean.FALSE);
            vo.setAnswer("没听出你要查哪个部门，还是想看全部部门。可以这么说：「技术部 9 月预算还剩多少」"
                    + "，或「各部门 9 月预算还剩多少」。");
            return false;
        }
        Dept dept = deptOf(deptName);
        if (dept == null) {
            vo.setAnswerable(Boolean.FALSE);
            vo.setAnswer("库里没有「" + deptName + "」这个部门，检查下部门名。");
            return false;
        }
        BigDecimal budget = dept.getMonthlyBudget() == null ? BigDecimal.ZERO : dept.getMonthlyBudget();
        BigDecimal used = analyticsService.monthly(month, dept.getId()).getTotalPay();
        used = used == null ? BigDecimal.ZERO : used;
        BigDecimal left = budget.subtract(used);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deptName", dept.getDeptName());
        data.put("month", month);
        data.put("monthlyBudget", money(budget));
        data.put("used", money(used));
        data.put("left", money(left));
        vo.setData(data);
        vo.setFacts(List.of(dept.getDeptName() + " " + month + " 预算 ¥" + money(budget)
                + "，已花 ¥" + money(used) + "，余额 ¥" + money(left)));
        vo.setAnswerable(Boolean.TRUE);
        return true;
    }

    private boolean isDeptAll(String q) {
        return containsAny(q, "各部门", "所有部门", "每个部门", "全部部门", "全部门",
                "各部门预算", "所有部门预算", "每个部门预算", "全部部门预算");
    }

    private boolean computeBudgetAll(DataQaVO vo, String month) {
        List<Dept> depts = safeDepts();
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalBudget = BigDecimal.ZERO;
        BigDecimal totalUsed = BigDecimal.ZERO;
        BigDecimal totalLeft = BigDecimal.ZERO;
        BigDecimal lowestLeft = null;
        String lowestName = null;
        for (Dept d : depts) {
            BigDecimal budget = d.getMonthlyBudget() == null ? BigDecimal.ZERO : d.getMonthlyBudget();
            BigDecimal used = analyticsService.monthly(month, d.getId()).getTotalPay();
            used = used == null ? BigDecimal.ZERO : used;
            BigDecimal left = budget.subtract(used);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deptName", d.getDeptName());
            item.put("monthlyBudget", money(budget));
            item.put("used", money(used));
            item.put("left", money(left));
            rows.add(item);
            totalBudget = totalBudget.add(budget);
            totalUsed = totalUsed.add(used);
            totalLeft = totalLeft.add(left);
            if (lowestLeft == null || left.compareTo(lowestLeft) < 0) {
                lowestLeft = left;
                lowestName = d.getDeptName();
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", "all");
        data.put("month", month);
        data.put("rows", rows);
        data.put("totalBudget", money(totalBudget));
        data.put("totalUsed", money(totalUsed));
        data.put("totalLeft", money(totalLeft));
        vo.setData(data);
        List<String> facts = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            facts.add(str(r.get("deptName")) + " 预算 ¥" + str(r.get("monthlyBudget"))
                    + "，已花 ¥" + str(r.get("used")) + "，余额 ¥" + str(r.get("left")));
        }
        if (lowestName != null) {
            facts.add("余额最低的是 " + lowestName + "，只剩 ¥" + money(lowestLeft));
        }
        vo.setFacts(facts);
        vo.setAnswerable(Boolean.TRUE);
        return true;
    }

    private boolean computeOverdue(DataQaVO vo, String deptName) {
        Long deptId = deptName == null ? null : deptIdOf(deptName);
        List<TripApply> pending = tripApplyMapper.selectList(new LambdaQueryWrapper<TripApply>()
                .in(TripApply::getStatus, TripApply.STATUS_DEPT_REVIEW, TripApply.STATUS_FINAL_REVIEW)
                .eq(deptId != null, TripApply::getDeptId, deptId));
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        int overdueCnt = 0;
        for (TripApply a : pending) {
            if (a.getStatusAt() == null) {
                continue;
            }
            long waited = java.time.Duration.between(a.getStatusAt(), LocalDateTime.now()).toHours();
            int limit = slaConfig.hoursOf(a.getStatus());
            boolean over = limit > 0 && waited > limit;
            if (over) {
                overdueCnt++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("applyNo", a.getApplyNo());
            item.put("reason", a.getReason());
            item.put("status", a.getStatus());
            item.put("waitedHours", waited);
            item.put("overdue", over);
            item.put("overdueHours", over ? waited - limit : 0L);
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deptName", deptName);
        data.put("count", pending.size());
        data.put("overdueCount", overdueCnt);
        data.put("items", items);
        vo.setData(data);
        if (overdueCnt > 0) {
            facts.add("当前在审 " + pending.size() + " 张单，其中 " + overdueCnt + " 张已超时");
        } else {
            facts.add("当前在审 " + pending.size() + " 张单，没有超时的");
        }
        vo.setFacts(facts);
        vo.setAnswerable(Boolean.TRUE);
        return true;
    }

    // ---------- 叙事 ----------

    /**
     * 模型把真实数字讲成一句人话；数字校验没过或无模型时回落规则文案。
     */
    private void narrate(DataQaVO vo) {
        String numbersJson = toJson(vo.getData());
        // 无付款数据：跳过模型，直接陈列提示（避免模型编出「最不划算」）
        if (Boolean.TRUE.equals(vo.getData() == null ? null : vo.getData().get("noSpend"))) {
            vo.setAnswer(buildRuleAnswer(vo));
            vo.setLocal(Boolean.TRUE);
            vo.setAnswerable(Boolean.TRUE);
            aiLogService.write(AiScene.DATA_QA, null, vo.getQuestion(), null, null, null,
                    0, false, true, "无付款数据，直接陈列提示", null, null);
            return;
        }
        java.util.Set<String> allowed = AiTextGuard.allowedNumbers(numbersJson);
        boolean modelUsed = false;
        String err = null;
        String modelContent = null;
        long costMs = 0;
        Integer reqTok = null;
        Integer respTok = null;

        if (aiService.remote()) {
            try {
                String prompt = AiPrompts.dataQaNarrate(vo.getQuestion(), numbersJson);
                AiTextResult result = aiService.completeRaw(AiScene.DATA_QA, prompt);
                if (result != null) {
                    modelContent = result.getContent();
                    costMs = result.getCostMs();
                    reqTok = result.getRequestTokens();
                    respTok = result.getResponseTokens();
                    String answer = extractAnswer(modelContent);
                    if (answer != null && !answer.isBlank() && answer.length() <= 120
                            && AiTextGuard.numbersAllowed(answer, allowed)) {
                        vo.setAnswer(answer.trim());
                        modelUsed = true;
                    } else {
                        err = "模型叙事未通过数字校验或格式不符，回落真实数字";
                    }
                } else {
                    err = "模型未返回内容（超时 / 不可用）";
                }
            } catch (Exception e) {
                err = "模型叙事异常：" + e.getMessage();
            }
        } else {
            err = "模型未启用，直接陈列真实数字";
        }

        if (!modelUsed) {
            vo.setAnswer(buildRuleAnswer(vo));
            vo.setLocal(Boolean.TRUE);
        } else {
            vo.setLocal(Boolean.FALSE);
        }
        vo.setAnswerable(Boolean.TRUE);

        aiLogService.write(AiScene.DATA_QA, null, vo.getQuestion(), modelContent,
                null, null, costMs, modelUsed, !modelUsed, err, reqTok, respTok);
    }

    /**
     * 规则叙事：模型没接上时，把真实数字直接讲成一句人话（不编、不补）。
     */
    private String buildRuleAnswer(DataQaVO vo) {
        Map<String, Object> d = vo.getData();
        if (d == null) {
            return String.join("；", vo.getFacts());
        }
        return switch (vo.getCapability()) {
            case MONTHLY_RATIO -> {
                String scope = str(d.get("scope"));
                if ("dept".equals(scope)) {
                    yield d.get("month") + " " + str(d.get("deptName")) + " 费效比 "
                            + str(d.get("ratio")) + "，投入 ¥" + str(d.get("totalPay"))
                            + "、产出 ¥" + str(d.get("totalOutput")) + "，出差 " + str(d.get("applyCnt")) + " 单。";
                }
                yield d.get("month") + " 全公司费效比 " + str(d.get("ratio"))
                        + "，投入 ¥" + str(d.get("totalPay")) + "、产出 ¥" + str(d.get("totalOutput"))
                        + "，出差 " + str(d.get("applyCnt")) + " 单，可疑占比 " + str(d.get("attentionRatio")) + "。";
            }
            case PARTNER_TOP -> {
                if (Boolean.TRUE.equals(d.get("noSpend"))) {
                    yield str(d.get("month")) + " 还没有付款数据，暂时没法比哪个对象最不划算。"
                            + "等有了付款记录我再给你排。";
                }
                List<?> rows = (List<?>) d.get("rows");
                if (rows == null || rows.isEmpty()) {
                    yield d.get("month") + " 暂无对象投入产出数据。";
                }
                Map<?, ?> top = (Map<?, ?>) rows.get(0);
                yield "投入产出最不划算的是 " + str(top.get("partnerName")) + "，费效比 "
                        + str(top.get("ratio")) + "（投入 ¥" + str(top.get("totalInvest"))
                        + "、产出 ¥" + str(top.get("totalOutput")) + "）。";
            }
            case APPLY_SUMMARY -> str(d.get("empName")) + " " + str(d.get("month"))
                    + " 出差 " + str(d.get("applyCnt")) + " 次，预估合计 ¥" + str(d.get("totalEst")) + "。";
            case BUDGET_LEFT -> {
                if ("all".equals(str(d.get("scope")))) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(str(d.get("month"))).append(" 各部门预算余额：");
                    List<?> rows = (List<?>) d.get("rows");
                    if (rows != null) {
                        for (int i = 0; i < rows.size(); i++) {
                            Map<?, ?> r = (Map<?, ?>) rows.get(i);
                            if (i > 0) {
                                sb.append("；");
                            }
                            sb.append(str(r.get("deptName"))).append(" 剩 ¥").append(str(r.get("left")))
                                    .append("（已花 ¥").append(str(r.get("used"))).append("）");
                        }
                    }
                    sb.append("。合计余额 ¥").append(str(d.get("totalLeft"))).append("。");
                    yield sb.toString();
                }
                yield str(d.get("deptName")) + " " + str(d.get("month"))
                        + " 预算 ¥" + str(d.get("monthlyBudget")) + "，已花 ¥" + str(d.get("used"))
                        + "，余额 ¥" + str(d.get("left")) + "。";
            }
            case OVERDUE_LIST -> "当前在审 " + str(d.get("count")) + " 张单，其中 "
                    + str(d.get("overdueCount")) + " 张已超时。";
            default -> String.join("；", vo.getFacts());
        };
    }

    // ---------- 参数抽取 ----------

    private String parseMonth(String q) {
        if (q == null || q.isBlank()) {
            return YM.format(LocalDateTime.now());
        }
        if (containsAny(q, "上月", "上个月")) {
            return YM.format(LocalDateTime.now().minusMonths(1));
        }
        if (containsAny(q, "本月", "这个月", "当月", "这月", "现在")) {
            return YM.format(LocalDateTime.now());
        }
        // yyyy-MM / yyyy年MM月 / yyyy/MM / 2026年9月
        Matcher ym = Pattern.compile("(20\\d{2})\\s*[-年/]?\\s*(\\d{1,2})\\s*月?").matcher(q);
        if (ym.find()) {
            return toYm(ym.group(1), ym.group(2));
        }
        // 只有「9月」这种，年份取当前年
        Matcher m = Pattern.compile("(\\d{1,2})\\s*月").matcher(q);
        if (m.find()) {
            return toYm(String.valueOf(LocalDateTime.now().getYear()), m.group(1));
        }
        return YM.format(LocalDateTime.now());
    }

    private String toYm(String year, String mon) {
        try {
            int y = Integer.parseInt(year.trim());
            int m = Integer.parseInt(mon.trim());
            if (m < 1 || m > 12) {
                return YM.format(LocalDateTime.now());
            }
            return String.format("%04d-%02d", y, m);
        } catch (Exception e) {
            return YM.format(LocalDateTime.now());
        }
    }

    private int[] parseYm(String ym) {
        String[] p = ym.split("-");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    private String resolveDept(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        List<Dept> depts = safeDepts();
        String hit = null;
        for (Dept d : depts) {
            if (d.getDeptName() != null && q.contains(d.getDeptName()) && d.getDeptName().length() > 1) {
                if (hit == null || d.getDeptName().length() > hit.length()) {
                    hit = d.getDeptName();
                }
            }
        }
        return hit;
    }

    private Long deptIdOf(String deptName) {
        Dept d = deptOf(deptName);
        return d == null ? null : d.getId();
    }

    private Dept deptOf(String deptName) {
        if (deptName == null) {
            return null;
        }
        return safeDepts().stream()
                .filter(d -> deptName.equals(d.getDeptName()))
                .findFirst().orElse(null);
    }

    private String resolveEmp(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        // 「我 / 我自己 / 我的」映射当前登录人（管理层本人视角问自己的出差）
        if (q.contains("我") && !containsAny(q, "我们", "我公司", "我方", "我国", "我的公司")) {
            SysUser me = UserContext.get();
            if (me != null && me.getRealName() != null && !me.getRealName().isBlank()) {
                return me.getRealName();
            }
        }
        List<SysUser> users = safeUsers();
        String hit = null;
        for (SysUser u : users) {
            if (u.getRealName() != null && q.contains(u.getRealName()) && u.getRealName().length() > 1) {
                if (hit == null || u.getRealName().length() > hit.length()) {
                    hit = u.getRealName();
                }
            }
        }
        return hit;
    }

    private SysUser userOf(String realName) {
        return safeUsers().stream()
                .filter(u -> realName != null && realName.equals(u.getRealName()))
                .findFirst().orElse(null);
    }

    private List<Dept> safeDepts() {
        try {
            return deptMapper.selectList(null);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<SysUser> safeUsers() {
        try {
            return sysUserMapper.selectList(null);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------- 小工具 ----------

    private String extractAnswer(String content) {
        JsonNode root = safeTree(content);
        if (root == null) {
            return null;
        }
        return AiTextGuard.text(root, "answer");
    }

    private JsonNode safeTree(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean containsAny(String text, String... words) {
        if (text == null) {
            return false;
        }
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String money(BigDecimal v) {
        if (v == null) {
            return "0";
        }
        return v.stripTrailingZeros().toPlainString();
    }

    private static String ratio(BigDecimal v) {
        if (v == null) {
            return "—";
        }
        // 费效比保留 4 位（与月报口径一致，避免 0.005 被抹成 0），产出为 0 记 0
        return v.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal v) {
        if (v == null) {
            return "—";
        }
        return v.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
