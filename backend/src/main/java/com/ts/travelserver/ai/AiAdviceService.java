package com.ts.travelserver.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.config.AiConfig;
import com.ts.travelserver.dto.ApplySubmitRequest;
import com.ts.travelserver.dto.CostRefQuery;
import com.ts.travelserver.entity.BizResult;
import com.ts.travelserver.entity.Partner;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.mapper.BizResultMapper;
import com.ts.travelserver.mapper.PartnerMapper;
import com.ts.travelserver.mapper.SysUserMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.mapper.TripItineraryMapper;
import com.ts.travelserver.service.CostRefService;
import com.ts.travelserver.service.StandardService;
import com.ts.travelserver.vo.AiAdviceVO;
import com.ts.travelserver.vo.AiFieldAdviceVO;
import com.ts.travelserver.vo.AiPointVO;
import com.ts.travelserver.vo.CostRefVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 填写助手：把「当前这一版表单」和「库里能对上的数据」放在一起分析，产出一份可参考的建议。
 *
 * <h3>什么时候给建议</h3>
 * <p><b>任何时候。</b>填得多还是填得少只影响建议的构成——素材多的时候能给具体值，
 * 素材少的时候多给方向、少给值——从来不是「等你填完再来」。
 * 早先这里有一道「事由不满四个字或没有目的地就不问模型」的门槛，
 * 结果是员工刚打开表单最需要帮忙的那一刻，AI 只会回一句「先把这几项填上」。
 *
 * <h3>三类产出，能替他定到哪一步各不相同</h3>
 * <ol>
 *   <li><b>库里有据可查的就替他填</b>（{@code fields} 里 source 是 DB_* 的那些）：
 *       出差类型按本人 / 部门 / 全员在目的地的历史分布，金额按同类参考、历史均值或差标。
 *       值全部由 DB 算，模型碰不到数字。</li>
 *   <li><b>事由可以由模型重组一句</b>（source=MODEL）：员工写的是零散的几个词，
 *       把它们整理成一句规范表述。前提是员工确实写了东西——一个字没写时不给改写，
 *       因为没有原文就没有改写，系统替他写的「预期如何如何」是凭空捏的。</li>
 *   <li><b>只有本人才知道的只给方向</b>（{@code items}）：去见谁、哪天出发、目的地算几级城市、
 *       预期产出具体是什么。这类建议写成一句话告诉他补什么，不给值。</li>
 * </ol>
 *
 * <h3>没有库内数据时怎么说</h3>
 * <p>照样给建议，但要把话说全：{@code anchorless=true} 时带上
 * 「库里没找到能对标的同类数据」，并说明补哪几项之后建议能落到具体数字。
 * 只写「暂无建议」等于把「没有数据」和「你填得没问题」混成一件事。
 *
 * <h3>依据范围</h3>
 * <p>全程不联网。交给模型的只有本库的同类参考、差标、历史单据与对象产出，
 * 模型也没有任何「查一下外部信息」的通道，理由与代价写在 {@link AiPrompts} 的类注释里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAdviceService {

    /** 本地模板句超过这个长度就不用：那时拼出来的已经不成句了 */
    private static final int MAX_REWRITE = 80;

    /** 日期没填时按这个天数估住宿占比，短途最常见 */
    private static final int DEFAULT_DAYS = 2;

    /** 金额建议取整到百位：填预估时没人写 1373.33 */
    private static final BigDecimal STEP = new BigDecimal("100");

    /** 没有同类参考可依时，按差标上限的这个比例给，留一成余量 */
    private static final BigDecimal CAP_RATIO = new BigDecimal("0.9");

    /** 预估合计与同类均值偏离超过这个比例才提醒，低于它属于正常波动 */
    private static final BigDecimal DEVIATION = new BigDecimal("0.30");

    /** 本人历史与员工这次选的类型不一致，且同目的地的历史够多时才敢建议改 */
    private static final int STRONG_HISTORY = 3;

    private static final List<String> OUTPUT_WORDS = List.of(
            "签单", "订单", "验收", "交付", "样品", "报价", "合同", "回款", "节点", "审厂", "试产", "对账");

    private static final List<String> PURPOSE_WORDS = List.of(
            "拜访", "验货", "审核", "展会", "交付", "培训", "谈判", "对接", "巡店", "驻场");

    /**
     * 一条库内数据都对不上时的开场白。
     *
     * <p>话要说全：承认没数据的同时告诉他补哪几项能让建议落到具体数字。
     * 只说「暂无建议」会让员工以为是自己填错了，或者以为系统坏了。
     */
    private static final String NO_ANCHOR_NOTE = "库里暂时没有能对标的数据（没有同类花费参考，"
            + "你本人、同部门、全公司近 12 个月也没去过这里）。下面是通用填写提示，"
            + "把目的地和日期填上，再点一次分析就能给出具体金额。";

    private final AiService aiService;
    private final AiCache aiCache;
    private final AiLogService aiLogService;
    private final AiConfig config;
    private final ObjectMapper objectMapper;
    private final AdviceGuard adviceGuard;
    private final PartnerMapper partnerMapper;
    private final SysUserMapper sysUserMapper;
    private final CostRefService costRefService;
    private final StandardService standardService;
    private final TripApplyMapper tripApplyMapper;
    private final TripItineraryMapper tripItineraryMapper;
    private final BizResultMapper bizResultMapper;

    /**
     * 给一份表单出建议。草稿阶段也能调，不落库、不改草稿内容。
     */
    public AiAdviceVO advice(ApplySubmitRequest request) {
        AiAdviceVO vo = new AiAdviceVO();
        vo.setLocal(!aiService.remote());
        vo.setModel(aiService.model());
        vo.setPromptVersion(config.getPromptVersion());

        String reason = request.getReason() == null ? "" : request.getReason().trim();
        Ctx ctx = context(request);
        collectMissing(request, vo, reason, ctx);

        boolean anchorless = !ctx.hasAnchor();
        vo.setAnchorless(anchorless);
        vo.setSourceNote(anchorless ? NO_ANCHOR_NOTE : null);

        List<AdviceGuard.AdviceItem> model = ask(request, ctx, reason);

        int sort = 1;
        AiFieldAdviceVO rewrite = rewriteField(request, ctx, reason, model);
        if (rewrite != null) {
            rewrite.setSort(sort++);
            vo.getFields().add(rewrite);
            vo.setRewrite(rewrite.getSuggestValue());
        }
        Optional<AiFieldAdviceVO> type = tripTypeField(request, ctx, model);
        if (type.isPresent()) {
            type.get().setSort(sort++);
            vo.getFields().add(type.get());
        }
        for (AiFieldAdviceVO amount : amountFields(request, ctx)) {
            amount.setSort(sort++);
            vo.getFields().add(amount);
        }
        Optional<AiFieldAdviceVO> exceed = exceedReasonField(request, ctx);
        if (exceed.isPresent()) {
            exceed.get().setSort(sort++);
            vo.getFields().add(exceed.get());
        }

        hints(model, vo);
        checks(request, ctx, vo);

        if (vo.getFields().isEmpty() && vo.getItems().isEmpty()) {
            vo.getItems().add(AiPointVO.of("low",
                    "这版暂时没有可建议的项。把事由、目的地、对象填上任意一项再来点一次，"
                            + "AI 会拿库里的同类数据对着给建议"));
        }
        return vo;
    }

    // ---------- 缺项：确定性规则，不问模型 ----------

    /**
     * 哪几项还没动。这一份永远给，与 AI 有没有被调用无关——
     * 模型挂了、没配密钥，页面照样要告诉员工「你还差什么」。
     */
    private void collectMissing(ApplySubmitRequest request, AiAdviceVO vo, String reason, Ctx ctx) {
        if (reason.isEmpty()) {
            vo.getMissing().add("出差事由");
            vo.getItems().add(AiPointVO.of("mid",
                    "事由还没填。写清「去做什么 + 预期拿到什么」这趟才讲得清"));
        } else {
            if (reason.length() < 15) {
                vo.getMissing().add("事由过短");
                vo.getItems().add(AiPointVO.of("mid",
                        "事由只有 " + reason.length() + " 个字，审批人看不出这趟的目的"));
            }
            if (!containsAny(reason, PURPOSE_WORDS)) {
                vo.getMissing().add("出行目的");
                vo.getItems().add(AiPointVO.of("mid",
                        "没写明去做什么（拜访 / 验货 / 审核 / 交付），建议补上"));
            }
            if (!containsAny(reason, OUTPUT_WORDS)) {
                vo.getMissing().add("预期产出");
                vo.getItems().add(AiPointVO.of("high",
                        "没写预期产出。写清「确认 3 月订单节点」这类结果，合理性评分会明显上去"));
            }
        }
        if (ctx.dest == null) {
            vo.getMissing().add("行程目的地");
            vo.getItems().add(AiPointVO.of("mid",
                    "还没填目的地，同类花费参考查不出来，金额建议也只能先给个大概"));
        }
        if (ctx.days <= 0) {
            vo.getMissing().add("行程日期");
            vo.getItems().add(AiPointVO.of("mid",
                    "日期还没填。住宿要按住几晚摊，日期补上后金额建议会换成按你行程算的数"));
        }
        if (request.getPartnerId() == null) {
            vo.getMissing().add("关联对象");
            vo.getItems().add(AiPointVO.of("mid",
                    "还没选关联对象。选上之后历史产出率能算出来，出差类型也更好判断"));
        }
    }

    // ---------- 库内上下文 ----------

    /**
     * 一次把建议要用到的库内数据取齐。
     *
     * <p>取不到的项保持 null，后面的生成器自己决定这一项还能不能给——
     * 基础数据不全时不硬凑，宁可这一项少一条。
     *
     * <p>四类来源挨个试（本人 → 同部门 → 同目的地全员 → 差标），
     * 是因为「这一趟该怎么填」这件事越接近本人的口径越准，越往外证据越弱。
     * 弱证据不是不能用，只是置信度要跟着往下走。
     */
    private Ctx context(ApplySubmitRequest request) {
        Ctx ctx = new Ctx();
        List<ApplySubmitRequest.ItineraryItem> items =
                request.getItineraries() == null ? List.of() : request.getItineraries();

        for (ApplySubmitRequest.ItineraryItem item : items) {
            if (ctx.dest == null && StringUtils.hasText(item.getToCity())) {
                ctx.dest = item.getToCity().trim();
            }
        }
        ctx.days = days(request);
        ctx.nights = Math.max(0, ctx.days - 1);
        ctx.segCnt = items.size();

        loadCaps(request, ctx);
        loadCostRef(request, ctx);
        loadPartner(request, ctx);

        Long userId = currentUserId();
        if (userId != null) {
            loadMine(ctx, userId);
            loadDept(ctx, userId);
        }
        loadCityAll(ctx);
        return ctx;
    }

    /** 逐段差标上限合计。某一组没配标准就跳过，免得基础数据不全把整单判成超限 */
    private void loadCaps(ApplySubmitRequest request, Ctx ctx) {
        BigDecimal total = BigDecimal.ZERO;
        boolean known = false;
        for (ApplySubmitRequest.ItineraryItem item : request.getItineraries() == null
                ? List.<ApplySubmitRequest.ItineraryItem>of() : request.getItineraries()) {
            String level = StringUtils.hasText(item.getCityLevel())
                    ? item.getCityLevel() : standardService.cityLevelOf(item.getToCity());
            BigDecimal max = standardService.currentMax(level, item.getTrafficType());
            if (max != null) {
                total = total.add(max);
                known = true;
            }
        }
        ctx.cap = known ? total : null;
    }

    /**
     * 同类花费参考。走的是与填单页同一套实时聚合——两条路各查各的会出
     * 「表格说 ¥1,798、AI 说 ¥2,600」这种自己跟自己打架的数。
     *
     * <p>后端查不到样本时会按维度优先级放宽，这里把「到底放宽没有」记下来：
     * 放宽过意味着这一组不是员工填的那组条件，文案里要讲清楚。
     */
    private void loadCostRef(ApplySubmitRequest request, Ctx ctx) {
        if (!StringUtils.hasText(ctx.dest)) {
            return;
        }
        try {
            CostRefQuery q = new CostRefQuery();
            q.setToCity(ctx.dest);
            q.setTripType(request.getTripType());
            // 首段行程的等级与交通方式也带进去，与页面表格用的是同一批维度
            List<ApplySubmitRequest.ItineraryItem> items = request.getItineraries();
            if (items != null && !items.isEmpty()) {
                ApplySubmitRequest.ItineraryItem first = items.get(0);
                q.setCityLevel(first.getCityLevel());
                q.setTrafficType(first.getTrafficType());
            }
            ctx.costRef = costRefService.queryLive(q);
            ctx.costRefExact = ctx.costRef != null && ctx.costRef.getRelaxed() != Boolean.TRUE;
        } catch (Exception e) {
            log.warn("读取同类花费参考失败，本次不给同类相关建议：{}", e.getMessage());
        }
    }

    /** 往来对象本身的信息，以及它名下的历史单据分布 */
    private void loadPartner(ApplySubmitRequest request, Ctx ctx) {
        Long partnerId = request.getPartnerId();
        if (partnerId == null) {
            return;
        }
        try {
            Partner partner = partnerMapper.selectById(partnerId);
            if (partner != null) {
                ctx.partnerName = partner.getPartnerName();
                ctx.partnerType = partner.getPartnerType();
                ctx.partnerOutput = partner.getTotalOutput();
            }
            ctx.partnerResultCnt = bizResultMapper.selectCount(new LambdaQueryWrapper<BizResult>()
                    .eq(BizResult::getPartnerId, partnerId)
                    .ge(BizResult::getFilledAt, LocalDateTime.now().minusMonths(12)));
            List<TripApply> applies = recentApplies(new LambdaQueryWrapper<TripApply>()
                    .eq(TripApply::getPartnerId, partnerId)
                    .last("limit 50"));
            if (!applies.isEmpty()) {
                Map<String, Integer> cnt = new LinkedHashMap<>();
                for (TripApply apply : applies) {
                    if (StringUtils.hasText(apply.getTripType())) {
                        cnt.merge(apply.getTripType(), 1, Integer::sum);
                    }
                }
                ctx.partnerApplyCnt = applies.size();
                ctx.partnerTopType = topKey(cnt);
            }
        } catch (Exception e) {
            log.warn("读取往来对象信息失败，本次不给对象相关建议：{}", e.getMessage());
        }
    }

    /**
     * 本人近 12 个月的出差画像：常去哪儿、常坐什么、平均几天几钱，
     * 再加上「如果这次的目的地已经去过」那一部分。
     *
     * <p>这一组是当前操作人的，不牵扯别人的数据，所以即使其他项全缺也能用；
     * 表单还空着的时候，它往往是唯一能把建议落到具体字面的依据。
     */
    private void loadMine(Ctx ctx, Long userId) {
        try {
            List<TripApply> mine = recentApplies(new LambdaQueryWrapper<TripApply>()
                    .eq(TripApply::getUserId, userId)
                    .last("limit 50"));
            if (mine.isEmpty()) {
                return;
            }
            List<String> nos = mine.stream().map(TripApply::getApplyNo).toList();
            Map<String, String> destOf = firstOf(nos, false);
            Map<String, String> trafficOf = firstOf(nos, true);
            Map<String, Integer> daysOf = daysOf(nos);

            Map<String, Integer> cityCnt = new LinkedHashMap<>();
            Map<String, Integer> trafficCnt = new LinkedHashMap<>();
            Map<String, Integer> typeCnt = new LinkedHashMap<>();
            BigDecimal amountSum = BigDecimal.ZERO;
            int amountCnt = 0;
            long daysSum = 0L;

            for (TripApply apply : mine) {
                String dest = destOf.get(apply.getApplyNo());
                if (StringUtils.hasText(dest)) {
                    cityCnt.merge(dest, 1, Integer::sum);
                }
                String traffic = trafficOf.get(apply.getApplyNo());
                if (StringUtils.hasText(traffic)) {
                    trafficCnt.merge(traffic, 1, Integer::sum);
                }
                Integer d = daysOf.get(apply.getApplyNo());
                if (d != null && d > 0) {
                    daysSum += d;
                }
                if (apply.getEstTotal() != null && apply.getEstTotal().compareTo(BigDecimal.ZERO) > 0) {
                    amountSum = amountSum.add(apply.getEstTotal());
                    amountCnt++;
                }
                // 同一目的地：这一组的类型是判断本次类型最硬的证据
                if (ctx.dest != null && ctx.dest.equals(dest)) {
                    ctx.historyCnt++;
                    if (StringUtils.hasText(apply.getTripType())) {
                        typeCnt.merge(apply.getTripType(), 1, Integer::sum);
                    }
                }
            }

            ctx.myTopCities = topKeys(cityCnt, 3);
            ctx.myTrafficTop = topKey(trafficCnt);
            ctx.historyType = topKey(typeCnt);
            if (!mine.isEmpty()) {
                ctx.myAvgDays = (int) Math.max(1, Math.round((double) daysSum / mine.size()));
            }
            ctx.myApplyCnt = mine.size();
            if (amountCnt > 0) {
                ctx.myAvgAmount = amountSum.divide(BigDecimal.valueOf(amountCnt), 2, RoundingMode.HALF_UP);
            }
            if (ctx.historyCnt > 0) {
                ctx.historyAvgAmount = avgAmount(mine, destOf, ctx.dest);
            }
        } catch (Exception e) {
            log.warn("读取本人历史出差失败，本次不给历史类建议：{}", e.getMessage());
        }
    }

    /**
     * 同部门其他人在同一目的地的历史分布。
     *
     * <p>本人没去过的时候它是第二好的参照：同部门的人跑的线路、见的对象类型相近，
     * 比全公司口径更贴近这一趟。
     */
    private void loadDept(Ctx ctx, Long userId) {
        if (ctx.dest == null) {
            return;
        }
        try {
            SysUser me = sysUserMapper.selectById(userId);
            if (me == null || me.getDeptId() == null) {
                return;
            }
            List<SysUser> mates = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getDeptId, me.getDeptId()));
            List<Long> ids = mates.stream().map(SysUser::getId)
                    .filter(id -> id != null && !id.equals(userId)).toList();
            if (ids.isEmpty()) {
                return;
            }
            LambdaQueryWrapper<TripApply> q = new LambdaQueryWrapper<TripApply>()
                    .in(TripApply::getUserId, ids)
                    .last("limit 100");
            List<TripApply> applies = recentApplies(q);
            summarize(ctx, applies, true);
        } catch (Exception e) {
            log.warn("读取同部门历史出差失败，本次不给部门类建议：{}", e.getMessage());
        }
    }

    /** 全公司在同一目的地的历史分布：本人与部门都没有样本时的最后一道兜底 */
    private void loadCityAll(Ctx ctx) {
        if (ctx.dest == null) {
            return;
        }
        try {
            List<TripApply> applies = recentApplies(new LambdaQueryWrapper<TripApply>().last("limit 200"));
            summarize(ctx, applies, false);
        } catch (Exception e) {
            log.warn("读取同类目的地历史出差失败，本次不给全员类建议：{}", e.getMessage());
        }
    }

    /**
     * 按「首段目的地」把一批单据汇总成类型分布与金额均值。
     *
     * <p>目的地一律取首段：多段行程里第一段才是真正的去处，
     * 按每段拆会把同一张单重复计进好几个目的地。
     *
     * @param dept true 表示这批数据是同部门的，写到 Ctx 的 dept 那几个字段
     */
    private void summarize(Ctx ctx, List<TripApply> applies, boolean dept) {
        if (applies.isEmpty()) {
            return;
        }
        Map<String, String> destOf = firstOf(applies.stream().map(TripApply::getApplyNo).toList(), false);
        Map<String, Integer> typeCnt = new LinkedHashMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        int cnt = 0;
        for (TripApply apply : applies) {
            if (ctx.dest != null && !ctx.dest.equals(destOf.get(apply.getApplyNo()))) {
                continue;
            }
            cnt++;
            if (StringUtils.hasText(apply.getTripType())) {
                typeCnt.merge(apply.getTripType(), 1, Integer::sum);
            }
            if (apply.getEstTotal() != null) {
                sum = sum.add(apply.getEstTotal());
            }
        }
        if (cnt == 0) {
            return;
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(cnt), 2, RoundingMode.HALF_UP);
        if (dept) {
            ctx.deptCnt = cnt;
            ctx.deptTopType = topKey(typeCnt);
            ctx.deptAvgAmount = avg;
        } else {
            ctx.cityCnt = cnt;
            ctx.cityTopTypes = topKeys(typeCnt, 2);
            ctx.cityAvgAmount = avg;
        }
    }

    private List<TripApply> recentApplies(LambdaQueryWrapper<TripApply> extra) {
        return tripApplyMapper.selectList(extra
                .in(TripApply::getStatus, List.of(TripApply.STATUS_APPROVED, TripApply.STATUS_CLOSED))
                .ge(TripApply::getCreatedAt, LocalDateTime.now().minusMonths(12))
                .orderByDesc(TripApply::getCreatedAt));
    }

    /** 每张单取首段的目的地或交通工具 */
    private Map<String, String> firstOf(List<String> applyNos, boolean traffic) {
        Map<String, String> out = new LinkedHashMap<>();
        if (applyNos.isEmpty()) {
            return out;
        }
        List<TripItinerary> items = tripItineraryMapper.selectList(new LambdaQueryWrapper<TripItinerary>()
                .in(TripItinerary::getApplyNo, applyNos)
                .orderByAsc(TripItinerary::getSeq));
        for (TripItinerary it : items) {
            String value = traffic ? it.getTrafficType() : it.getToCity();
            if (StringUtils.hasText(value)) {
                out.putIfAbsent(it.getApplyNo(), value.trim());
            }
        }
        return out;
    }

    /** 每张单的总天数（各段累加） */
    private Map<String, Integer> daysOf(List<String> applyNos) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (applyNos.isEmpty()) {
            return out;
        }
        List<TripItinerary> items = tripItineraryMapper.selectList(new LambdaQueryWrapper<TripItinerary>()
                .in(TripItinerary::getApplyNo, applyNos)
                .orderByAsc(TripItinerary::getSeq));
        for (TripItinerary it : items) {
            if (it.getStartDate() == null || it.getEndDate() == null) {
                continue;
            }
            long d = ChronoUnit.DAYS.between(it.getStartDate(), it.getEndDate()) + 1;
            if (d > 0) {
                out.merge(it.getApplyNo(), (int) d, Integer::sum);
            }
        }
        return out;
    }

    private BigDecimal avgAmount(List<TripApply> applies, Map<String, String> destOf, String dest) {
        BigDecimal sum = BigDecimal.ZERO;
        int cnt = 0;
        for (TripApply apply : applies) {
            if (dest != null && !dest.equals(destOf.get(apply.getApplyNo()))) {
                continue;
            }
            if (apply.getEstTotal() != null && apply.getEstTotal().compareTo(BigDecimal.ZERO) > 0) {
                sum = sum.add(apply.getEstTotal());
                cnt++;
            }
        }
        return cnt == 0 ? null : sum.divide(BigDecimal.valueOf(cnt), 2, RoundingMode.HALF_UP);
    }

    // ---------- 字段建议：出差类型 ----------

    /**
     * 出差类型。已经选了也不一定就放着：
     *
     * <p>员工这一次选的，和他在同一目的地的历史明显不一致时（去过三次以上、每次都填另一个类型），
     * 给一条「要不要改成 xx」的建议。这类建议默认不勾（overwrite=true 由页面处理），
     * 决定权在他——类型一改，对标的那组花费参考跟着换，所以必须让人确认一次。
     *
     * <p>推断顺序即证据强度：本人历史 > 该对象的历史单据 > 同部门 > 对象性质 > 全员分布。
     */
    private Optional<AiFieldAdviceVO> tripTypeField(ApplySubmitRequest request, Ctx ctx,
                                                    List<AdviceGuard.AdviceItem> model) {
        String current = request.getTripType();
        boolean filled = StringUtils.hasText(current);

        if (!filled) {
            Optional<AiFieldAdviceVO> fromRule = inferTripType(ctx);
            if (fromRule.isPresent()) {
                return fromRule;
            }
            return model.stream()
                    .filter(it -> "tripType".equals(it.field()) && AdviceGuard.KIND_FILL.equals(it.kind()))
                    .findFirst()
                    .map(it -> tripTypeCard(it.suggest(), it.why(), AiFieldAdviceVO.SRC_MODEL,
                            "模型推断：" + it.why(), new BigDecimal("0.45"), false));
        }

        if (ctx.historyType != null && ctx.historyCnt >= STRONG_HISTORY && !ctx.historyType.equals(current)) {
            AiFieldAdviceVO vo = tripTypeCard(ctx.historyType,
                    "你在" + ctx.dest + "的 " + ctx.historyCnt + " 趟里多数填的是「" + ctx.historyType + "」",
                    AiFieldAdviceVO.SRC_HISTORY,
                    "本人历史：近 12 个月到" + ctx.dest + " " + ctx.historyCnt + " 趟",
                    new BigDecimal("0.55"), true);
            return Optional.of(vo);
        }
        return Optional.empty();
    }

    /** 按证据强度挨个退让，得出一个最可能的类型 */
    private Optional<AiFieldAdviceVO> inferTripType(Ctx ctx) {
        if (ctx.historyType != null) {
            return Optional.of(tripTypeCard(ctx.historyType,
                    "你近 12 个月去过 " + ctx.dest + " " + ctx.historyCnt + " 趟，多数填的是「" + ctx.historyType + "」",
                    AiFieldAdviceVO.SRC_HISTORY,
                    "本人历史：近 12 个月到" + ctx.dest + " " + ctx.historyCnt + " 趟",
                    new BigDecimal("0.70"), false));
        }
        if (ctx.partnerTopType != null) {
            return Optional.of(tripTypeCard(ctx.partnerTopType,
                    "关联对象「" + ctx.partnerName + "」名下的单多数填的是「" + ctx.partnerTopType + "」",
                    AiFieldAdviceVO.SRC_PARTNER,
                    "往来对象：近 12 个月 " + ctx.partnerApplyCnt + " 张单",
                    new BigDecimal("0.60"), false));
        }
        if (ctx.deptTopType != null) {
            return Optional.of(tripTypeCard(ctx.deptTopType,
                    "同部门去过" + ctx.dest + "的同事多数填的是「" + ctx.deptTopType + "」",
                    AiFieldAdviceVO.SRC_HISTORY,
                    "同部门：近 12 个月到" + ctx.dest + " " + ctx.deptCnt + " 趟",
                    new BigDecimal("0.55"), false));
        }
        if (Partner.TYPE_CUSTOMER.equals(ctx.partnerType) || Partner.TYPE_SUPPLIER.equals(ctx.partnerType)) {
            boolean supplier = Partner.TYPE_SUPPLIER.equals(ctx.partnerType);
            return Optional.of(tripTypeCard(supplier ? "供应商审核" : "商务拜访",
                    "关联对象是" + (supplier ? "供应商" : "客户") + "，这类上门一般按这个类型走",
                    AiFieldAdviceVO.SRC_PARTNER,
                    "往来对象：" + ctx.partnerName + " 是" + (supplier ? "供应商" : "客户"),
                    new BigDecimal("0.50"), false));
        }
        if (!ctx.cityTopTypes.isEmpty()) {
            String top = ctx.cityTopTypes.get(0);
            return Optional.of(tripTypeCard(top,
                    "全公司近 12 个月到" + ctx.dest + "出差，" + ctx.cityCnt + " 趟里「" + top + "」最多",
                    AiFieldAdviceVO.SRC_HISTORY,
                    "全员历史：近 12 个月到" + ctx.dest + " " + ctx.cityCnt + " 趟",
                    new BigDecimal("0.45"), false));
        }
        return Optional.empty();
    }

    /**
     * 组装出差类型那条建议。刻意单独一个方法：这类建议的 source 可能是历史、对象、部门、
     * 对象性质、全员分布五种，evidence 那一行得跟着变，混进别的字段会写串。
     */
    private AiFieldAdviceVO tripTypeCard(String value, String reason, String source,
                                         String sourceText, BigDecimal confidence, boolean overwrite) {
        AiFieldAdviceVO vo = new AiFieldAdviceVO();
        vo.setField("tripType");
        vo.setLabel("出差类型");
        vo.setCurrentValue("");
        vo.setSuggestValue(value);
        vo.setReason(reason);
        vo.setSource(source);
        vo.setSourceText(sourceText);
        vo.setConfidence(confidence);
        vo.setOverwrite(overwrite);
        return vo;
    }

    // ---------- 字段建议：预估金额 ----------

    /**
     * 预估交通费 / 住宿费。值一律由库内数据算，模型碰不到。
     *
     * <p>口径写死在这里，页面会原样展示，改一处就要改说明文字：
     * <ol>
     *   <li>合计基准按证据强弱依次退让：同类花费参考 → 本人同目的地历史 → 同部门 → 全员 → 差标九成。</li>
     *   <li>基准超过差标上限时按上限封顶——建议一个超标的金额等于主动给自己找驳回。</li>
     *   <li>住宿按「住几晚 / 共几天」摊。<b>日期没填时按本人平均天数再退到 2 天</b>，
     *       并在文案里写明「暂按几天几晚估」。这一层假设必须写在明面上，
     *       否则员工改完日期也不会想起点一次重新分析。</li>
     *   <li>取整到百位。</li>
     * </ol>
     */
    private List<AiFieldAdviceVO> amountFields(ApplySubmitRequest request, Ctx ctx) {
        List<AiFieldAdviceVO> out = new ArrayList<>();

        BigDecimal raw = null;
        String source = null;
        String sourceText = null;
        BigDecimal confidence = null;

        boolean refUsable = ctx.costRef != null && ctx.costRef.getLowSample() != Boolean.TRUE
                && ctx.costRef.getAvgAmount() != null;
        if (refUsable) {
            raw = ctx.costRef.getAvgAmount();
            source = AiFieldAdviceVO.SRC_COST_REF;
            sourceText = "同类花费参考：「" + ctx.costRef.getToCity() + " · "
                    + (ctx.costRef.getTripType() == null ? "全部类型" : ctx.costRef.getTripType())
                    + "」近 12 个月 " + ctx.costRef.getSampleCnt() + " 单均值 ¥" + money(raw)
                    + (ctx.costRefExact ? "" : "，你填的这几个条件凑不出足够样本，取的是放宽后的那组");
            confidence = new BigDecimal("0.75");
        } else if (ctx.historyAvgAmount != null) {
            raw = ctx.historyAvgAmount;
            source = AiFieldAdviceVO.SRC_HISTORY;
            sourceText = "本人历史：近 12 个月到" + ctx.dest + " " + ctx.historyCnt + " 趟均值 ¥" + money(raw);
            confidence = new BigDecimal("0.70");
        } else if (ctx.deptAvgAmount != null) {
            raw = ctx.deptAvgAmount;
            source = AiFieldAdviceVO.SRC_HISTORY;
            sourceText = "同部门：近 12 个月到" + ctx.dest + " " + ctx.deptCnt + " 趟均值 ¥" + money(raw);
            confidence = new BigDecimal("0.65");
        } else if (ctx.cityAvgAmount != null) {
            raw = ctx.cityAvgAmount;
            source = AiFieldAdviceVO.SRC_HISTORY;
            sourceText = "全员：近 12 个月到" + ctx.dest + " " + ctx.cityCnt + " 趟均值 ¥" + money(raw);
            confidence = new BigDecimal("0.60");
        } else if (ctx.myAvgAmount != null) {
            raw = ctx.myAvgAmount;
            source = AiFieldAdviceVO.SRC_HISTORY;
            sourceText = "本人历史：近 12 个月 " + ctx.myApplyCnt + " 趟均值 ¥" + money(raw) + "，不含目的地口径";
            confidence = new BigDecimal("0.50");
        } else if (ctx.cap != null) {
            raw = ctx.cap.multiply(CAP_RATIO);
            source = AiFieldAdviceVO.SRC_STANDARD;
            sourceText = "差标：本次行程上限合计 ¥" + money(ctx.cap) + "，按九成留余量";
            confidence = new BigDecimal("0.45");
        } else {
            return out;
        }

        // 日期还没填：先按本人平均天数，再退到两天。这一步是假设，必须写进文案
        boolean assumed = ctx.days <= 0;
        int days = assumed ? (ctx.myAvgDays > 0 ? ctx.myAvgDays : DEFAULT_DAYS) : ctx.days;
        int nights = Math.max(0, days - 1);
        if (assumed) {
            confidence = confidence.min(new BigDecimal("0.55"));
        }

        BigDecimal base = raw;
        String baseText;
        if (ctx.cap != null && raw.compareTo(ctx.cap) > 0) {
            base = ctx.cap;
            confidence = confidence.min(new BigDecimal("0.60"));
            baseText = "同类均值 ¥" + money(raw) + " 高于差标上限 ¥" + money(ctx.cap)
                    + "，按下限合规口径取 ¥" + money(base);
        } else {
            baseText = "同类均值 ¥" + money(raw);
        }

        BigDecimal hotel = base.multiply(BigDecimal.valueOf(nights))
                .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        BigDecimal hotelRounded = round100(hotel);
        BigDecimal trafficRounded = round100(base.subtract(hotel));
        if (hotelRounded.compareTo(BigDecimal.ZERO) < 0 || trafficRounded.compareTo(BigDecimal.ZERO) < 0) {
            return out;
        }

        String share = assumed
                ? "行程日期还没填，暂按 " + days + " 天 " + nights + " 晚估"
                : days + " 天 " + nights + " 晚，住宿按 " + nights + "/" + days + " 摊";

        AiFieldAdviceVO hotelVo = new AiFieldAdviceVO();
        hotelVo.setField("estHotel");
        hotelVo.setLabel("预估住宿费");
        hotelVo.setSuggestValue(hotelRounded.toPlainString());
        hotelVo.setCurrentValue(moneyOrBlank(request.getEstHotel()));
        hotelVo.setReason(baseText + "，" + share + "，住宿约 ¥" + money(hotelRounded));
        hotelVo.setSource(source);
        hotelVo.setSourceText(sourceText);
        hotelVo.setConfidence(confidence);
        hotelVo.setOverwrite(positive(request.getEstHotel()));
        if (!sameAmount(request.getEstHotel(), hotelRounded)) {
            out.add(hotelVo);
        }

        AiFieldAdviceVO trafficVo = new AiFieldAdviceVO();
        trafficVo.setField("estTraffic");
        trafficVo.setLabel("预估交通费");
        trafficVo.setSuggestValue(trafficRounded.toPlainString());
        trafficVo.setCurrentValue(moneyOrBlank(request.getEstTraffic()));
        trafficVo.setReason(baseText + "，交通约 ¥" + money(trafficRounded)
                + "，与住宿合计 ¥" + money(hotelRounded.add(trafficRounded)));
        trafficVo.setSource(source);
        trafficVo.setSourceText(sourceText);
        trafficVo.setConfidence(confidence);
        trafficVo.setOverwrite(positive(request.getEstTraffic()));
        if (!sameAmount(request.getEstTraffic(), trafficRounded)) {
            out.add(trafficVo);
        }
        return out;
    }

    // ---------- 字段建议：超标说明 ----------

    /**
     * 只在「预估合计确实超过差标上限、且还没写说明」时给。
     *
     * <p>给的不是一个能直接交差的成品：具体原因只有当事人知道，系统编一条出来就是替员工签字。
     * 这里只把「超了多少、按哪几段算的」摆清楚，剩下的那句话留给本人补。
     */
    private Optional<AiFieldAdviceVO> exceedReasonField(ApplySubmitRequest request, Ctx ctx) {
        if (ctx.cap == null || StringUtils.hasText(request.getExceedReason())) {
            return Optional.empty();
        }
        BigDecimal estTotal = request.calcEstTotal();
        if (estTotal.compareTo(ctx.cap) <= 0) {
            return Optional.empty();
        }
        String result = standardService.judge(estTotal, ctx.cap);
        BigDecimal over = estTotal.subtract(ctx.cap);
        int pct = ctx.cap.compareTo(BigDecimal.ZERO) == 0 ? 0
                : over.multiply(BigDecimal.valueOf(100))
                        .divide(ctx.cap, 0, RoundingMode.HALF_UP).intValue();

        AiFieldAdviceVO vo = new AiFieldAdviceVO();
        vo.setField("exceedReason");
        vo.setLabel("超标说明");
        vo.setCurrentValue("");
        vo.setConfidence(new BigDecimal("0.60"));
        vo.setSource(AiFieldAdviceVO.SRC_STANDARD);
        vo.setSourceText("差标：本次行程上限合计 ¥" + money(ctx.cap)
                + "，判定 " + StandardService.describe(result));
        vo.setSuggestValue("本次预估 ¥" + money(estTotal) + "，按行程各段差标上限合计 ¥" + money(ctx.cap)
                + " 计，超出 ¥" + money(over) + "（约 " + pct + "%）"
                + (TripApply.STANDARD_EXCEED.equals(result) ? "，已超 10% 必须说明" : "，在 10% 以内")
                + "。请在句末补一句具体原因（如客户指定酒店、无更低价班次），否则审批会被退回补充。");
        vo.setReason("超出的金额与口径已经算好，具体原因仍要本人写——那句话系统替不了");
        vo.setOverwrite(false);
        return Optional.of(vo);
    }

    // ---------- 事由改写 ----------

    /**
     * 事由的改写。<b>只在员工写了东西的前提下谈改写。</b>
     *
     * <p>一个字没写时不给句子。理由和早先那道门槛不同：不是嫌素材少，
     * 而是「改写」这件事本身需要原文——凭空的句子里每一个预期、每一个交付物
     * 都是系统替他答应的，员工一键采用之后就成了他本人的承诺。
     *
     * <p>模型不可用（或压根没配）时由本地模板拼一句，拼出来的句子生硬但一个字不假。
     */
    private AiFieldAdviceVO rewriteField(ApplySubmitRequest request, Ctx ctx, String reason,
                                         List<AdviceGuard.AdviceItem> model) {
        if (reason.isEmpty()) {
            return null;
        }
        Optional<AdviceGuard.AdviceItem> rewrite = model.stream()
                .filter(it -> "reason".equals(it.field()) && AdviceGuard.KIND_REWRITE.equals(it.kind()))
                .findFirst();

        AiFieldAdviceVO vo = new AiFieldAdviceVO();
        vo.setField("reason");
        vo.setLabel("出差事由");
        vo.setCurrentValue(reason);
        vo.setOverwrite(true);

        if (rewrite.isPresent()) {
            AdviceGuard.AdviceItem item = rewrite.get();
            vo.setSuggestValue(item.suggest());
            vo.setSource(AiFieldAdviceVO.SRC_MODEL);
            vo.setSourceText("模型改写：" + item.why());
            vo.setReason("依据 · " + item.evidenceField() + " = " + item.evidenceValue());
            vo.setConfidence(aiService.remote() ? new BigDecimal("0.80") : new BigDecimal("0.50"));
        } else {
            String sentence = template(request, ctx, reason);
            if (sentence == null || sentence.isBlank() || sentence.length() > MAX_REWRITE
                    || sentence.equals(reason)) {
                return null;
            }
            vo.setSuggestValue(sentence);
            vo.setSource(AiFieldAdviceVO.SRC_TEMPLATE);
            vo.setSourceText("本地模板：模型没给可用改写，按你已填的内容拼了一句");
            vo.setReason("照着改两个字就能用，句子里没有你没写过的东西");
            vo.setConfidence(new BigDecimal("0.50"));
        }
        return vo;
    }

    /**
     * 本地兜底。<b>只串员工已经填出来的东西，一个字都不补。</b>
     *
     * <p>早先这方法会看出差类型往下编：填了「验货」就补「现场验货并确认交付节点」，
     * 那些话没有一句来自表单，一键采用之后就成了员工本人写的承诺。
     * 拼出来的句子因此不好看，也不像人写的——这是有意的：
     * 本地模板的定位是「保底有一句能用的」，不是「假装模型还在」。
     */
    private String template(ApplySubmitRequest request, Ctx ctx, String reason) {
        StringBuilder head = new StringBuilder();
        if (StringUtils.hasText(request.getTripType())) {
            head.append(request.getTripType()).append("：");
        }
        if (ctx.dest != null) {
            head.append("赴").append(ctx.dest);
        }
        if (ctx.partnerName != null) {
            head.append(ctx.dest == null ? "" : "，").append("与").append(ctx.partnerName);
        }
        if (ctx.days > 0) {
            head.append("，行程 ").append(ctx.days).append(" 天");
        }
        // 前缀一项都没有时，拼出来就是「。原文」，比不给改写更像系统出错
        if (head.isEmpty()) {
            return null;
        }
        return head.append("。").append(reason).toString();
    }

    // ---------- 方向型建议 ----------

    /**
     * 模型给的、不带具体值的建议。
     *
     * <p>这些建议落地成 items 而不是 fields：没有 suggestValue 就不该出现在可勾选清单里，
     * 否则员工勾了却发现表单没变化。
     *
     * <p>与规则项重复的直接丢：规则已经说过「没写预期产出」，模型再讲一遍
     * 同一件事只会让人觉得这个 AI 在凑数。
     */
    private void hints(List<AdviceGuard.AdviceItem> model, AiAdviceVO vo) {
        for (AdviceGuard.AdviceItem item : model) {
            if (item.suggest() != null) {
                continue;
            }
            String text = item.why();
            if (text == null || text.isBlank()) {
                continue;
            }
            String key = text.length() > 8 ? text.substring(0, 8) : text;
            boolean duplicated = vo.getItems().stream()
                    .anyMatch(point -> point.getText() != null && point.getText().contains(key));
            if (duplicated) {
                continue;
            }
            vo.getItems().add(AiPointVO.of(item.level(), text, item.evidenceField(), item.evidenceValue()));
        }
    }

    /**
     * 规则能算清的几件事，不麻烦模型。
     *
     * <p>放进来是为了让「修改建议」真的包括「已填的值要不要改」——
     * 只说缺什么的话，员工填进去一个明显不合理的数，系统是不会吭声的。
     */
    private void checks(ApplySubmitRequest request, Ctx ctx, AiAdviceVO vo) {
        BigDecimal est = request.calcEstTotal();
        BigDecimal ref = ctx.costRef != null && ctx.costRef.getLowSample() != Boolean.TRUE
                ? ctx.costRef.getAvgAmount()
                : ctx.historyAvgAmount;
        if (positive(est) && positive(ref)) {
            int pct = est.subtract(ref).multiply(BigDecimal.valueOf(100))
                    .divide(ref, 0, RoundingMode.HALF_UP).intValue();
            BigDecimal abs = BigDecimal.valueOf(Math.abs(pct))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (abs.compareTo(DEVIATION) > 0 && pct > 0) {
                vo.getItems().add(AiPointVO.of("high",
                        "预估合计 ¥" + money(est) + " 比同类均值 ¥" + money(ref) + " 高 " + pct + "%，"
                                + "提交前想清楚要不要在超标说明里先写一句原因",
                        "costRef.avgAmount", money(ref)));
            } else if (abs.compareTo(DEVIATION) > 0 && pct < 0) {
                vo.getItems().add(AiPointVO.of("low",
                        "预估合计 ¥" + money(est) + " 比同类均值 ¥" + money(ref) + " 低 " + Math.abs(pct) + "%，"
                                + "报销时如果实际花超了会被要求补说明",
                        "costRef.avgAmount", money(ref)));
            }
        }
        if (ctx.days >= 4 && ctx.segCnt == 1) {
            vo.getItems().add(AiPointVO.of("mid",
                    "行程 " + ctx.days + " 天却只有一段。跨城市的话要按实际到过的城市拆成多段，"
                            + "差标与票据匹配都按段走",
                    "itinerary", String.valueOf(ctx.days)));
        }
    }

    // ---------- 调模型 ----------

    /**
     * 这一版建议挂在哪张单上。
     *
     * <p>有申请号（续写草稿、驳回重提）就用申请号；没有就落到 {@code NEW + 本人 id}
     * 这个固定槽位上——一人同时只会有一张正在填的新单，跟理由草稿的约定一致。
     *
     * <p>前端提交后回传的 bizNo 必须按同一套算法算出来，否则采纳回执对不上任何留痕。
     */
    private String refNoOf(ApplySubmitRequest request) {
        String no = request == null ? null : request.getApplyNo();
        if (no != null && !no.isBlank()) {
            return no.trim();
        }
        try {
            Long uid = UserContext.currentId();
            return "NEW" + (uid == null ? 0L : uid);
        } catch (Exception e) {
            return "NEW0";
        }
    }

    /**
     * 把表单与库内证据打包问模型，回来的是一份建议清单。
     *
     * <p>这里不做「素材够不够」的判断。判断值不值得问的下游尺度有两条，都不在这里：
     * 一是 {@link AdviceGuard} 的逐条校验（编的东西会被丢），
     * 二是 PR-03 里那条「理由为空时不接受改写」。
     *
     * @return 通过校验的建议；一条都不过返回空表，调用方据此走本地模板
     */
    private List<AdviceGuard.AdviceItem> ask(ApplySubmitRequest request, Ctx ctx, String reason) {
        Map<String, Object> input = input(request, ctx, reason);
        String inputJson;
        try {
            inputJson = objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            log.warn("填单建议输入序列化失败：{}", e.getMessage());
            return List.of();
        }

        Set<String> pool = adviceGuard.pool(input);
        AdviceGuard.Facts facts = AdviceGuard.Facts.of(pool, ctx.days, reason.isEmpty());
        /*
         * 新建单还没有申请号，留痕的 refNo 就用「NEW + 本人 id」占一个固定槽位——
         * 一人同时只会有一张在填的新单。这样提交之后的采纳回执才能对上这一条留痕；
         * 用空值的话，这条留痕永远找不到，「采纳率」也就永远算不出来。
         */
        String refNo = refNoOf(request);

        String content = aiCache.get(AiScene.APPLY_ASSIST, inputJson);
        if (content == null) {
            AiTextResult result = aiService.complete(AiScene.APPLY_ASSIST, inputJson);
            content = result == null ? null : result.getContent();
            List<AdviceGuard.AdviceItem> parsed = adviceGuard.accept(content, facts);
            String err = null;
            if (result == null) {
                err = "模型未返回内容（超时 / 不可用）";
            } else if (parsed.isEmpty()) {
                err = "模型有返回，但建议全部未通过校验";
            }
            aiLogService.write(AiScene.APPLY_ASSIST, refNo, inputJson, content,
                    null, null, result == null ? 0L : result.getCostMs(),
                    !parsed.isEmpty(), result == null,
                    err,
                    result == null ? null : result.getRequestTokens(),
                    result == null ? null : result.getResponseTokens());
            if (content != null && !parsed.isEmpty()) {
                aiCache.put(AiScene.APPLY_ASSIST, inputJson, content);
            }
            return parsed;
        }
        return adviceGuard.accept(content, facts);
    }

    /**
     * 交给模型的输入。三块：当前表单、没填的项清单、库里查出来的证据。
     *
     * <p><b>没填的一律写 null</b>，不用占位词补。占位词是这里最容易复发的一个坑：
     * 「该客户」「目的地」看着无害，可模型见了这两个词就会照着它们造句，
     * 造出来的句子主谓齐全却没有任何信息。
     *
     * <p>另外单列一份 {@code unfilled}：模型对「哪些事还悬着」的判断，
     * 决定了它给方向还是给值，这一层不该让它从一堆 null 里自己数。
     */
    private Map<String, Object> input(ApplySubmitRequest request, Ctx ctx, String reason) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("reason", reason.isEmpty() ? null : reason);
        form.put("tripType", StringUtils.hasText(request.getTripType()) ? request.getTripType() : null);
        form.put("partner", partnerBlock(ctx));
        List<Map<String, Object>> segs = itineraryBlocks(request);
        form.put("itineraries", segs.isEmpty() ? null : segs);
        form.put("days", ctx.days > 0 ? ctx.days : null);
        form.put("nights", ctx.days > 0 ? ctx.nights : null);
        form.put("amount", amountBlock(request));
        form.put("exceedReason",
                StringUtils.hasText(request.getExceedReason()) ? request.getExceedReason().trim() : null);
        root.put("form", form);

        root.put("unfilled", unfilled(request, ctx, reason));
        root.put("context", contextBlock(ctx));
        return root;
    }

    /** 还没填的关键项，给模型判断「该给值还是给方向」用 */
    private List<String> unfilled(ApplySubmitRequest request, Ctx ctx, String reason) {
        List<String> lack = new ArrayList<>();
        if (reason.isEmpty()) {
            lack.add("出差事由");
        }
        if (!StringUtils.hasText(request.getTripType())) {
            lack.add("出差类型");
        }
        if (request.getPartnerId() == null) {
            lack.add("关联对象");
        }
        if (ctx.dest == null) {
            lack.add("行程目的地");
        }
        if (ctx.days <= 0) {
            lack.add("行程日期");
        }
        if (!positive(request.getEstTraffic()) && !positive(request.getEstHotel())) {
            lack.add("预估金额");
        }
        return lack;
    }

    /** 对象块。没选对象整块是 null */
    private Map<String, Object> partnerBlock(Ctx ctx) {
        if (ctx.partnerName == null) {
            return null;
        }
        Map<String, Object> partner = new LinkedHashMap<>();
        partner.put("name", ctx.partnerName);
        partner.put("type", ctx.partnerType);
        return partner;
    }

    /**
     * 行程各段。城市等级没填时按目的地补出来——
     * 这是后端算差标上限时用的同一个口径，补出来能让模型判断行程规模。
     */
    private List<Map<String, Object>> itineraryBlocks(ApplySubmitRequest request) {
        List<Map<String, Object>> segs = new ArrayList<>();
        for (ApplySubmitRequest.ItineraryItem item : request.getItineraries() == null
                ? List.<ApplySubmitRequest.ItineraryItem>of() : request.getItineraries()) {
            Map<String, Object> seg = new LinkedHashMap<>();
            putIf(seg, "toCity", item.getToCity());
            putIf(seg, "fromCity", item.getFromCity());
            putIf(seg, "trafficType", item.getTrafficType());
            putIf(seg, "startDate", item.getStartDate());
            putIf(seg, "endDate", item.getEndDate());
            if (seg.isEmpty()) {
                continue;
            }
            String level = StringUtils.hasText(item.getCityLevel())
                    ? item.getCityLevel() : standardService.cityLevelOf(item.getToCity());
            putIf(seg, "cityLevel", level);
            segs.add(seg);
        }
        return segs;
    }

    /** 金额块。三项全空给 null；有一项就带上，让模型判断这趟的规模 */
    private Map<String, Object> amountBlock(ApplySubmitRequest request) {
        BigDecimal traffic = request.getEstTraffic();
        BigDecimal hotel = request.getEstHotel();
        if (!positive(traffic) && !positive(hotel)) {
            return null;
        }
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("traffic", traffic);
        amount.put("hotel", hotel);
        amount.put("total", request.calcEstTotal());
        return amount;
    }

    /** 库内证据。每一项的取值都能被用来锚定模型输出里的同一 Lite 数字 */
    private Map<String, Object> contextBlock(Ctx ctx) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (ctx.costRef != null) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("toCity", ctx.costRef.getToCity());
            ref.put("tripType", ctx.costRef.getTripType());
            ref.put("avg", ctx.costRef.getAvgAmount());
            ref.put("min", ctx.costRef.getMinAmount());
            ref.put("max", ctx.costRef.getMaxAmount());
            ref.put("sampleCnt", ctx.costRef.getSampleCnt());
            ref.put("exact", ctx.costRefExact);
            context.put("costRef", ref);
        }
        if (ctx.cap != null) {
            context.put("standardCap", ctx.cap);
        }
        if (ctx.partnerName != null) {
            Map<String, Object> partner = new LinkedHashMap<>();
            partner.put("name", ctx.partnerName);
            partner.put("type", ctx.partnerType);
            partner.put("output", ctx.partnerOutput == null ? BigDecimal.ZERO : ctx.partnerOutput);
            partner.put("resultCnt", ctx.partnerResultCnt == null ? 0 : ctx.partnerResultCnt);
            partner.put("applyCnt", ctx.partnerApplyCnt);
            partner.put("topType", ctx.partnerTopType);
            context.put("partner", partner);
        }
        Map<String, Object> mine = new LinkedHashMap<>();
        if (ctx.myApplyCnt > 0) {
            mine.put("destCnt", ctx.historyCnt);
            mine.put("destTopType", ctx.historyType);
            mine.put("destAvgAmount", ctx.historyAvgAmount);
            mine.put("applyCnt", ctx.myApplyCnt);
            mine.put("avgDays", ctx.myAvgDays);
            mine.put("avgAmount", ctx.myAvgAmount);
            mine.put("topCities", ctx.myTopCities.isEmpty() ? null : ctx.myTopCities);
            mine.put("trafficTop", ctx.myTrafficTop);
            context.put("history", mine);
        }
        if (ctx.deptCnt > 0) {
            Map<String, Object> dept = new LinkedHashMap<>();
            dept.put("destCnt", ctx.deptCnt);
            dept.put("destTopType", ctx.deptTopType);
            dept.put("destAvgAmount", ctx.deptAvgAmount);
            context.put("dept", dept);
        }
        if (ctx.cityCnt > 0) {
            Map<String, Object> all = new LinkedHashMap<>();
            all.put("destCnt", ctx.cityCnt);
            all.put("topTypes", ctx.cityTopTypes.isEmpty() ? null : ctx.cityTopTypes);
            all.put("destAvgAmount", ctx.cityAvgAmount);
            context.put("cityAll", all);
        }
        return context;
    }

    private static void putIf(Map<String, Object> map, String key, String value) {
        if (StringUtils.hasText(value)) {
            map.put(key, value.trim());
        }
    }

    // ---------- 小工具 ----------

    private static Long currentUserId() {
        try {
            return UserContext.currentId();
        } catch (Exception e) {
            return null;
        }
    }

    private static <K, V extends Comparable<? super V>> String topKey(Map<K, V> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> String.valueOf(e.getKey()))
                .orElse(null);
    }

    private static <K, V extends Comparable<? super V>> List<String> topKeys(Map<K, V> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<K, V>comparingByValue().reversed())
                .limit(limit)
                .map(e -> String.valueOf(e.getKey()))
                .toList();
    }

    /** 取整到百位：填预估时整百的数才好改 */
    private static BigDecimal round100(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.divide(STEP, 0, RoundingMode.HALF_UP).multiply(STEP).setScale(2);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private static boolean sameAmount(BigDecimal current, BigDecimal suggest) {
        return current != null && current.compareTo(suggest) == 0;
    }

    private static String money(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String moneyOrBlank(BigDecimal value) {
        return value == null ? "" : money(value);
    }

    private int days(ApplySubmitRequest request) {
        List<ApplySubmitRequest.ItineraryItem> list = request.getItineraries();
        if (list == null) {
            return 0;
        }
        int days = 0;
        for (ApplySubmitRequest.ItineraryItem item : list) {
            try {
                LocalDate start = LocalDate.parse(item.getStartDate().trim());
                LocalDate end = LocalDate.parse(item.getEndDate().trim());
                long d = ChronoUnit.DAYS.between(start, end) + 1;
                if (d > 0) {
                    days += (int) d;
                }
            } catch (Exception e) {
                // 日期没填全就跳过，天数只是建议里的一个分摊依据
            }
        }
        return days;
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

    /**
     * 一次建议要用到的库内上下文。全是可空字段：查不到就保持默认值，
     * 由各生成器自己决定这一项还能不能给。
     */
    private static final class Ctx {
        private String dest;
        private int days;
        private int nights;
        private int segCnt;
        /** 各段差标上限合计；null = 这一段没配标准 */
        private BigDecimal cap;
        private CostRefVO costRef;
        /** cost_ref 是否精确命中（目的地 + 类型），false 表示退到了同城不限类型 */
        private boolean costRefExact;
        private String partnerName;
        private String partnerType;
        private BigDecimal partnerOutput;
        /** 该对象近 12 个月被回填的业务结果条数 */
        private Long partnerResultCnt;
        private int partnerApplyCnt;
        private String partnerTopType;
        private List<String> myTopCities = List.of();
        private String myTrafficTop;
        private int myApplyCnt;
        private int myAvgDays;
        private BigDecimal myAvgAmount;
        /** 本人近 12 个月到同一目的地的趟数 */
        private int historyCnt;
        private String historyType;
        private BigDecimal historyAvgAmount;
        private int deptCnt;
        private String deptTopType;
        private BigDecimal deptAvgAmount;
        private int cityCnt;
        private List<String> cityTopTypes = List.of();
        private BigDecimal cityAvgAmount;

        /**
         * 有没有任何一条库内证据。没有的话页面要如实标注「库里没找到能对标的数据」——
         * 这时候的建议是通用的，混着说等于把「没数据」和「你填得没问题」当成一回事。
         */
        boolean hasAnchor() {
            return costRef != null || cap != null || partnerName != null
                    || historyCnt > 0 || deptCnt > 0 || cityCnt > 0;
        }
    }
}
