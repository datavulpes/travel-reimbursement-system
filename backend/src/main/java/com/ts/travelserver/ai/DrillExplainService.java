package com.ts.travelserver.ai;

import com.ts.travelserver.config.AiConfig;
import com.ts.travelserver.service.AnalyticsService;
import com.ts.travelserver.vo.AiPointVO;
import com.ts.travelserver.vo.DrillExplainVO;
import com.ts.travelserver.vo.DrillVO;
import com.ts.travelserver.vo.MonthlyReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 钻取解释：管理层点开某个部门，问「这个月为什么变了」。
 *
 * <h3>与月度洞察的三点不同</h3>
 * <ol>
 *   <li><b>按次触发</b>：只有人点了「看分析」才调一次，切部门切月份都不自动跑。
 *       这不是为了省那几毛钱，而是因为它是「人问了一句」才有的答复——
 *       主动冒出来，就又多了一块没人要求它出现的文字。</li>
 *   <li><b>不落库</b>：单次问答，不占 monthly_insight 那张表。它依赖的统计随时在变，
 *       存下来反而要多维护一份会过期的东西。</li>
 *   <li><b>样本门槛更低</b>（{@value #MIN_SAMPLE}）：一个部门一个月的单据天然比全公司少一个量级，
 *       拿全公司那道 5 单的门槛卡它，等于这个功能在大多数部门永远不生效。
 *       但低于 3 张单仍然不给——两三张单谈趋势就是瞎说。</li>
 * </ol>
 *
 * <h3>边界与月度洞察完全一致</h3>
 * 投入、产出、费效比、环比全部由 {@link AnalyticsService} 算好放进输入，
 * 模型只回答「为什么」。输出里出现任何输入中不存在的数字，整条丢弃。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrillExplainService {

    /** 已付款单据少于这个数，不给趋势性结论 */
    private static final int MIN_SAMPLE = 3;

    private static final int MAX_POINTS = 3;
    private static final int MAX_TEXT = 60;
    private static final int MAX_SUMMARY = 80;

    /** 单条结论要挂在一个真实字段上；前缀之外的字段一律视为凭空造依据 */
    private static final List<String> FIELD_PREFIXES = List.of(
            "ratio", "prevRatio", "delta", "fillRate", "paidCnt", "filledCnt", "applyCnt",
            "pay", "output", "type.", "biz.", "outputStruct", "noProgressRate");

    /** 环比变化超过这个幅度才说「变差 / 变好」 */
    private static final BigDecimal DELTA_ALERT = new BigDecimal("0.05");

    /** 放进输入的花钱最多的单据条数 */
    private static final int MAX_BIZ = 5;

    private final AnalyticsService analyticsService;
    private final AiService aiService;
    private final AiLogService aiLogService;
    private final AiConfig config;
    private final ObjectMapper objectMapper;

    /**
     * 出一份解释。模型不可用或校验不过时整份回落到规则结论，
     * 页面拿到的永远是一份能看的东西。
     */
    public DrillExplainVO explain(Long deptId, String month) {
        DrillExplainVO vo = new DrillExplainVO();
        vo.setDeptId(deptId);
        vo.setModel(aiService.model());
        vo.setPromptVersion(config.getPromptVersion());

        MonthlyReportVO cur = analyticsService.monthly(month, deptId);
        String monthKey = cur.getMonth() == null || cur.getMonth().isBlank() ? month : cur.getMonth();
        vo.setMonth(monthKey);
        String prevMonth = prevMonth(monthKey);
        vo.setPrevMonth(prevMonth);

        MonthlyReportVO.DeptRowVO self = firstRow(cur);
        vo.setDeptName(self == null ? null : self.getDeptName());

        BigDecimal ratio = AiFmt.nz(cur.getRatio());
        BigDecimal prevRatio = null;
        MonthlyReportVO prev = safeMonthly(prevMonth, deptId);
        if (prev != null && prev.getRatio() != null && prev.getPaidCnt() != null
                && prev.getPaidCnt() >= MIN_SAMPLE) {
            prevRatio = prev.getRatio();
        }
        vo.setRatio(ratio);
        vo.setPrevRatio(prevRatio);
        vo.setDelta(prevRatio == null ? null : ratio.subtract(prevRatio));

        int paidCnt = cur.getPaidCnt() == null ? 0 : cur.getPaidCnt();
        vo.setSampleCnt(paidCnt);
        vo.getHighlights().addAll(rulePoints(cur, prev, vo.getDelta(), paidCnt));
        vo.setSummary(ruleSummary(cur, prevRatio, vo.getDelta()));
        vo.setLocal(Boolean.TRUE);
        vo.getSources().addAll(sources(cur));

        boolean thin = paidCnt < MIN_SAMPLE;
        String inputJson = toJson(facts(cur, prev, vo, prevMonth, deptId));
        if (thin) {
            return vo;
        }

        Set<String> allowed = AiTextGuard.allowedNumbers(inputJson);
        AiTextResult result = aiService.complete(AiScene.DRILL_EXPLAIN, inputJson);
        String content = result == null ? null : result.getContent();
        if (result != null) {
            boolean ok = false;
            if (content != null) {
                JsonNode root = safeTree(content);
                List<AiPointVO> points = parsePoints(root, allowed);
                String summary = parseSummary(root, allowed);
                if (!points.isEmpty()) {
                    vo.getHighlights().clear();
                    vo.getHighlights().addAll(points);
                    ok = true;
                }
                if (summary != null) {
                    vo.setSummary(summary);
                }
            }
            String err = null;
            if (content == null) {
                err = "模型未返回内容（超时 / 不可用）";
            } else if (!ok) {
                err = "模型输出未通过数字 / 依据校验，整条丢弃";
            }
            aiLogService.write(AiScene.DRILL_EXPLAIN, monthKey + "|" + deptId, inputJson, content,
                    null, null, result.getCostMs(), ok, !ok,
                    err, result.getRequestTokens(), result.getResponseTokens());
            if (ok) {
                vo.setLocal(!aiService.remote());
            } else if (content != null) {
                log.info("钻取解释：模型输出未通过数字/依据校验，本次回落规则结论");
            }
        }
        return vo;
    }

    // ---------- 模型输出的解析 ----------

    private JsonNode safeTree(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            log.warn("钻取解释解析失败：{}", e.getMessage());
            return null;
        }
    }

    private List<AiPointVO> parsePoints(JsonNode root, Set<String> allowed) {
        if (root == null) {
            return List.of();
        }
        JsonNode arr = root.get("highlights");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<AiPointVO> out = new ArrayList<>();
        for (int i = 0; i < arr.size() && out.size() < MAX_POINTS; i++) {
            JsonNode node = arr.get(i);
            String level = AiTextGuard.text(node, "level");
            String body = AiTextGuard.text(node, "text");
            JsonNode ev = node.get("evidence");
            String field = ev == null ? null : AiTextGuard.text(ev, "field");
            String value = ev == null ? null : AiTextGuard.text(ev, "value");
            if (!AiTextGuard.okPoint(level, body, field, value, allowed, FIELD_PREFIXES, MAX_TEXT)) {
                continue;
            }
            out.add(AiPointVO.of(level, body.trim(), field.trim(), value.trim(), AiPointVO.SRC_MODEL));
        }
        return out;
    }

    private String parseSummary(JsonNode root, Set<String> allowed) {
        if (root == null) {
            return null;
        }
        String summary = AiTextGuard.text(root, "summary");
        if (summary == null || summary.isBlank() || summary.length() > MAX_SUMMARY) {
            return null;
        }
        return AiTextGuard.numbersAllowed(summary, allowed) ? summary.trim() : null;
    }

    // ---------- 输入 ----------

    private Map<String, Object> facts(MonthlyReportVO cur, MonthlyReportVO prev,
                                      DrillExplainVO vo, String prevMonth, Long deptId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dept", AiFmt.str(vo.getDeptName()));
        input.put("month", AiFmt.str(cur.getMonth()));
        input.put("prevMonth", AiFmt.str(prevMonth));
        input.put("pay", AiFmt.money(cur.getTotalPay()));
        input.put("output", AiFmt.money(cur.getTotalOutput()));
        input.put("ratio", AiFmt.r(cur.getRatio()));
        input.put("prevRatio", vo.getPrevRatio() == null ? null : AiFmt.r(vo.getPrevRatio()));
        input.put("delta", vo.getDelta() == null ? null : AiFmt.r(vo.getDelta()));
        input.put("applyCnt", cur.getApplyCnt());
        input.put("paidCnt", cur.getPaidCnt());
        input.put("filledCnt", cur.getFilledCnt());
        input.put("fillRate", cur.getFillRate() == null ? null : AiFmt.pct(cur.getFillRate()));
        input.put("attentionCnt", cur.getAttentionCnt());

        List<Map<String, Object>> types = new ArrayList<>();
        for (MonthlyReportVO.TypeRowVO row : cur.getTypeRows() == null
                ? List.<MonthlyReportVO.TypeRowVO>of() : cur.getTypeRows()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.getLabel());
            item.put("pay", AiFmt.money(row.getTotalPay()));
            item.put("output", AiFmt.money(row.getTotalOutput()));
            item.put("ratio", AiFmt.r(row.getRatio()));
            types.add(item);
        }
        input.put("types", types);

        MonthlyReportVO.OutputStructVO struct = cur.getOutputStruct();
        if (struct != null) {
            Map<String, Object> os = new LinkedHashMap<>();
            os.put("totalCnt", struct.getTotalCnt());
            os.put("noProgressRate", struct.getNoProgressRate() == null
                    ? null : AiFmt.pct(struct.getNoProgressRate()));
            input.put("outputStruct", os);
        }

        input.put("topBiz", topBiz(deptId, AiFmt.str(cur.getMonth())));
        return input;
    }

    /**
     * 花钱最多的几张单。给模型看具体单据，它才说得出「为什么」——
     * 只给汇总数字，它只能把数字复述一遍。
     */
    private List<Map<String, Object>> topBiz(Long deptId, String month) {
        try {
            DrillVO drill = analyticsService.drill(deptId, month);
            if (drill == null || drill.getRows() == null) {
                return List.of();
            }
            List<DrillVO.DrillRowVO> rows = new ArrayList<>(drill.getRows().stream()
                    .filter(r -> r != null && r.getPayAmount() != null
                            && r.getPayAmount().compareTo(BigDecimal.ZERO) > 0)
                    .toList());
            rows.sort(Comparator.comparing((DrillVO.DrillRowVO r) -> r.getPayAmount()).reversed());
            List<Map<String, Object>> out = new ArrayList<>();
            for (DrillVO.DrillRowVO row : rows.subList(0, Math.min(MAX_BIZ, rows.size()))) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("bizNo", AiFmt.str(row.getBizNo()));
                item.put("name", AiFmt.str(row.getName()));
                item.put("pay", AiFmt.money(row.getPayAmount()));
                item.put("output", AiFmt.money(row.getOutputAmount()));
                item.put("ratio", AiFmt.r(row.getRatio()));
                out.add(item);
            }
            return out;
        } catch (Exception e) {
            log.info("钻取解释取单据明细失败，本次只给汇总数字：{}", e.getMessage());
            return List.of();
        }
    }

    // ---------- 规则结论：兜底，也是样本不足时的唯一来源 ----------

    private List<AiPointVO> rulePoints(MonthlyReportVO cur, MonthlyReportVO prev,
                                       BigDecimal delta, int paidCnt) {
        List<AiPointVO> out = new ArrayList<>();
        if (paidCnt < MIN_SAMPLE) {
            out.add(rule("low", "这个部门本月已付款单据只有 " + paidCnt + " 单，不足 " + MIN_SAMPLE
                    + " 单，样本太小，这里不给趋势性结论", "paidCnt", String.valueOf(paidCnt)));
            return out;
        }
        if (prev != null && delta != null) {
            if (delta.compareTo(DELTA_ALERT) > 0) {
                out.add(rule("high", "费效比 " + AiFmt.r(cur.getRatio()) + "，较上月 "
                        + AiFmt.r(prev.getRatio()) + " 变差", "ratio",
                        AiFmt.r(prev.getRatio()) + " → " + AiFmt.r(cur.getRatio())));
            } else if (delta.compareTo(DELTA_ALERT.negate()) < 0) {
                out.add(rule("low", "费效比 " + AiFmt.r(cur.getRatio()) + "，较上月 "
                        + AiFmt.r(prev.getRatio()) + " 变好", "ratio",
                        AiFmt.r(prev.getRatio()) + " → " + AiFmt.r(cur.getRatio())));
            } else {
                out.add(rule("low", "费效比 " + AiFmt.r(cur.getRatio()) + "，与上月基本持平",
                        "ratio", AiFmt.r(cur.getRatio())));
            }
        }
        BigDecimal fillRate = cur.getFillRate();
        if (fillRate != null && fillRate.compareTo(new BigDecimal("0.60")) < 0) {
            out.add(rule("mid", "已付款 " + paidCnt + " 单里只回填了 "
                    + (cur.getFilledCnt() == null ? 0 : cur.getFilledCnt()) + " 单产出，回填率 "
                    + AiFmt.pct(fillRate), "fillRate", AiFmt.pct(fillRate)));
        }
        MonthlyReportVO.OutputStructVO struct = cur.getOutputStruct();
        if (struct != null && struct.getNoProgressRate() != null
                && struct.getNoProgressRate().compareTo(new BigDecimal("0.30")) >= 0) {
            out.add(rule("mid", "回填的业务结果里「无进展」占 " + AiFmt.pct(struct.getNoProgressRate()),
                    "outputStruct.noProgressRate", AiFmt.pct(struct.getNoProgressRate())));
        }
        return out.size() > MAX_POINTS ? out.subList(0, MAX_POINTS) : out;
    }

    private String ruleSummary(MonthlyReportVO cur, BigDecimal prevRatio, BigDecimal delta) {
        StringBuilder sb = new StringBuilder();
        sb.append("投入 ¥").append(AiFmt.money(cur.getTotalPay()))
                .append("、产出 ¥").append(AiFmt.money(cur.getTotalOutput()))
                .append("，费效比 ").append(AiFmt.r(cur.getRatio()));
        if (prevRatio != null && delta != null) {
            sb.append("（上月 ").append(AiFmt.r(prevRatio)).append("，")
                    .append(delta.compareTo(BigDecimal.ZERO) >= 0 ? "变差 " : "变好 ")
                    .append(AiFmt.r(delta.abs())).append("）");
        }
        return sb.append("。").toString();
    }

    private AiPointVO rule(String level, String text, String field, String value) {
        return AiPointVO.of(level, text, field, value, AiPointVO.SRC_RULE);
    }

    private List<String> sources(MonthlyReportVO cur) {
        String m = AiFmt.str(cur.getMonth());
        List<String> out = new ArrayList<>();
        out.add("payment_record " + m + " 该部门已付款 "
                + (cur.getPaidCnt() == null ? 0 : cur.getPaidCnt()) + " 单，投入 ¥"
                + AiFmt.money(cur.getTotalPay()));
        out.add("biz_result " + m + " 该部门产出登记，产出 ¥" + AiFmt.money(cur.getTotalOutput())
                + "，其中已回填 " + (cur.getFilledCnt() == null ? 0 : cur.getFilledCnt()) + " 单");
        out.add("trip_apply " + m + " 该部门申请 "
                + (cur.getApplyCnt() == null ? 0 : cur.getApplyCnt()) + " 单");
        return out;
    }

    // ---------- 小工具 ----------

    private MonthlyReportVO.DeptRowVO firstRow(MonthlyReportVO cur) {
        if (cur.getRows() == null || cur.getRows().isEmpty()) {
            return null;
        }
        return cur.getRows().get(0);
    }

    private String prevMonth(String month) {
        try {
            LocalDate date = LocalDate.parse(month == null || month.isBlank()
                    ? LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")) + "-01"
                    : month.trim() + "-01");
            return date.minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        } catch (Exception e) {
            return null;
        }
    }

    /** 上月数据取不到不影响本月解释，只是少了环比 */
    private MonthlyReportVO safeMonthly(String month, Long deptId) {
        if (month == null) {
            return null;
        }
        try {
            return analyticsService.monthly(month, deptId);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
