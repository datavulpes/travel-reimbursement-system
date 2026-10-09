package com.ts.travelserver.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.config.AiConfig;
import com.ts.travelserver.entity.AiLog;
import com.ts.travelserver.entity.MonthlyInsight;
import com.ts.travelserver.mapper.AiLogMapper;
import com.ts.travelserver.mapper.MonthlyInsightMapper;
import com.ts.travelserver.service.AnalyticsService;
import com.ts.travelserver.vo.AiPointVO;
import com.ts.travelserver.vo.MonthlyInsightVO;
import com.ts.travelserver.vo.MonthlyReportVO;
import com.ts.travelserver.vo.PartnerRoiVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理层月度洞察：把费效比月报的统计数字，变成几条能直接看的结论。
 *
 * <h3>三层，可信度依次递减</h3>
 * <ol>
 *   <li><b>事实层</b>：投入、产出、费效比、结构占比，全部来自 {@link AnalyticsService}，
 *       可复现、可验算，是这份洞察里唯一"能拿来拍板"的部分。</li>
 *   <li><b>归因层</b>：费效比环比变化按「部门 / 出差类型 / 往来对象」做一阶分解，
 *       算出每一份对变化的贡献。这是纯算术，不进模型——分解错了没法跟管理层解释。</li>
 *   <li><b>解读层</b>：模型负责挑重点、组织措辞。<b>它不许算数</b>：
 *       输出里任何一个数字只要不在输入里出现，整条结论就被丢弃（见 {@link #allowedNumbers}）。</li>
 * </ol>
 *
 * <h3>为什么坚持"数字不进模型"</h3>
 * 月报上的每一个数后面都连着钱。模型算错一个数，看的人无从判断它对不对；
 * 而"少说一句"的代价只是页面短一点。所以校验不通过就整条回落规则模板，
 * 宁可结论生硬，也不给一个来源不明的数字。
 *
 * <h3>样本门槛</h3>
 * 已付款单据不足 {@value #MIN_SAMPLE} 单时直接不调模型：三张单算出来的"趋势"
 * 没有意义，对着几条数据大发议论是这套系统最该避免的事。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyInsightService {

    /** 已付款单据少于这个数，不给趋势性结论 */
    private static final int MIN_SAMPLE = 5;

    /** 回填率低于这个值要显式提醒：结论只覆盖了部分单据 */
    private static final BigDecimal LOW_FILL = new BigDecimal("0.60");

    /**
     * 样本充足的分界线：已付款单据到了这个量级，就不再因为样本量扣置信度。
     *
     * <p>取 MIN_SAMPLE 的两倍是刻意的：过了 MIN_SAMPLE 只是「够开口」，
     * 离「说得准」还差一截。定得更宽（比如 15）会让常见月份一律落在中间档，
     * 标签永远同一个颜色，等于没有信息量。
     */
    private static final int SAMPLE_FULL = 10;

    /** 置信度分档门槛：0.70 以上算充足，0.40 以下算偏低 */
    private static final double CONF_AMPLE = 0.70;
    private static final double CONF_FAIR = 0.40;

    /**
     * 回填率对置信度的折扣下限。产出没回填确实让费效比虚高，
     * 但回填率为 0 不等于整份报告都在胡说，所以不让它把置信度压到接近 0。
     */
    private static final BigDecimal FILL_FLOOR = new BigDecimal("0.50");

    /** 无进展率超过这个值值得单独提一条 */
    private static final BigDecimal NO_PROGRESS_ALERT = new BigDecimal("0.30");

    /** 费效比环比变化超过这个幅度，才说"变差 / 变好" */
    private static final BigDecimal DELTA_ALERT = new BigDecimal("0.05");

    /** 部门费效比超过这个值与页面色阶一致：0.45 以上标红 */
    private static final BigDecimal WORST_THRESHOLD = new BigDecimal("0.45");

    private static final int MAX_POINTS = 4;
    private static final int MAX_DRIVERS = 3;
    private static final int MAX_TYPES = 5;

    /** 模型单条结论的长度上限；超过说明它在自由发挥 */
    private static final int MAX_TEXT = 60;
    private static final int MAX_SUMMARY = 120;

    /** 最多给几条下一步动作，以及每条说明的长度上限 */
    private static final int MAX_ACTIONS = 3;
    private static final int MAX_ACTION_DETAIL = 30;

    /**
     * 允许的动作代码白名单。模型只能从这里选，写别的整条丢。
     *
     * <p>为什么只给这六个：它们都是「去查一件事」，没有一个是处置决定。
     * 暂停审批、扣预算、处分这类动作系统里根本做不到，让模型写出来了，
     * 页面上就是一条既兑现不了、又显得管理层失职的空话。
     */
    private static final Map<String, String> ACTION_CODES = Map.of(
            MonthlyInsightVO.ACT_REVIEW_COST, "复核费用明细",
            MonthlyInsightVO.ACT_CHECK_OUTPUT, "补录产出",
            MonthlyInsightVO.ACT_AUDIT_INVOICE, "抽查票据",
            MonthlyInsightVO.ACT_TALK_DEPT, "对齐下月计划",
            MonthlyInsightVO.ACT_ADJUST_BUDGET, "调整下月预算",
            MonthlyInsightVO.ACT_WATCH_PARTNER, "继续观察");

    /** 归因分解保留 6 位，展示时再压到 4 位 */
    private static final int SCALE = 6;

    /**
     * evidence.field 允许的前缀。模型只能把结论挂在这些字段上，
     * 挂到一个不存在的字段等于凭空造依据。
     */
    private static final List<String> FIELD_PREFIXES = List.of(
            "ratio", "prevRatio", "delta", "fillRate", "paidCnt", "filledCnt",
            "attentionCnt", "attentionRatio", "applyCnt", "blockedAmount",
            "totalPay", "totalOutput", "dept.", "type.", "partner.", "outputStruct", "driver.");

    private final AnalyticsService analyticsService;
    private final AiService aiService;
    private final AiCache aiCache;
    private final AiLogService aiLogService;
    private final AiLogMapper aiLogMapper;
    private final AiConfig config;
    private final ObjectMapper objectMapper;
    private final MonthlyInsightMapper monthlyInsightMapper;

    /**
     * 读一份报告：库里有就直接返回那份快照，没有才现生成一份。
     *
     * <p>这一层<b>刻意不自动重算</b>。哪怕底下的统计数字已经变了，也先把上次那份原样交回去，
     * 只用 {@code stale} 提示「数据已更新」，要不要重新分析由看报告的人决定。两条理由：
     * 一是不能让一次页面刷新就悄悄烧掉一次模型调用；二是管理层常常对着同一份报告讨论，
     * 「报告会自己变」比「报告是旧的」麻烦得多。
     */
    public MonthlyInsightVO read(String month) {
        MonthlyInsight row = find(month);
        if (row == null) {
            return generate(month, MonthlyInsight.BY_AUTO, false);
        }
        MonthlyInsightVO vo = toVo(row);
        if (vo.getConfidence() == null || vo.getActions().isEmpty() || vo.getSources().isEmpty()) {
            backfill(vo);
        }
        String fp = fingerprint(month);
        // 指纹算不出来（统计层异常）时不装作知道，宁可不标「已更新」
        vo.setStale(Boolean.valueOf(fp != null && !fp.equals(row.getInputHash())));
        return vo;
    }

    /**
     * 手动重新分析。人点了按钮就一定重新跑模型，哪怕入参跟上次一字不差——
     * 「再要一份」是人的判断，不该由系统替他省这次调用。
     *
     * <p>唯一的例外仍是样本不足 {@link #MIN_SAMPLE}：那种情况下压根不调模型，
     * 对着三五张单议论趋势本来就没有意义，省的不是钱是误导。
     */
    public MonthlyInsightVO refresh(String month) {
        return generate(month, MonthlyInsight.BY_MANUAL, true);
    }

    /**
     * 每天出一份，当天已经有了就不重复出。
     *
     * <p>凌晨定时任务和「启动补跑」共用这一条路径，所以 0 点机器没开机这种漏跑，
     * 第二天服务一起来就自动补上了，不需要额外的追数逻辑。
     */
    public void ensureToday(String month) {
        MonthlyInsight row = find(month);
        if (row != null && row.getGeneratedAt() != null
                && !row.getGeneratedAt().toLocalDate().isBefore(LocalDate.now())) {
            return;
        }
        generate(month, MonthlyInsight.BY_SCHEDULED, false);
    }

    /**
     * 定时任务入口：先给当月出一份；逢每月 1 号再把上个月结算成终版。
     *
     * <p>为什么要单独照顾 1 号：上个月那份很可能是它「还在进行中」的时候生成的，
     * 月底最后几天的付款还没进来就被存档，等于永久留了个半成品。
     */
    public void runScheduled() {
        String cur = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        ensureToday(cur);
        if (LocalDate.now().getDayOfMonth() == 1) {
            String prev = prevMonth(cur);
            if (prev != null) {
                ensureToday(prev);
            }
        }
    }

    /**
     * 出一份洞察并存库。月报接口自己也会带一段模板结论，那份永远可用；
     * 这份是模型版，拿不到就回落，页面上两者共用同一张 AI 卡。
     *
     * @param by         生成来源，决定页面上标注的出处
     * @param forceModel true = 跳过缓存直接打模型，手动重新分析专用
     */
    private MonthlyInsightVO generate(String month, String by, boolean forceModel) {
        MonthlyInsightVO vo = new MonthlyInsightVO();
        vo.setModel(aiService.model());
        vo.setPromptVersion(config.getPromptVersion());

        MonthlyReportVO cur = analyticsService.monthly(month, null);
        // 页面可以不传月份（取当前月），落库得用统计层真正认定的那个月，否则 key 会串
        String monthKey = cur.getMonth() == null || cur.getMonth().isBlank() ? month : cur.getMonth();
        vo.setMonth(monthKey);
        String prevMonth = prevMonth(monthKey);
        vo.setPrevMonth(prevMonth);
        MonthlyReportVO prev = safeMonthly(prevMonth);

        BigDecimal ratio = nz(cur.getRatio());
        BigDecimal prevRatio = prev == null ? null : nz(prev.getRatio());
        vo.setRatio(ratio);
        vo.setPrevRatio(prevRatio);
        vo.setDelta(prevRatio == null ? null : ratio.subtract(prevRatio));
        List<MonthlyInsightVO.DriverVO> drv = drivers(cur, prev, prevMonth);
        boolean prevThin = prevThin(prev);
        vo.setDrivers(drv);

        int paidCnt = cur.getPaidCnt() == null ? 0 : cur.getPaidCnt();
        int filledCnt = cur.getFilledCnt() == null ? 0 : cur.getFilledCnt();
        BigDecimal fillRate = cur.getFillRate();
        boolean thin = paidCnt < MIN_SAMPLE;
        boolean lowFill = fillRate != null && fillRate.compareTo(LOW_FILL) < 0;
        vo.setSampleNote(note(paidCnt, filledCnt, fillRate, thin, lowFill, prevThin, prevMonth));

        List<AiPointVO> body = analyticPoints(cur, prev, vo.getDelta(), thin);
        String summary = summary(cur, prev, vo.getDelta());
        /*
         * 这一份到底有没有经过模型，决定页面角标写「模型输出」还是「本地演示」。
         * 不能只看通道是不是 remote：样本不足时压根没调模型，结论全是规则拼的，
         * 这时候标「模型输出」就是冒充——角标的唯一作用是让人知道该信到什么程度。
         */
        boolean usedModel = false;

        /*
         * 样本不足时不调模型：一是省一次调用，二是模型对着三五张单只会编出更像回事的废话。
         * 这时候页面拿到的就是一条「样本不足」的规则结论，来源照样如实标本地。
         */
        /*
         * 入参在这里就定下来：样本不足时同样要算，因为报告照样要落库
         * （留个「样本不足」的说明，页面才知道不是它加载慢），
         * 而 inputHash 是下一次判断「数据有没有变」的唯一依据。
         */
        String inputJson = toJson(facts(cur, prev, vo, prevMonth));
        Parsed parsed = Parsed.empty();
        /* 动作只能落到本次入参里真实存在的对象上，这个名单就是白名单 */
        Set<String> targets = allowedTargets(cur, vo);
        if (!thin) {
            Set<String> allowed = allowedNumbers(inputJson);

            /*
             * 手动重新分析（forceModel）刻意绕开缓存：人点了按钮就是要重新跑一遍，
             * 哪怕入参跟上次一字不差——这几毛钱不该由系统替管理层省。
             */
            String content = forceModel ? null : aiCache.get(AiScene.MONTHLY_INSIGHT, inputJson);
            AiTextResult result = null;
            if (content == null) {
                result = aiService.complete(AiScene.MONTHLY_INSIGHT, inputJson);
                content = result == null ? null : result.getContent();
                if (content != null) {
                    aiCache.put(AiScene.MONTHLY_INSIGHT, inputJson, content);
                }
            }
            parsed = content == null ? Parsed.empty() : parse(content, allowed, targets);
            if (result != null) {
                String err = null;
                if (content == null) {
                    err = "模型未返回内容（超时 / 不可用）";
                } else if (parsed.points.isEmpty()) {
                    err = "模型输出未通过数字 / 依据校验，整条丢弃";
                }
                aiLogService.write(AiScene.MONTHLY_INSIGHT, month, inputJson, content, null, null,
                        result.getCostMs(), !parsed.points.isEmpty(), parsed.points.isEmpty(),
                        err, result.getRequestTokens(), result.getResponseTokens());
            }
            if (parsed.points.isEmpty()) {
                if (content != null) {
                    log.info("月度洞察：模型输出未通过数字/依据校验，本次回落规则结论");
                }
            } else {
                body = parsed.points;
                usedModel = true;
                if (parsed.summary != null) {
                    summary = parsed.summary;
                }
            }
        }

        List<AiPointVO> all = new ArrayList<>();
        /*
         * 回填率提醒由规则出，保证模型不提也一定在；但模型经常也会提同一件事，
         * 两条挨着说一遍显得啰嗦——它已经挂到 fillRate 上时就不重复加了。
         */
        boolean modelMentionedFill = body.stream()
                .anyMatch(p -> p.getEvidence() != null && "fillRate".equals(p.getEvidence().getField()));
        if (lowFill && !modelMentionedFill) {
            all.add(rule("mid", "产出回填率 " + pct(fillRate) + "（" + filledCnt + "/" + paidCnt
                    + " 单），这份月报只覆盖了部分已付款单据", "fillRate", pct(fillRate)));
        }
        all.addAll(body);
        vo.getHighlights().addAll(all.size() > MAX_POINTS + 1 ? all.subList(0, MAX_POINTS + 1) : all);
        /*
         * 动作只在模型给出可用结论时才用它的：规则兜底那套结论是"照着统计层念一遍"，
         * 念完还不清楚该去查什么。模型一条都没站住时，页面就只有结论没有动作——
         * 少一块总比给几条对不上号的动作强。
         */
        vo.getActions().addAll(usedModel ? parsed.actions() : ruleActions(cur, vo, paidCnt));
        vo.getSources().addAll(buildSources(cur));
        vo.setSummary(summary);
        vo.setLocal(!(usedModel && aiService.remote()));
        vo.setSampleCnt(paidCnt);
        /*
         * 置信度只在模型结论真的上了页面时才乘「校验通过率」。
         * 回落成规则结论时，页面上的数字本来就是硬算的，
         * 模型有多少条没站住跟这份结论没关系，不该让它背这个分。
         */
        double checkRate = usedModel && parsed.total() > 0
                ? (double) parsed.points().size() / parsed.total()
                : 1.0;
        vo.setConfidence(confidence(paidCnt, fillRate, checkRate));
        vo.setConfidenceLevel(levelOf(vo.getConfidence()));

        /*
         * 落库失败不能拖垮这一次展示：报告本质是缓存产物，内存里这份照样能用，
         * 下次访问自然会重试。这与 AI 留痕同一条原则——任何 side effect 都不能卡主流程。
         */
        /*
         * 纳秒去掉是为了保证「这次刚生成」与「下次从库里读出来」两个返回值完全一致。
         * MySQL 的 DATETIME 默认不存小数秒，带着纳秒回来会让页面在同一个报告上
         * 看到两个不一样的时间戳，看着像报告被重算过。
         */
        LocalDateTime now = LocalDateTime.now().withNano(0);
        MonthlyInsight row = upsert(vo, aiCache.key(AiScene.MONTHLY_INSIGHT, inputJson), by, now);
        vo.setGeneratedAt(row == null ? now : row.getGeneratedAt());
        vo.setGeneratedBy(row == null ? by : row.getGeneratedBy());
        vo.setStale(Boolean.FALSE);
        return vo;
    }

    // ---------- 报告的存取 ----------

    /**
     * 按月份取库里那份。失败一律当作「没有」处理，
     * 让流程退化到重新生成，而不是把异常抛到页面上。
     */
    private MonthlyInsight find(String month) {
        if (month == null || month.isBlank()) {
            return null;
        }
        try {
            return monthlyInsightMapper.selectOne(
                    new LambdaQueryWrapper<MonthlyInsight>().eq(MonthlyInsight::getMonth, month.trim()));
        } catch (Exception e) {
            log.warn("月度洞察报告读取失败，本次按没有旧报告处理：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入当月这条。一个月一行（uk_month），有则更新无则插入。
     */
    private MonthlyInsight upsert(MonthlyInsightVO vo, String inputHash, String by, LocalDateTime now) {
        try {
            MonthlyInsight row = find(vo.getMonth());
            if (row == null) {
                row = new MonthlyInsight();
            }
            row.setMonth(vo.getMonth());
            row.setInputHash(inputHash);
            row.setSummary(vo.getSummary());
            row.setHighlightsJson(toJson(vo.getHighlights()));
            row.setDriversJson(toJson(vo.getDrivers()));
            row.setActionsJson(toJson(vo.getActions()));
            row.setSourcesJson(toJson(vo.getSources()));
            row.setRatio(vo.getRatio());
            row.setPrevRatio(vo.getPrevRatio());
            row.setDelta(vo.getDelta());
            row.setPrevMonth(vo.getPrevMonth());
            row.setSampleNote(vo.getSampleNote());
            row.setSampleCnt(vo.getSampleCnt());
            row.setConfidence(vo.getConfidence() == null ? null : BigDecimal.valueOf(vo.getConfidence()));
            row.setLocalFlag(Boolean.TRUE.equals(vo.getLocal()) ? 1 : 0);
            row.setModel(vo.getModel());
            row.setPromptVersion(vo.getPromptVersion());
            row.setGeneratedAt(now);
            row.setGeneratedBy(by);
            if (row.getId() == null) {
                monthlyInsightMapper.insert(row);
            } else {
                monthlyInsightMapper.updateById(row);
            }
            return row;
        } catch (Exception e) {
            log.warn("月度洞察报告落库失败（不影响本次展示）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 库里那条还原成 VO。这里手动按字段取值而不用反序列化成具体类型，
     * 是为了避开 Boot 4 里 Jackson 版本带来的泛型解析差异——
     * 字段一共就那么几个，逐个取反而更稳。
     */
    private MonthlyInsightVO toVo(MonthlyInsight row) {
        MonthlyInsightVO vo = new MonthlyInsightVO();
        vo.setMonth(row.getMonth());
        vo.setPrevMonth(row.getPrevMonth());
        vo.setRatio(row.getRatio());
        vo.setPrevRatio(row.getPrevRatio());
        vo.setDelta(row.getDelta());
        vo.setSummary(row.getSummary());
        vo.setSampleNote(row.getSampleNote());
        vo.setSampleCnt(row.getSampleCnt());
        vo.setConfidence(row.getConfidence() == null ? null : row.getConfidence().doubleValue());
        vo.setConfidenceLevel(levelOf(vo.getConfidence()));
        vo.setLocal(row.getLocalFlag() == null || row.getLocalFlag() == 1);
        vo.setModel(row.getModel());
        vo.setPromptVersion(row.getPromptVersion());
        vo.setGeneratedAt(row.getGeneratedAt());
        vo.setGeneratedBy(row.getGeneratedBy());
        vo.getHighlights().addAll(readPoints(row.getHighlightsJson()));
        vo.getDrivers().addAll(readDrivers(row.getDriversJson()));
        vo.getActions().addAll(readActions(row.getActionsJson()));
        vo.getSources().addAll(readSources(row.getSourcesJson()));
        return vo;
    }

    private List<AiPointVO> readPoints(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr == null) {
                return List.of();
            }
            List<AiPointVO> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JsonNode node = arr.get(i);
                JsonNode ev = node.get("evidence");
                out.add(AiPointVO.of(text(node, "level"), text(node, "text"),
                        ev == null ? null : text(ev, "field"),
                        ev == null ? null : text(ev, "value"),
                        text(node, "source")));
            }
            return out;
        } catch (Exception e) {
            log.warn("月度洞察要点还原失败，本次按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    private List<MonthlyInsightVO.DriverVO> readDrivers(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr == null) {
                return List.of();
            }
            List<MonthlyInsightVO.DriverVO> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JsonNode node = arr.get(i);
                MonthlyInsightVO.DriverVO vo = new MonthlyInsightVO.DriverVO();
                vo.setDimension(text(node, "dimension"));
                vo.setDimensionText(text(node, "dimensionText"));
                vo.setName(text(node, "name"));
                vo.setContribution(dec(node, "contribution"));
                vo.setPay(dec(node, "pay"));
                vo.setOutput(dec(node, "output"));
                vo.setPayDelta(dec(node, "payDelta"));
                vo.setOutputDelta(dec(node, "outputDelta"));
                vo.setText(text(node, "text"));
                out.add(vo);
            }
            return out;
        } catch (Exception e) {
            log.warn("月度洞察归因还原失败，本次按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    private List<MonthlyInsightVO.ActionVO> readActions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr == null) {
                return List.of();
            }
            List<MonthlyInsightVO.ActionVO> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JsonNode node = arr.get(i);
                MonthlyInsightVO.ActionVO vo = new MonthlyInsightVO.ActionVO();
                vo.setCode(text(node, "code"));
                vo.setCodeText(text(node, "codeText"));
                vo.setTarget(text(node, "target") == null ? "" : text(node, "target"));
                vo.setDetail(text(node, "detail"));
                vo.setAdopted("true".equalsIgnoreCase(text(node, "adopted")));
                vo.setBasis(text(node, "basis"));
                out.add(vo);
            }
            return out;
        } catch (Exception e) {
            log.warn("月度洞察动作还原失败，本次按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    private List<String> readSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                String one = arr.get(i) == null ? null : arr.get(i).asString();
                if (one != null && !one.isBlank()) {
                    out.add(one);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("月度洞察数据出处还原失败，本次按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 勾掉或取消一条动作。
     *
     * <p>这个勾选是「采纳闭环」的最后一环：只在前端本地改一下的话，
     * 刷新就没了，等于没记——而这份报告有没有真的推动过一件事，靠的就是它。
     *
     * <p>按 (code, target) 定位而不是下标：动作每次重新分析都会重排，
     * 用下标会把勾选记到另一条动作上。
     */
    public boolean setActionAdopted(String month, String code, String target, boolean adopted) {
        MonthlyInsight row = find(month);
        if (row == null) {
            return false;
        }
        List<MonthlyInsightVO.ActionVO> actions = actionsOf(row);
        String want = target == null ? "" : target.trim();
        boolean hit = false;
        for (MonthlyInsightVO.ActionVO a : actions) {
            if (code != null && code.equals(a.getCode())
                    && want.equals(a.getTarget() == null ? "" : a.getTarget())) {
                a.setAdopted(Boolean.valueOf(adopted));
                hit = true;
            }
        }
        if (!hit) {
            return false;
        }
        row.setActionsJson(toJson(actions));
        try {
            monthlyInsightMapper.updateById(row);
            return true;
        } catch (Exception e) {
            log.warn("动作勾选落库失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 记下「这份洞察有没有用」。
     *
     * <p>写回当月那一条 {@code MONTHLY_INSIGHT} 留痕的 accepted，
     * 与动作勾选分开放：动作是「报告让我去查的那件事做没做」，
     * 这里是「这份报告本身值不值得看」——一个评价业务执行，一个评价 AI 产出。
     *
     * <p>找不到当月留痕（比如这份报告是定时任务出的、留痕关着）就返回 false，
     * 不新建一条：留痕的意义是记「那一次调用」，凭空造一行等于伪造历史。
     */
    public boolean setFeedback(String month, boolean helpful) {
        if (month == null || month.isBlank()) {
            return false;
        }
        AiLog row = null;
        try {
            row = aiLogMapper.selectOne(new LambdaQueryWrapper<AiLog>()
                    .eq(AiLog::getScene, AiScene.MONTHLY_INSIGHT)
                    .eq(AiLog::getRefNo, month)
                    .orderByDesc(AiLog::getId)
                    .last("limit 1"));
        } catch (Exception e) {
            log.warn("洞察反馈查询失败：{}", e.getMessage());
            return false;
        }
        if (row == null) {
            return false;
        }
        aiLogService.markAccepted(AiScene.MONTHLY_INSIGHT, month, helpful);
        return true;
    }

    /**
     * 这份报告当前挂着哪些动作。
     *
     * <p>库里没有（加这个字段之前生成的老报告）时，用当前统计补出规则版那几条再写回去——
     * 否则这一次勾选无处可落，人勾了、刷新就没了，等于骗他一下。
     * 补的时候刻意不调模型：勾一个框不该烧一次模型调用。
     */
    private List<MonthlyInsightVO.ActionVO> actionsOf(MonthlyInsight row) {
        List<MonthlyInsightVO.ActionVO> actions = new ArrayList<>(readActions(row.getActionsJson()));
        if (!actions.isEmpty()) {
            return actions;
        }
        try {
            MonthlyReportVO cur = analyticsService.monthly(row.getMonth(), null);
            int paidCnt = cur.getPaidCnt() == null ? 0 : cur.getPaidCnt();
            actions.addAll(ruleActions(cur, new MonthlyInsightVO(), paidCnt));
        } catch (Exception e) {
            log.info("补出动作失败，本次勾选无法落库：{}", e.getMessage());
        }
        return actions;
    }

    private static BigDecimal dec(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 当前这份统计的入参指纹，用来判断已存的那份是不是过时了。
     *
     * <p>读路径要为此重算一遍统计（比白读多四五条查询），换的是一个准确判断：
     * 用「今天有没有生成过」这种粗口径当替代品，会让早上补录的一笔付款被压一整天，
     * 页面却什么都不说。指纹虽然贵，但它是唯一诚实的依据。
     */
    private String fingerprint(String month) {
        try {
            MonthlyReportVO cur = analyticsService.monthly(month, null);
            String key = cur.getMonth() == null || cur.getMonth().isBlank() ? month : cur.getMonth();
            String prevMonth = prevMonth(key);
            MonthlyReportVO prev = safeMonthly(prevMonth);
            MonthlyInsightVO probe = new MonthlyInsightVO();
            BigDecimal ratio = nz(cur.getRatio());
            probe.setRatio(ratio);
            probe.setPrevRatio(prev == null ? null : nz(prev.getRatio()));
            probe.setDelta(probe.getPrevRatio() == null ? null : ratio.subtract(probe.getPrevRatio()));
            probe.setDrivers(drivers(cur, prev, prevMonth));
            return aiCache.key(AiScene.MONTHLY_INSIGHT, toJson(facts(cur, prev, probe, prevMonth)));
        } catch (Exception e) {
            log.warn("月度洞察指纹计算失败，本次不判定是否过期：{}", e.getMessage());
            return null;
        }
    }

    // ---------- 归因层：费效比环比变化的一阶分解 ----------

    /**
     * 把"本月费效比比上月差了多少"拆到具体的部门、出差类型、往来对象上。
     *
     * <p>用的是一阶分解：{@code ratio = 投入 ÷ 产出}，则
     * <pre>
     * Δratio ≈ Δ投入 ÷ 产出₀ − 投入₀ × Δ产出 ÷ 产出₀²
     * </pre>
     * 同一维度内各项贡献之和就是整体的变化量，这也是它比"哪个部门比值高"更管用的地方——
     * 比值高可能是常年如此，贡献大才是这个月真的变差了。
     *
     * <p>上月产出为 0 时不做分解：分母为 0，算出来的贡献没有意义，
     * 这时候宁可不给归因，也不要给一个看着很专业的错数。
     *
     * <p>上月样本不足 {@link #MIN_SAMPLE} 单时同样不做：见 {@link #prevThin}。
     */
    private List<MonthlyInsightVO.DriverVO> drivers(MonthlyReportVO cur, MonthlyReportVO prev, String prevMonth) {
        if (prev == null || prevThin(prev)) {
            return List.of();
        }
        BigDecimal p0 = nz(prev.getTotalPay());
        BigDecimal o0 = nz(prev.getTotalOutput());
        if (o0.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }

        List<Grp> curAll = new ArrayList<>();
        curAll.addAll(deptGroups(cur));
        curAll.addAll(typeGroups(cur));
        curAll.addAll(partnerGroups(analyticsService.partner(cur.getMonth())));

        List<Grp> prevAll = new ArrayList<>();
        prevAll.addAll(deptGroups(prev));
        prevAll.addAll(typeGroups(prev));
        prevAll.addAll(partnerGroups(analyticsService.partner(prevMonth)));

        Map<String, Grp> prevByName = new HashMap<>();
        for (Grp g : prevAll) {
            prevByName.merge(key(g), g, (a, b) -> new Grp(a.dim(), a.dimText(), a.name(),
                    a.pay().add(b.pay()), a.output().add(b.output())));
        }

        List<MonthlyInsightVO.DriverVO> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Grp g : curAll) {
            seen.add(key(g));
            out.add(driver(g, prevByName.getOrDefault(key(g),
                    new Grp(g.dim(), g.dimText(), g.name(), BigDecimal.ZERO, BigDecimal.ZERO)), p0, o0));
        }
        // 上月有、本月归零的也要算进来：整类出差停了，同样是把比值拉动的原因
        for (Grp g : prevAll) {
            if (seen.contains(key(g))) {
                continue;
            }
            out.add(driver(new Grp(g.dim(), g.dimText(), g.name(), BigDecimal.ZERO, BigDecimal.ZERO), g, p0, o0));
        }

        BigDecimal delta = nz(cur.getRatio()).subtract(nz(prev.getRatio()));
        if (delta.compareTo(BigDecimal.ZERO) > 0) {
            out.sort(Comparator.comparing((MonthlyInsightVO.DriverVO d) -> nz(d.getContribution())).reversed());
        } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
            out.sort(Comparator.comparing((MonthlyInsightVO.DriverVO d) -> nz(d.getContribution())));
        } else {
            out.sort(Comparator.comparing((MonthlyInsightVO.DriverVO d) -> nz(d.getContribution()).abs()).reversed());
        }
        return out.size() > MAX_DRIVERS ? out.subList(0, MAX_DRIVERS) : out;
    }

    private MonthlyInsightVO.DriverVO driver(Grp g, Grp prev, BigDecimal p0, BigDecimal o0) {
        BigDecimal dPay = g.pay().subtract(prev.pay());
        BigDecimal dOut = g.output().subtract(prev.output());
        BigDecimal contribution = dPay.divide(o0, SCALE, RoundingMode.HALF_UP)
                .subtract(p0.multiply(dOut).divide(o0.multiply(o0), SCALE, RoundingMode.HALF_UP));

        MonthlyInsightVO.DriverVO vo = new MonthlyInsightVO.DriverVO();
        vo.setDimension(g.dim());
        vo.setDimensionText(g.dimText());
        vo.setName(g.name());
        vo.setContribution(contribution.setScale(4, RoundingMode.HALF_UP));
        vo.setPay(g.pay());
        vo.setOutput(g.output());
        vo.setPayDelta(dPay);
        vo.setOutputDelta(dOut);
        vo.setText(driverText(g, dPay, dOut, contribution));
        return vo;
    }

    private String driverText(Grp g, BigDecimal dPay, BigDecimal dOut, BigDecimal contribution) {
        boolean worse = contribution.compareTo(BigDecimal.ZERO) >= 0;
        return g.dimText() + "「" + g.name() + "」投入较上月 " + signed(dPay)
                + "、产出 " + signed(dOut) + "，把费效比" + (worse ? "推高 " : "拉低 ")
                + contribution.abs().setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    // ---------- 事实层：喂给模型的统计 ----------

    private Map<String, Object> facts(MonthlyReportVO cur, MonthlyReportVO prev,
                                      MonthlyInsightVO vo, String prevMonth) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("month", str(cur.getMonth()));
        input.put("prevMonth", str(prevMonth));
        input.put("pay", money(cur.getTotalPay()));
        input.put("output", money(cur.getTotalOutput()));
        input.put("ratio", r(cur.getRatio()));
        input.put("prevRatio", prev == null ? null : r(prev.getRatio()));
        input.put("delta", vo.getDelta() == null ? null : r(vo.getDelta()));
        input.put("applyCnt", cur.getApplyCnt());
        input.put("paidCnt", cur.getPaidCnt());
        input.put("filledCnt", cur.getFilledCnt());
        input.put("fillRate", cur.getFillRate() == null ? null : pct(cur.getFillRate()));
        input.put("attentionCnt", cur.getAttentionCnt());
        input.put("attentionRatio", cur.getAttentionRatio() == null ? null : pct(cur.getAttentionRatio()));
        input.put("blockedAmount", money(cur.getBlockedAmount()));

        MonthlyReportVO.DeptRowVO best = bestDept(cur);
        MonthlyReportVO.DeptRowVO worst = worstDept(cur);
        if (best != null) {
            input.put("deptBest", deptBrief(best));
        }
        input.put("deptWorst", worst == null ? null : deptBrief(worst));

        List<Map<String, Object>> types = new ArrayList<>();
        List<MonthlyReportVO.TypeRowVO> typeRows = cur.getTypeRows() == null ? List.of() : cur.getTypeRows();
        for (MonthlyReportVO.TypeRowVO row : typeRows.subList(0, Math.min(MAX_TYPES, typeRows.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.getLabel());
            item.put("pay", money(row.getTotalPay()));
            item.put("output", money(row.getTotalOutput()));
            item.put("ratio", r(row.getRatio()));
            item.put("applyCnt", row.getApplyCnt());
            types.add(item);
        }
        input.put("types", types);

        MonthlyReportVO.OutputStructVO struct = cur.getOutputStruct();
        if (struct != null) {
            Map<String, Object> os = new LinkedHashMap<>();
            os.put("totalCnt", struct.getTotalCnt());
            os.put("noProgressRate", struct.getNoProgressRate() == null
                    ? null : pct(struct.getNoProgressRate()));
            List<Map<String, Object>> items = new ArrayList<>();
            for (MonthlyReportVO.OutputItemVO item : struct.getItems() == null ? List.<MonthlyReportVO.OutputItemVO>of() : struct.getItems()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("resultType", item.getResultType());
                one.put("cnt", item.getCnt());
                one.put("amount", money(item.getAmount()));
                items.add(one);
            }
            os.put("items", items);
            input.put("outputStruct", os);
        }

        List<Map<String, Object>> drv = new ArrayList<>();
        for (MonthlyInsightVO.DriverVO d : vo.getDrivers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dim", d.getDimensionText());
            item.put("name", d.getName());
            item.put("contribution", r4(d.getContribution()));
            item.put("pay", money(d.getPay()));
            item.put("output", money(d.getOutput()));
            item.put("deltaPay", money(d.getPayDelta()));
            item.put("deltaOutput", money(d.getOutputDelta()));
            drv.add(item);
        }
        input.put("drivers", drv);
        return input;
    }

    // ---------- 解读层：模型输出的校验 ----------

    /**
     * 输入里出现过的数字（含常见的等价写法）。
     *
     * <p>模型可能把 0.45 写成 45%，也可能把 0.40 写成 0.4，所以每种写法都放进去。
     * 这不是为了放宽校验，是为了不让"写法不同"误杀一条本来正确的结论——
     * 真正要拦的是输入里压根不存在的数。
     */
    private Set<String> allowedNumbers(String inputJson) {
        return AiTextGuard.allowedNumbers(inputJson);
    }

    private Parsed parse(String content, Set<String> allowed, Set<String> targets) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null) {
                return Parsed.empty();
            }
            List<AiPointVO> points = new ArrayList<>();
            int total = 0;
            JsonNode arr = root.get("highlights");
            if (arr != null) {
                for (int i = 0; i < arr.size() && points.size() < MAX_POINTS; i++) {
                    total++;
                    JsonNode node = arr.get(i);
                    String level = text(node, "level");
                    String body = text(node, "text");
                    JsonNode ev = node == null ? null : node.get("evidence");
                    String field = ev == null ? null : text(ev, "field");
                    String value = ev == null ? null : text(ev, "value");
                    if (!okPoint(level, body, field, value, allowed)) {
                        continue;
                    }
                    points.add(AiPointVO.of(level, body.trim(), field.trim(), value.trim(),
                            AiPointVO.SRC_MODEL));
                }
            }
            String summary = text(root, "summary");
            if (summary != null && (summary.length() > MAX_SUMMARY || !numbersAllowed(summary, allowed))) {
                summary = null;
            }
            return new Parsed(points, summary, total, parseActions(root.get("actions"), allowed, targets));
        } catch (Exception e) {
            log.warn("月度洞察解析失败：{}", e.getMessage());
            return Parsed.empty();
        }
    }

    /**
     * 动作的白名单解析。三道闸，任何一道不过就整条丢：
     * 代码必须是六个固定动作之一（防编出越权动作）、
     * 对象必须能在入参里找到（防让人去查一个不存在的部门）、
     * 说明里的数字必须在入参里出现过（防编造预期效果）。
     */
    private List<MonthlyInsightVO.ActionVO> parseActions(JsonNode arr, Set<String> allowed,
                                                         Set<String> targets) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<MonthlyInsightVO.ActionVO> out = new ArrayList<>();
        for (int i = 0; i < arr.size() && out.size() < MAX_ACTIONS; i++) {
            JsonNode node = arr.get(i);
            String code = text(node, "code");
            String target = text(node, "target");
            String detail = text(node, "detail");
            if (!ACTION_CODES.containsKey(code)) {
                continue;
            }
            if (target != null && !target.isBlank() && !targets.contains(target.trim())) {
                continue;
            }
            if (detail == null || detail.isBlank() || detail.trim().length() > MAX_ACTION_DETAIL) {
                continue;
            }
            if (!numbersAllowed(detail, allowed)) {
                continue;
            }
            MonthlyInsightVO.ActionVO vo = new MonthlyInsightVO.ActionVO();
            vo.setCode(code);
            vo.setCodeText(ACTION_CODES.get(code));
            vo.setTarget(target == null ? "" : target.trim());
            vo.setDetail(detail.trim());
            vo.setAdopted(Boolean.FALSE);
            vo.setBasis(basisOf(targets, target));
            out.add(vo);
        }
        return out;
    }

    /**
     * 动作挂靠的字段。写不出依据的动作不让上页面——
     * 一条凭空冒出来的「建议复核」等于骚扰。
     */
    private String basisOf(Set<String> targets, String target) {
        if (target == null || target.isBlank()) {
            return "ratio";
        }
        String t = target.trim();
        return targets.contains(t) ? "dept." + t + ".ratio" : "ratio";
    }

    /**
     * 模型一条结论都没站住时的动作：照着统计层最该看的两件事给，
     * 不硬凑三条。这时候动作是规则出的，性质跟「产出没回填就该去补」一样明确。
     */
    private List<MonthlyInsightVO.ActionVO> ruleActions(MonthlyReportVO cur, MonthlyInsightVO vo,
                                                        int paidCnt) {
        List<MonthlyInsightVO.ActionVO> out = new ArrayList<>();
        if (paidCnt < MIN_SAMPLE) {
            return out;
        }
        MonthlyReportVO.DeptRowVO blank = blankOutputDept(cur);
        if (blank != null) {
            out.add(action(MonthlyInsightVO.ACT_CHECK_OUTPUT, blank.getDeptName(),
                    "本月投入 ¥" + money(blank.getTotalPay()) + "，产出还空着"));
        }
        MonthlyReportVO.DeptRowVO worst = worstDept(cur);
        if (worst != null && nz(worst.getRatio()).compareTo(WORST_THRESHOLD) > 0
                && (blank == null || !worst.getDeptName().equals(blank.getDeptName()))) {
            out.add(action(MonthlyInsightVO.ACT_REVIEW_COST, worst.getDeptName(),
                    "费效比 " + r(worst.getRatio()) + "，先看这个月的费用明细"));
        }
        return out.size() > MAX_ACTIONS ? out.subList(0, MAX_ACTIONS) : out;
    }

    private MonthlyInsightVO.ActionVO action(String code, String target, String detail) {
        MonthlyInsightVO.ActionVO vo = new MonthlyInsightVO.ActionVO();
        vo.setCode(code);
        vo.setCodeText(ACTION_CODES.get(code));
        vo.setTarget(target);
        vo.setDetail(detail);
        vo.setAdopted(Boolean.FALSE);
        vo.setBasis("dept." + target + ".ratio");
        return vo;
    }

    /**
     * 本次入参里真实存在的动作对象：部门名、出差类型名、归因里点到的名字。
     */
    private Set<String> allowedTargets(MonthlyReportVO cur, MonthlyInsightVO vo) {
        Set<String> set = new HashSet<>();
        for (MonthlyReportVO.DeptRowVO row : cur.getRows() == null ? List.<MonthlyReportVO.DeptRowVO>of() : cur.getRows()) {
            if (row.getDeptName() != null && !row.getDeptName().isBlank()) {
                set.add(row.getDeptName().trim());
            }
        }
        for (MonthlyReportVO.TypeRowVO row : cur.getTypeRows() == null ? List.<MonthlyReportVO.TypeRowVO>of() : cur.getTypeRows()) {
            if (row.getLabel() != null && !row.getLabel().isBlank()) {
                set.add(row.getLabel().trim());
            }
        }
        for (MonthlyInsightVO.DriverVO d : vo.getDrivers()) {
            if (d.getName() != null && !d.getName().isBlank()) {
                set.add(d.getName().trim());
            }
        }
        return set;
    }

    /**
     * 这份报告的数字是从哪来的。每一项都带上月份与条数，
     * 一是供后端自检（报告里引用的口径跟这里对不上就是算错了），
     * 二是被人问「这个数哪来的」时答得出来。页面不展示。
     */
    private List<String> buildSources(MonthlyReportVO cur) {
        String m = str(cur.getMonth());
        List<String> out = new ArrayList<>();
        out.add("payment_record " + m + " 已付款 " + (cur.getPaidCnt() == null ? 0 : cur.getPaidCnt())
                + " 单，投入 ¥" + money(cur.getTotalPay()));
        out.add("biz_result " + m + " 产出登记，产出 ¥" + money(cur.getTotalOutput())
                + "，其中已回填 " + (cur.getFilledCnt() == null ? 0 : cur.getFilledCnt()) + " 单");
        out.add("trip_apply " + m + " 申请 " + (cur.getApplyCnt() == null ? 0 : cur.getApplyCnt())
                + " 单，事前拦截 ¥" + money(cur.getBlockedAmount()));
        out.add("eval_result " + m + " 评价「关注」 "
                + (cur.getAttentionCnt() == null ? 0 : cur.getAttentionCnt()) + " 单");
        return out;
    }

    /**
     * 置信度 = 样本充足度 × 数字校验通过率 × 产出回填完整度。
     *
     * <p>三项各自回答一个问题：样本量决定这份结论站不站得住；
     * 校验通过率决定模型这一次说得准不准；回填率决定分母（产出）是不是完整的。
     * 三项都是统计层的真实数字，模型全程不参与——包括不让它给自己打分。
     */
    private double confidence(int paidCnt, BigDecimal fillRate, double checkRate) {
        double adequacy = Math.min(1.0, paidCnt / (double) SAMPLE_FULL);
        double fill = fillRate == null
                ? 1.0
                : Math.max(FILL_FLOOR.doubleValue(), fillRate.doubleValue());
        return Math.round(adequacy * checkRate * fill * 100.0) / 100.0;
    }

    /**
     * 置信度档位。数字不上页面，页面只要档位来选颜色。
     */
    private String levelOf(Double c) {
        if (c == null) {
            return null;
        }
        if (c >= CONF_AMPLE) {
            return MonthlyInsightVO.CONF_AMPLE;
        }
        return c >= CONF_FAIR ? MonthlyInsightVO.CONF_FAIR : MonthlyInsightVO.CONF_THIN;
    }

    /**
     * 加这几个字段之前生成的报告，库里没有置信度 / 动作 / 出处，读出来时用当前统计补一次。
     *
     * <p>刻意只算不存、也不调模型：这是「读出来展示」的分支，
     * 为一次展示去写库不合适，下次重新分析自然会带上真实算出来的那一份。
     *
     * <p>补出来的动作是规则版那两条（补录产出、复核费用），不是模型版——
     * 老报告本来就没有模型动作可留，拿现在的统计硬问模型一次，
     * 会让「读一次报告」变成一次模型调用，那条边界不能破。
     */
    private void backfill(MonthlyInsightVO vo) {
        try {
            MonthlyReportVO cur = analyticsService.monthly(vo.getMonth(), null);
            int paidCnt = cur.getPaidCnt() == null ? 0 : cur.getPaidCnt();
            vo.setSampleCnt(paidCnt);
            if (vo.getConfidence() == null) {
                vo.setConfidence(confidence(paidCnt, cur.getFillRate(), 1.0));
                vo.setConfidenceLevel(levelOf(vo.getConfidence()));
            }
            if (vo.getActions().isEmpty()) {
                vo.getActions().addAll(ruleActions(cur, vo, paidCnt));
            }
            if (vo.getSources().isEmpty()) {
                vo.getSources().addAll(buildSources(cur));
            }
        } catch (Exception e) {
            log.info("老报告补算失败，页面不显示样本标签与动作：{}", e.getMessage());
        }
    }

    /**
     * 一条结论能不能上页面：档位合法、长度合规、依据挂在真实字段上、
     * 且正文与依据里出现的每个数字都能在输入里找到。
     */
    private boolean okPoint(String level, String body, String field, String value, Set<String> allowed) {
        return AiTextGuard.okPoint(level, body, field, value, allowed, FIELD_PREFIXES, MAX_TEXT);
    }

    private boolean numbersAllowed(String text, Set<String> allowed) {
        return AiTextGuard.numbersAllowed(text, allowed);
    }

    // ---------- 规则结论：模型的兜底，也是 AI 关闭时的唯一来源 ----------

    /**
     * 规则版结论。它和模型版共用同一张卡，所以同样带 evidence——
     * 让人无论看哪一版，都能顺着字段去核。
     */
    /** 规则出的一条结论。来源如实标 RULE，混在模型结论里时不冒充 */
    private AiPointVO rule(String level, String text, String field, String value) {
        return AiPointVO.of(level, text, field, value, AiPointVO.SRC_RULE);
    }

    private List<AiPointVO> analyticPoints(MonthlyReportVO cur, MonthlyReportVO prev,
                                           BigDecimal delta, boolean thin) {
        List<AiPointVO> out = new ArrayList<>();
        int paidCnt = cur.getPaidCnt() == null ? 0 : cur.getPaidCnt();
        if (thin) {
            out.add(rule("low", "本月已付款单据只有 " + paidCnt + " 单，不足 " + MIN_SAMPLE
                    + " 单，样本太小，这里不给趋势性结论", "paidCnt", String.valueOf(paidCnt)));
            return out;
        }

        if (prev != null && delta != null) {
            if (delta.compareTo(DELTA_ALERT) > 0) {
                out.add(rule("high", "本月费效比 " + r(cur.getRatio()) + "，较上月 "
                        + r(prev.getRatio()) + " 变差", "ratio", r(prev.getRatio()) + " → " + r(cur.getRatio())));
            } else if (delta.compareTo(DELTA_ALERT.negate()) < 0) {
                out.add(rule("low", "本月费效比 " + r(cur.getRatio()) + "，较上月 "
                        + r(prev.getRatio()) + " 变好", "ratio", r(prev.getRatio()) + " → " + r(cur.getRatio())));
            } else {
                out.add(rule("low", "本月费效比 " + r(cur.getRatio()) + "，与上月基本持平",
                        "ratio", r(cur.getRatio())));
            }
        }

        // 投了钱却没回填产出的部门，比"比值偏高"更值得先看：前者是数据缺口，后者是效率问题
        MonthlyReportVO.DeptRowVO blank = blankOutputDept(cur);
        if (blank != null) {
            out.add(rule("high", "「" + blank.getDeptName() + "」本月投入 ¥"
                    + money(blank.getTotalPay()) + "，但没有回填任何产出",
                    "dept." + blank.getDeptName() + ".output", "0"));
        }

        MonthlyReportVO.DeptRowVO worst = worstDept(cur);
        if (worst != null && nz(worst.getRatio()).compareTo(WORST_THRESHOLD) > 0) {
            out.add(rule(nz(worst.getRatio()).compareTo(new BigDecimal("0.60")) > 0 ? "high" : "mid",
                    "「" + worst.getDeptName() + "」费效比 " + r(worst.getRatio()) + "，投入产出明显失衡",
                    "dept." + worst.getDeptName() + ".ratio", r(worst.getRatio())));
        }

        MonthlyReportVO.OutputStructVO struct = cur.getOutputStruct();
        if (struct != null && struct.getNoProgressRate() != null
                && struct.getNoProgressRate().compareTo(NO_PROGRESS_ALERT) >= 0) {
            out.add(rule("mid", "本月回填的业务结果 " + struct.getTotalCnt() + " 条，其中「无进展」占 "
                    + pct(struct.getNoProgressRate()), "outputStruct.noProgressRate",
                    pct(struct.getNoProgressRate())));
        }

        if (cur.getBlockedAmount() != null && cur.getBlockedAmount().compareTo(BigDecimal.ZERO) > 0) {
            out.add(rule("low", "事前拦截 ¥" + money(cur.getBlockedAmount())
                    + "：差标超限且最终未成行的预估合计", "blockedAmount", money(cur.getBlockedAmount())));
        }

        MonthlyReportVO.DeptRowVO best = bestDept(cur);
        if (best != null && best != worst) {
            out.add(rule("low", "「" + best.getDeptName() + "」费效比 " + r(best.getRatio())
                    + "，是本月投入产出最划算的部门", "dept." + best.getDeptName() + ".ratio", r(best.getRatio())));
        }
        return out.size() > MAX_POINTS ? out.subList(0, MAX_POINTS) : out;
    }

    private String summary(MonthlyReportVO cur, MonthlyReportVO prev, BigDecimal delta) {
        StringBuilder sb = new StringBuilder();
        sb.append("本月投入 ¥").append(money(cur.getTotalPay()))
                .append("、产出 ¥").append(money(cur.getTotalOutput()))
                .append("，费效比 ").append(r(cur.getRatio()));
        if (prev != null && delta != null) {
            sb.append("（上月 ").append(r(prev.getRatio())).append("，")
                    .append(delta.compareTo(BigDecimal.ZERO) >= 0 ? "变差 " : "变好 ")
                    .append(r(delta.abs())).append("）");
        }
        return sb.append("。").toString();
    }

    /**
     * 上个月的已付款单据够不够当归因基准。
     * 一阶分解是拿上月当分母的：上月样本太小（比如只有一两单），分母一抖，
     * 算出来的各项贡献会大到离谱 —— 演示时见过单项贡献 0.41、而整体 delta 只有 0.14 的情况。
     * 与其给一堆看着专业却加不起来的数，不如不给。
     */
    private boolean prevThin(MonthlyReportVO prev) {
        return prev != null && (prev.getPaidCnt() == null || prev.getPaidCnt() < MIN_SAMPLE);
    }

    private String note(int paidCnt, int filledCnt, BigDecimal fillRate,
                        boolean thin, boolean lowFill, boolean prevThin, String prevMonth) {
        if (thin) {
            return "本月已付款单据 " + paidCnt + " 单，不足 " + MIN_SAMPLE + " 单，样本太小，不给出趋势性结论。";
        }
        if (prevThin) {
            return "上月（" + prevMonth + "）已付款单据不足 " + MIN_SAMPLE
                    + " 单，拿它当月环比基准算出来的归因不可靠，本期不出归因明细。";
        }
        if (lowFill) {
            return "产出回填率 " + pct(fillRate) + "（" + filledCnt + "/" + paidCnt
                    + " 单）：结论只覆盖了部分已付款单据，未回填的部分按 0 产出计入。";
        }
        return null;
    }

    // ---------- 分组 ----------

    private List<Grp> deptGroups(MonthlyReportVO rep) {
        List<Grp> out = new ArrayList<>();
        if (rep == null || rep.getRows() == null) {
            return out;
        }
        for (MonthlyReportVO.DeptRowVO row : rep.getRows()) {
            out.add(new Grp("DEPT", "部门", name(row.getDeptName()),
                    nz(row.getTotalPay()), nz(row.getTotalOutput())));
        }
        return out;
    }

    private List<Grp> typeGroups(MonthlyReportVO rep) {
        List<Grp> out = new ArrayList<>();
        if (rep == null || rep.getTypeRows() == null) {
            return out;
        }
        for (MonthlyReportVO.TypeRowVO row : rep.getTypeRows()) {
            out.add(new Grp("TYPE", "出差类型", name(row.getLabel()),
                    nz(row.getTotalPay()), nz(row.getTotalOutput())));
        }
        return out;
    }

    private List<Grp> partnerGroups(PartnerRoiVO rep) {
        List<Grp> out = new ArrayList<>();
        if (rep == null || rep.getRows() == null) {
            return out;
        }
        for (PartnerRoiVO.PartnerRowVO row : rep.getRows()) {
            out.add(new Grp("PARTNER", "往来对象", name(row.getPartnerName()),
                    nz(row.getTotalInvest()), nz(row.getTotalOutput())));
        }
        return out;
    }

    /**
     * 最好 / 最差的部门。产出为 0 的部门不能按比值 0 排进"最好"——
     * 那是「投了钱没结果」，是这份月报里最该被看见的一类。
     */
    private MonthlyReportVO.DeptRowVO bestDept(MonthlyReportVO rep) {
        return rep.getRows() == null ? null : rep.getRows().stream()
                .filter(r -> nz(r.getTotalOutput()).compareTo(BigDecimal.ZERO) > 0)
                .min(Comparator.comparing(r -> nz(r.getRatio())))
                .orElse(null);
    }

    private MonthlyReportVO.DeptRowVO worstDept(MonthlyReportVO rep) {
        return rep.getRows() == null ? null : rep.getRows().stream()
                .filter(r -> nz(r.getTotalOutput()).compareTo(BigDecimal.ZERO) > 0)
                .max(Comparator.comparing(r -> nz(r.getRatio())))
                .orElse(null);
    }

    private MonthlyReportVO.DeptRowVO blankOutputDept(MonthlyReportVO rep) {
        return rep.getRows() == null ? null : rep.getRows().stream()
                .filter(r -> nz(r.getTotalPay()).compareTo(BigDecimal.ZERO) > 0
                        && nz(r.getTotalOutput()).compareTo(BigDecimal.ZERO) <= 0)
                .max(Comparator.comparing(r -> nz(r.getTotalPay())))
                .orElse(null);
    }

    private Map<String, Object> deptBrief(MonthlyReportVO.DeptRowVO row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name(row.getDeptName()));
        map.put("pay", money(row.getTotalPay()));
        map.put("output", money(row.getTotalOutput()));
        map.put("ratio", r(row.getRatio()));
        return map;
    }

    // ---------- 小工具 ----------

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

    /** 上月数据取不到不影响本月洞察，只是少了环比与归因 */
    private MonthlyReportVO safeMonthly(String month) {
        if (month == null) {
            return null;
        }
        try {
            return analyticsService.monthly(month, null);
        } catch (Exception e) {
            log.warn("读取上月月报失败，本次不做环比与归因：{}", e.getMessage());
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

    private static String text(JsonNode node, String field) {
        return AiTextGuard.text(node, field);
    }

    private static BigDecimal nz(BigDecimal value) {
        return AiFmt.nz(value);
    }

    private static String name(String value) {
        return AiFmt.name(value);
    }

    private static String str(String value) {
        return AiFmt.str(value);
    }

    private static String money(BigDecimal value) {
        return AiFmt.money(value);
    }

    private static String r(BigDecimal value) {
        return AiFmt.r(value);
    }

    private static String r4(BigDecimal value) {
        return AiFmt.r4(value);
    }

    private static String pct(BigDecimal value) {
        return AiFmt.pct(value);
    }

    private static String signed(BigDecimal value) {
        BigDecimal v = nz(value);
        return (v.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "-") + money(v.abs());
    }

    private static String key(Grp g) {
        return g.dim() + "|" + g.name();
    }

    /**
     * 一个维度上的一份投入产出，归因分解的最小单位。
     */
    private record Grp(String dim, String dimText, String name, BigDecimal pay, BigDecimal output) {
    }

    /**
     * 模型输出的解析结果。points 为空即视为本次没拿到可用结论。
     */
    /**
     * 模型输出解析的结果。
     *
     * <p>{@code total} 是模型一共给了几条（含没通过校验的），{@code points} 是留下来的。
     * 两者之比就是「数字校验通过率」，置信度的第二项：
     * 给了四条一条都站不住，和给了四条留下三条，这两份报告的含金量不该一样。
     */
    private record Parsed(List<AiPointVO> points, String summary, int total,
                          List<MonthlyInsightVO.ActionVO> actions) {
        static Parsed empty() {
            return new Parsed(List.of(), null, 0, List.of());
        }
    }
}
