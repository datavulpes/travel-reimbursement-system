package com.ts.travelserver.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.config.AiConfig;
import com.ts.travelserver.entity.AiLog;
import com.ts.travelserver.mapper.AiLogMapper;
import com.ts.travelserver.vo.AiStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 运行状况：把 ai_log 聚合成人能看的几个数。
 *
 * <p><b>刻意不建统计表</b>。这几个数的量级（一天几十到几百条）用一次带时间范围的查询
 * 现算完全够，建表就要考虑回补、对账、清理，成本远大于收益——
 * 而且统计表一旦和业务表不同步，页面上的数就成了谁也不敢信的数。
 *
 * <p>口径说清三条，免得页面上的数字被误读：
 * <ul>
 *   <li><b>成功</b> = 这一行 fallback = 0 且没有失败原因。降级到本地启发式也算没成功——
 *       它给出的结论是规则兜出来的，不是模型算出来的。</li>
 *   <li><b>采纳率</b> = accepted = 1 的占比。没有数据的场景返回 null，
 *       页面显示横杠，<b>不用 0 冒充「没人采纳」</b>——没跑过和跑了没人用是两回事。</li>
 *   <li><b>token</b> = 只累加厂商真的返回了 usage 的行。一条都没返回就是 null，
 *       跟「消耗为 0」完全不是一回事。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiStatusService {

    /** 最近留痕展示条数 */
    private static final int RECENT_SIZE = 10;

    /** 场景中文名，只给页面用 */
    private static final Map<String, String> SCENE_TEXT = Map.of(
            AiScene.SEMANTIC_EVAL, "语义评价",
            AiScene.APPLY_ASSIST, "填写助手",
            AiScene.MONTHLY_INSIGHT, "月度洞察",
            AiScene.DRILL_EXPLAIN, "钻取解释",
            AiScene.POLICY_QA, "制度问答",
            AiScene.DATA_QA, "AI助手"
    );

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final AiLogMapper aiLogMapper;
    private final AiConfig config;
    private final AiCorrectionService aiCorrectionService;

    /**
     * 聚合最近 days 天的运行状况。
     *
     * @param days 窗口天数，小于 1 按 1 天算
     */
    public AiStatusVO status(int days) {
        int window = Math.max(1, days);
        LocalDateTime from = LocalDateTime.now().minusDays(window);

        List<AiLog> rows = new ArrayList<>();
        try {
            rows = aiLogMapper.selectList(new LambdaQueryWrapper<AiLog>()
                    .ge(AiLog::getCreatedAt, from)
                    .orderByDesc(AiLog::getId));
        } catch (Exception e) {
            // 留痕表查不动不影响页面其余部分：下面的 VO 会按空数据组装
            log.warn("AI 运行状况聚合失败：{}", e.getMessage());
        }

        AiStatusVO vo = new AiStatusVO();
        vo.setModel(config.isEnabled() ? config.getModel() : "");
        vo.setEnabled(config.isEnabled());
        vo.setUsable(config.usable());
        vo.setLogEnabled(config.isLogEnabled());
        vo.setWindowDays(window);
        vo.setTotalCnt(rows.size());
        vo.setSuccessCnt(rows.stream().filter(this::success).count());
        vo.setFallbackCnt(rows.stream().filter(r -> is(r.getFallback())).count());
        vo.setErrorCnt(rows.stream().filter(r -> notBlank(r.getErrorMessage())).count());
        vo.setAvgCostMs(avgCost(rows));
        vo.setAdoptRate(adoptRate(rows));
        vo.setTokenSum(tokenSum(rows));
        vo.setScenes(sceneStats(rows));
        vo.setRecent(recent(rows));
        vo.setTopCorrected(aiCorrectionService.topFields(window, 6));
        return vo;
    }

    // ---------- 聚合 ----------

    /**
     * 成功 = 没降级且没有失败原因。
     *
     * <p>降级也算不成功：那条结论是本地规则兜出来的，
     * 把它算进成功率，页面上就会显示「AI 一切正常」，而这恰恰是最需要看到的异常。
     */
    private boolean success(AiLog r) {
        return !is(r.getFallback()) && !notBlank(r.getErrorMessage());
    }

    private long avgCost(List<AiLog> rows) {
        List<AiLog> priced = rows.stream().filter(r -> r.getCostMs() != null).toList();
        if (priced.isEmpty()) {
            return 0L;
        }
        return Math.round(priced.stream().mapToLong(AiLog::getCostMs).average().orElse(0d));
    }

    /**
     * 采纳率。分母是「已经有人做过决定」的行（accepted 非空），
     * 还没定论的不进分母——否则新出的建议会把采纳率往下拉，看着像没人用。
     */
    private Double adoptRate(List<AiLog> rows) {
        List<AiLog> decided = rows.stream().filter(r -> r.getAccepted() != null).toList();
        if (decided.isEmpty()) {
            return null;
        }
        long yes = decided.stream().filter(r -> r.getAccepted() == AiLog.ACCEPTED_YES).count();
        return decided.isEmpty() ? null : (double) yes / decided.size();
    }

    /** 只累加真返回了 usage 的行；一条都没有返回 null */
    private Long tokenSum(List<AiLog> rows) {
        long sum = 0;
        boolean any = false;
        for (AiLog r : rows) {
            if (r.getRequestTokens() != null) {
                sum += r.getRequestTokens();
                any = true;
            }
            if (r.getResponseTokens() != null) {
                sum += r.getResponseTokens();
                any = true;
            }
        }
        return any ? sum : null;
    }

    private List<AiStatusVO.SceneStat> sceneStats(List<AiLog> rows) {
        Map<String, List<AiLog>> grouped = new LinkedHashMap<>();
        for (AiSceneHolder holder : AiSceneHolder.ORDER) {
            grouped.put(holder.code, new ArrayList<>());
        }
        for (AiLog r : rows) {
            grouped.computeIfAbsent(r.getScene(), k -> new ArrayList<>()).add(r);
        }
        List<AiStatusVO.SceneStat> out = new ArrayList<>();
        // 有数据的排前面，其余按固定顺序跟上：一眼看到的是真跑过的场景
        List<Map.Entry<String, List<AiLog>>> entries = new ArrayList<>(grouped.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, List<AiLog>> e) -> -e.getValue().size())
                .thenComparingInt(e -> AiSceneHolder.indexOf(e.getKey())));
        for (Map.Entry<String, List<AiLog>> e : entries) {
            AiStatusVO.SceneStat s = new AiStatusVO.SceneStat();
            s.setScene(e.getKey());
            s.setSceneText(SCENE_TEXT.getOrDefault(e.getKey(), e.getKey()));
            s.setCnt(e.getValue().size());
            s.setSuccessCnt(e.getValue().stream().filter(this::success).count());
            s.setFallbackCnt(e.getValue().stream().filter(r -> is(r.getFallback())).count());
            s.setAvgCostMs(avgCost(e.getValue()));
            s.setAdoptRate(adoptRate(e.getValue()));
            out.add(s);
        }
        return out;
    }

    private List<AiStatusVO.LogRow> recent(List<AiLog> rows) {
        return rows.stream().limit(RECENT_SIZE).map(r -> {
            AiStatusVO.LogRow row = new AiStatusVO.LogRow();
            row.setId(r.getId());
            row.setScene(r.getScene());
            row.setSceneText(SCENE_TEXT.getOrDefault(r.getScene(), r.getScene()));
            row.setRefNo(r.getRefNo());
            row.setModel(r.getModel());
            row.setCostMs(r.getCostMs());
            row.setAccepted(r.getAccepted());
            row.setFallback(r.getFallback());
            row.setErrorMessage(r.getErrorMessage());
            row.setCreatedAt(r.getCreatedAt() == null ? null : r.getCreatedAt().format(FMT));
            return row;
        }).toList();
    }

    private boolean is(Integer v) {
        return v != null && v == 1;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 场景的固定展示顺序，只影响页面排序，不影响统计 */
    private enum AiSceneHolder {
        SEMANTIC_EVAL(AiScene.SEMANTIC_EVAL),
        APPLY_ASSIST(AiScene.APPLY_ASSIST),
        MONTHLY_INSIGHT(AiScene.MONTHLY_INSIGHT),
        DRILL_EXPLAIN(AiScene.DRILL_EXPLAIN),
        POLICY_QA(AiScene.POLICY_QA),
        DATA_QA(AiScene.DATA_QA);

        private final String code;

        AiSceneHolder(String code) {
            this.code = code;
        }

        static final List<AiSceneHolder> ORDER = List.of(values());

        static int indexOf(String code) {
            for (int i = 0; i < ORDER.size(); i++) {
                if (ORDER.get(i).code.equals(code)) {
                    return i;
                }
            }
            return ORDER.size();
        }
    }
}
