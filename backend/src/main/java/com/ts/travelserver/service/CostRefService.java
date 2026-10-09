package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.dto.CostRefQuery;
import com.ts.travelserver.entity.CostRef;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.mapper.CostRefMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.mapper.TripItineraryMapper;
import com.ts.travelserver.vo.CostRefVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 同类花费参考（cost_ref）。
 *
 * <p>同目的地、同出差类型近 12 个月的花费均值与区间，填预估金额时给个参照（FR-0104）。
 * 样本量不足 5 条的不展示，避免样本太少反而误导（设计报告 6.3.4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostRefService {

    /**
     * 一档参考「能用」的最低样本数。低于这个数才往宽一档退。
     *
     * <p>取 3 不取 5：5 是设计报告里「样本充足」的线，只用来决定要不要提示
     * 「只够看个大概」；拿它当采用门槛会让填得越具体越查不到。
     * 3 是能排出一个区间的下限——样本 2 条时 P25 与 P75 会退化成同一条边的两个点。
     */
    private static final int MIN_USABLE = 3;

    private final CostRefMapper costRefMapper;
    private final TripApplyMapper tripApplyMapper;
    private final TripItineraryMapper tripItineraryMapper;

    /**
     * 查参考值：先按（目的地 + 出差类型）精确匹配，没命中就降级成只按目的地取样本量最大的一条。
     *
     * @return 命中的参考行；都没命中返回 null
     */
    public CostRef match(String toCity, String tripType) {
        if (toCity == null || toCity.isBlank()) {
            return null;
        }
        boolean hasType = tripType != null && !tripType.isBlank();
        CostRef exact = costRefMapper.selectOne(new LambdaQueryWrapper<CostRef>()
                .eq(CostRef::getToCity, toCity)
                .eq(hasType, CostRef::getTripType, tripType)
                .orderByDesc(CostRef::getSampleCnt)
                .last("limit 1"));
        if (exact != null || !hasType) {
            return exact;
        }
        return costRefMapper.selectOne(new LambdaQueryWrapper<CostRef>()
                .eq(CostRef::getToCity, toCity)
                .orderByDesc(CostRef::getSampleCnt)
                .last("limit 1"));
    }

    /**
     * 包装成前端要的 VO，顺便算出本次预估落在区间里的位置。
     *
     * @param estAmount 本次预估合计，用来算位置；不传就不算
     */
    public CostRefVO query(String toCity, String tripType, BigDecimal estAmount) {
        CostRef ref = match(toCity, tripType);
        if (ref == null) {
            return null;
        }
        CostRefVO vo = new CostRefVO();
        vo.setToCity(ref.getToCity());
        vo.setTripType(ref.getTripType());
        vo.setAvgAmount(ref.getAvgAmount());
        vo.setMinAmount(ref.getMinAmount());
        vo.setMaxAmount(ref.getMaxAmount());
        vo.setSampleCnt(ref.getSampleCnt());
        vo.setLowSample(!ref.available());
        vo.setEstAmount(estAmount);
        vo.setPosInRange(position(ref, estAmount));
        return vo;
    }

    /**
     * 实时聚合：按员工当前已填的条件即时统计同类单据。
     *
     * <h3>为什么不全用 cost_ref 那张聚合表</h3>
     * 填单页要支持「填一项就重算一次」，条件组合是任意的（目的地 / 类型 / 等级 / 交通方式
     * 四个维度随便出现几个）。聚合表只有 (目的地, 类型) 一个分组键，要支持就得把它扩成四元组，
     * 分组数会随维度指数膨胀，而每组样本会碎到只剩一两单——参考值反而更不可信。
     * 所以填单页直接对单据表做即时聚合，成本是每次输入一次查询（有防抖，量级无所谓）。
     * cost_ref 与定时刷新保留给批量场景（AI 建议的历史口径、报表导出）。
     *
     * <h3>查不到就放宽，不放宽就什么都看不到</h3>
     * 演示库里所有已通过单据的交通方式都是高铁、城市等级几乎都是一级。
     * 员工选了飞机，四维严格匹配就是 0 样本，页面上写「近 12 个月无相关单据」——
     * 可同城同类型明明有 3 单。所以这里按优先级丢维度重查：
     * 交通方式 → 城市等级 → 出差类型，目的地是最后一道（丢掉它就等于拿全库平均，没有参考价值）。
     * 真正生效的维度会写回 VO，relaxed 标 true，前端必须把它显示出来。
     *
     * @return 命中一档就有值；放宽到最宽仍是一条单据都没有才返回 null（前端显示「无相关单据」）
     */
    public CostRefVO queryLive(CostRefQuery q) {
        if (q == null || q.empty()) {
            return null;
        }
        LocalDateTime since = LocalDateTime.now().minusMonths(12);

        // 首段行程是分组基准：多段行程的全单金额归到某一段上都会重复计数
        List<TripItinerary> firsts = tripItineraryMapper.selectList(new LambdaQueryWrapper<TripItinerary>()
                .eq(TripItinerary::getSeq, 1)
                .eq(StringUtils.hasText(q.getToCity()), TripItinerary::getToCity, q.getToCity()));
        if (firsts.isEmpty()) {
            return null;
        }
        Map<String, TripItinerary> firstMap = new LinkedHashMap<>();
        for (TripItinerary it : firsts) {
            firstMap.put(it.getApplyNo(), it);
        }

        List<TripApply> applies = tripApplyMapper.selectList(new LambdaQueryWrapper<TripApply>()
                .in(TripApply::getApplyNo, firstMap.keySet())
                .in(TripApply::getStatus, List.of(TripApply.STATUS_APPROVED, TripApply.STATUS_CLOSED))
                .ge(TripApply::getCreatedAt, since));
        if (applies.isEmpty()) {
            return null;
        }
        Map<String, Integer> daysOf = wholeTripDays(applies.stream().map(TripApply::getApplyNo).toList());

        List<Scope> ladder = ladder(q);
        CostRefVO fallback = null;
        int fallbackCnt = 0;
        for (int i = 0; i < ladder.size(); i++) {
            List<TripApply> hit = new ArrayList<>();
            for (TripApply a : applies) {
                if (ladder.get(i).matches(a, firstMap.get(a.getApplyNo()))) {
                    hit.add(a);
                }
            }
            /*
             * 够不够用看的是 MIN_USABLE（3），不是 CostRef.MIN_SAMPLE_CNT（5）。
             * 5 是「样本充足」的提示线，只用来置 lowSample 让前端加一句「只够看个大概」；
             * 拿它当采用门槛会把填得越具体越查不到这件事放大——
             * 员工填了「东莞 · 商务拜访」，严格档有 3 单，却因为不满 5 被放宽成
             * 「东莞不限类型 5 单」，看着样本多了，实际答非所问。
             */
            if (hit.size() >= MIN_USABLE) {
                return build(ladder.get(i), hit, daysOf, q, i > 0);
            }
            if (hit.size() > fallbackCnt) {
                fallbackCnt = hit.size();
                fallback = build(ladder.get(i), hit, daysOf, q, i > 0);
            }
        }
        // 一路都没凑够 3 单时退而求其次：有 1~2 单也比说「无相关单据」强，lowSample 会带上
        return fallbackCnt > 0 ? fallback : null;
    }

    /**
     * 整趟天数：跨段按首尾跨度算，不是各段相加——
     * 10.1-10.3 深圳接 10.3-10.5 东莞，两段相加是 6 天，实际整趟 5 天，重叠那天被数了两遍。
     */
    private Map<String, Integer> wholeTripDays(List<String> applyNos) {
        Map<String, LocalDate> minStart = new HashMap<>();
        Map<String, LocalDate> maxEnd = new HashMap<>();
        for (TripItinerary it : tripItineraryMapper.selectList(
                new LambdaQueryWrapper<TripItinerary>().in(TripItinerary::getApplyNo, applyNos))) {
            if (it.getStartDate() == null || it.getEndDate() == null) {
                continue;
            }
            minStart.merge(it.getApplyNo(), it.getStartDate(), (a, b) -> a.isBefore(b) ? a : b);
            maxEnd.merge(it.getApplyNo(), it.getEndDate(), (a, b) -> a.isAfter(b) ? a : b);
        }
        Map<String, Integer> out = new HashMap<>();
        minStart.forEach((no, start) -> {
            LocalDate end = maxEnd.get(no);
            if (end != null && !end.isBefore(start)) {
                out.put(no, (int) ChronoUnit.DAYS.between(start, end) + 1);
            }
        });
        return out;
    }

    /**
     * 放宽阶梯，从最严到最宽。目的地不为空时以「只剩目的地」为终点：
     * 丢掉目的地就变成全库平均，跨城市混在一起，对填金额没有参考价值。
     * 目的地没填时（员工先选了类型）从类型的那一档开始，最后退到全库。
     */
    private List<Scope> ladder(CostRefQuery q) {
        String city = blankToNull(q.getToCity());
        String type = blankToNull(q.getTripType());
        String level = blankToNull(q.getCityLevel());
        String traffic = blankToNull(q.getTrafficType());

        List<Scope> out = new ArrayList<>();
        if (city != null) {
            out.add(new Scope(city, type, level, traffic));
            out.add(new Scope(city, type, level, null));
            out.add(new Scope(city, type, null, null));
            out.add(new Scope(city, null, null, null));
        } else {
            out.add(new Scope(null, type, level, traffic));
            out.add(new Scope(null, type, level, null));
            out.add(new Scope(null, type, null, null));
            out.add(new Scope(null, null, null, null));
        }
        // 某个维度没填时会出现相邻两档完全一样，去掉省一次无谓的统计
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    private CostRefVO build(Scope scope, List<TripApply> hit, Map<String, Integer> daysOf,
                            CostRefQuery q, boolean relaxed) {
        List<BigDecimal> traffic = new ArrayList<>();
        List<BigDecimal> hotel = new ArrayList<>();
        List<BigDecimal> total = new ArrayList<>();
        List<BigDecimal> days = new ArrayList<>();
        for (TripApply a : hit) {
            traffic.add(nz(a.getEstTraffic()));
            // 住宿只收住过的单：当天往返的 0 会把均值拉成「这趟不用住」
            if (a.getEstHotel() != null && a.getEstHotel().compareTo(BigDecimal.ZERO) > 0) {
                hotel.add(a.getEstHotel());
            }
            total.add(nz(a.getEstTotal()));
            Integer d = daysOf.get(a.getApplyNo());
            if (d != null) {
                days.add(BigDecimal.valueOf(d));
            }
        }

        CostRefVO vo = new CostRefVO();
        vo.setToCity(scope.city());
        vo.setTripType(scope.type());
        vo.setCityLevel(scope.level());
        vo.setTrafficType(scope.traffic());
        vo.setSampleCnt(hit.size());
        vo.setSameDayCnt(hit.size() - hotel.size());
        vo.setLowSample(hit.size() < CostRef.MIN_SAMPLE_CNT);
        vo.setRelaxed(relaxed);

        vo.setAvgTraffic(avg(traffic));
        vo.setMinTraffic(quantile(traffic, 1));
        vo.setMaxTraffic(quantile(traffic, 3));
        vo.setAvgHotel(avg(hotel));
        vo.setMinHotel(quantile(hotel, 1));
        vo.setMaxHotel(quantile(hotel, 3));
        vo.setAvgAmount(avg(total));
        vo.setMinAmount(quantile(total, 1));
        vo.setMaxAmount(quantile(total, 3));
        vo.setAvgDays(avg(days));
        vo.setMinDays(quantile(days, 1));
        vo.setMaxDays(quantile(days, 3));

        vo.setEstTraffic(q.getEstTraffic());
        vo.setEstHotel(q.getEstHotel());
        vo.setEstDays(q.getEstDays());
        BigDecimal est = q.getEstTraffic() == null && q.getEstHotel() == null
                ? null
                : nz(q.getEstTraffic()).add(nz(q.getEstHotel()));
        vo.setEstAmount(est);
        if (est != null && vo.getMinAmount() != null && vo.getMaxAmount() != null) {
            vo.setPosInRange(position(vo.getMinAmount(), vo.getMaxAmount(), est));
        }
        return vo;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static BigDecimal avg(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * 分位数。quarter=1 取 P25，quarter=3 取 P75。
     * 样本不足 2 条返回 null——一个点谈不上区间，给区间就是装作有分布。
     */
    private static BigDecimal quantile(List<BigDecimal> values, int quarter) {
        if (values.size() < 2) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = quarter == 1 ? sorted.size() / 4 : Math.min(sorted.size() - 1, sorted.size() * 3 / 4);
        return sorted.get(Math.max(0, idx));
    }

    /** 一组比对维度。为 null 表示该维度不参与筛选 */
    private record Scope(String city, String type, String level, String traffic) {
        boolean matches(TripApply apply, TripItinerary first) {
            if (city != null && !city.equals(first == null ? null : first.getToCity())) {
                return false;
            }
            if (type != null && !type.equals(apply.getTripType())) {
                return false;
            }
            if (level != null && !level.equals(first == null ? null : first.getCityLevel())) {
                return false;
            }
            return traffic == null || Objects.equals(traffic, first == null ? null : first.getTrafficType());
        }
    }

    /**
     * 重算参考值：按（目的地，出差类型）分组统计近 12 个月已通过的申请单。
     *
     * <h3>为什么把「表里已有的组」也拉进来重算</h3>
     * 只扫单据的话，某个组合一旦没人再去，它那行参考值就永远停在最后一次的样子：
     * 样本数是三年前的，页面上却照常显示「近 12 个月 N 单」。
     * 手动刷新时数据还没翻新就有人看到；挂上定时任务之后更是每晚糊一层。
     * 所以这里先把表里现存的组全放进来，让近 12 个月没样本的组合
     * 有机会走到下面的重算，最终因为样本数为 0 被清掉。
     *
     * <h3>样本为 0 的组合直接删行</h3>
     * cost_ref 是派生数据，丢了能从申请单算回来。留着一堆空组只会让
     * 「查到参考值但样本不足」和「压根没有参考」这两种情况含混不清。
     * 删掉之后 A-06 返回 null，前端走「暂无同类参考」那条空态，比给一组过期数字诚实。
     *
     * @return 重算了多少组（不含被清理的数量）
     */
    public int refresh() {
        LocalDateTime since = LocalDateTime.now().minusMonths(12);

        Map<String, List<BigDecimal>> groups = new LinkedHashMap<>();
        for (CostRef exist : costRefMapper.selectList(null)) {
            groups.putIfAbsent(keyOf(exist.getToCity(), exist.getTripType()), new ArrayList<>());
        }

        List<TripApply> applies = tripApplyMapper.selectList(new LambdaQueryWrapper<TripApply>()
                .in(TripApply::getStatus, List.of(TripApply.STATUS_APPROVED, TripApply.STATUS_CLOSED))
                .ge(TripApply::getCreatedAt, since));

        for (TripApply apply : applies) {
            List<TripItinerary> items = tripItineraryMapper.selectList(
                    new LambdaQueryWrapper<TripItinerary>()
                            .eq(TripItinerary::getApplyNo, apply.getApplyNo())
                            .orderByAsc(TripItinerary::getSeq));
            if (items.isEmpty()) {
                continue;
            }
            // 只取首段目的地：参考值导出的是「这一趟去哪儿」，多段行程的全单金额
            // 归到某一段上都会重复计数，这里统一按第一段算，也与员工填单时先选的目的地一致
            groups.computeIfAbsent(keyOf(items.get(0).getToCity(), apply.getTripType()), k -> new ArrayList<>())
                    .add(apply.getEstTotal() == null ? BigDecimal.ZERO : apply.getEstTotal());
        }

        int changed = 0;
        int dropped = 0;
        for (Map.Entry<String, List<BigDecimal>> entry : groups.entrySet()) {
            String[] key = entry.getKey().split("\\|", -1);
            List<BigDecimal> values = entry.getValue();

            CostRef exist = costRefMapper.selectOne(new LambdaQueryWrapper<CostRef>()
                    .eq(CostRef::getToCity, key[0])
                    .eq(CostRef::getTripType, key[1])
                    .last("limit 1"));
            if (values.isEmpty()) {
                if (exist != null) {
                    costRefMapper.deleteById(exist.getId());
                    dropped++;
                }
                continue;
            }

            List<BigDecimal> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            BigDecimal sum = sorted.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(sorted.size()), 2, RoundingMode.HALF_UP);
            // 区间取 P25 / P75，样本少的时候会退化成最小最大值，够用
            BigDecimal min = sorted.get(Math.max(0, sorted.size() / 4));
            BigDecimal max = sorted.get(Math.min(sorted.size() - 1, sorted.size() * 3 / 4));

            CostRef ref = exist != null ? exist : new CostRef();
            ref.setToCity(key[0]);
            ref.setTripType(key[1]);
            ref.setAvgAmount(avg);
            ref.setMinAmount(min);
            ref.setMaxAmount(max);
            ref.setSampleCnt(sorted.size());
            if (ref.getId() == null) {
                costRefMapper.insert(ref);
            } else {
                costRefMapper.updateById(ref);
            }
            changed++;
        }
        log.info("同类花费参考重算完成：更新 {} 组，样本过期清理 {} 组", changed, dropped);
        return changed;
    }

    /**
     * 分组键。拼的时候用找不到分隔符把 null 收成一串，拆的时候用 -1 保住尾部空串，
     * 否则「没填出差类型」那组解析出来会少一个元素，数组越界。
     */
    private String keyOf(String toCity, String tripType) {
        return (toCity == null ? "" : toCity) + "|" + (tripType == null ? "" : tripType);
    }

    /**
     * 本次预估在区间中的位置，0~1；低于下限为负、高于上限大于 1。
     */
    private BigDecimal position(CostRef ref, BigDecimal estAmount) {
        if (estAmount == null || ref.getMinAmount() == null || ref.getMaxAmount() == null) {
            return null;
        }
        return position(ref.getMinAmount(), ref.getMaxAmount(), estAmount);
    }

    private BigDecimal position(BigDecimal min, BigDecimal max, BigDecimal estAmount) {
        BigDecimal span = max.subtract(min);
        if (span.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return estAmount.subtract(min).divide(span, 4, RoundingMode.HALF_UP);
    }
}
