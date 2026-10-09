package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.dto.ConfigLogQuery;
import com.ts.travelserver.dto.BudgetUpdateRequest;
import com.ts.travelserver.dto.DeptCreateRequest;
import com.ts.travelserver.dto.StandardUpdateRequest;
import com.ts.travelserver.entity.ConfigLog;
import com.ts.travelserver.entity.Dept;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.entity.TripStandard;
import com.ts.travelserver.mapper.ConfigLogMapper;
import com.ts.travelserver.mapper.DeptMapper;
import com.ts.travelserver.mapper.SysUserMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.mapper.TripStandardMapper;
import com.ts.travelserver.service.support.ConfigLogRecorder;
import com.ts.travelserver.vo.ApprovalViewVO;
import com.ts.travelserver.vo.ConfigLogVO;
import com.ts.travelserver.vo.DeptVO;
import com.ts.travelserver.vo.StandardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 差标与预算配置（模块 06，H-01 ~ H-04），以及提交申请时的差标校验。
 *
 * <p>差标校验三档（设计报告 6.3.3）：不超上限 NORMAL；超 10% 以内 HIGH；超 10% 以上 EXCEED。
 * EXCEED 必须填超标说明，否则返回 2003（BR-01）。
 *
 * <p>调整差标不 UPDATE 旧行，而是插一条新的生效日期行，这样任何时点的标准都能追溯（BR-09，设计报告 5.9）。
 *
 * <p>所有写操作（差标新增 / 调整 / 删除、部门新增 / 删除 / 调整预算）都会往 config_log 追加一条，
 * 带上前后值、操作人和管理层填的原因。留痕表只追加，不修改、不删除。
 *
 * <p>TODO-需要你补基础数据：目的地城市等级没有字典表，这里先用一份内置映射兜底
 * （北上广深一级、省会与新一线二级、广东其他地级市三级、其余三级）。
 * 正式做法是建一张 city_level 字典表，或者让前端在行程段里传 cityLevel（字段已留）。
 */
@Service
@RequiredArgsConstructor
public class StandardService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 差标上限允许的浮动比例，超出这个比例就是 EXCEED */
    @Value("${travel.standard.high-ratio:0.10}")
    private double highRatio;

    private final TripStandardMapper tripStandardMapper;
    private final DeptMapper deptMapper;
    private final SysUserMapper sysUserMapper;
    private final TripApplyMapper tripApplyMapper;
    private final ConfigLogMapper configLogMapper;
    private final ConfigLogRecorder logRecorder;

    // ---------- 差标校验 ----------

    /**
     * 各段上限之和。某段查不到标准就跳过（不算进上限），
     * 免得因为基础数据不全把整单判成超限。
     */
    public BigDecimal capTotal(List<TripItinerary> itineraries) {
        BigDecimal total = BigDecimal.ZERO;
        if (itineraries == null) {
            return total;
        }
        for (TripItinerary item : itineraries) {
            BigDecimal max = currentMax(item.getCityLevel(), item.getTrafficType());
            if (max != null) {
                total = total.add(max);
            }
        }
        return total;
    }

    /**
     * 逐段差标明细。整单预估按段数平均摊到每一段，
     * 这样「Σ 本段预估 = 整单预估」，逐段结论和整单结论能对得上。
     */
    public List<ApprovalViewVO.StandardHintVO> hints(List<TripItinerary> itineraries, BigDecimal estTotal) {
        List<ApprovalViewVO.StandardHintVO> hints = new ArrayList<>();
        if (itineraries == null || itineraries.isEmpty()) {
            return hints;
        }
        BigDecimal per = estTotal == null
                ? BigDecimal.ZERO
                : estTotal.divide(BigDecimal.valueOf(itineraries.size()), 2, RoundingMode.HALF_UP);
        for (TripItinerary item : itineraries) {
            ApprovalViewVO.StandardHintVO hint = new ApprovalViewVO.StandardHintVO();
            hint.setCityLevel(item.getCityLevel());
            hint.setTrafficType(item.getTrafficType());
            BigDecimal max = currentMax(item.getCityLevel(), item.getTrafficType());
            hint.setMaxAmount(max);
            hint.setEstAmount(per);
            hint.setResult(max == null ? null : judge(per, max));
            hints.add(hint);
        }
        return hints;
    }

    /**
     * 三档判定。上限为空（该城市等级 + 交通方式没配标准）时按 NORMAL 处理，
     * 并在超标说明里留一句，避免基础数据不全卡住主流程。
     */
    public String judge(BigDecimal estTotal, BigDecimal cap) {
        if (estTotal == null || cap == null || cap.compareTo(BigDecimal.ZERO) <= 0) {
            return TripApply.STANDARD_NORMAL;
        }
        BigDecimal limit = cap.multiply(BigDecimal.valueOf(1 + highRatio));
        if (estTotal.compareTo(cap) <= 0) {
            return TripApply.STANDARD_NORMAL;
        }
        if (estTotal.compareTo(limit) <= 0) {
            return TripApply.STANDARD_HIGH;
        }
        return TripApply.STANDARD_EXCEED;
    }

    /**
     * 取当前生效的标准：生效日期不晚于今天的最新一条。
     */
    public BigDecimal currentMax(String cityLevel, String trafficType) {
        if (cityLevel == null || cityLevel.isBlank() || trafficType == null || trafficType.isBlank()) {
            return null;
        }
        List<TripStandard> list = tripStandardMapper.selectList(new LambdaQueryWrapper<TripStandard>()
                .eq(TripStandard::getCityLevel, cityLevel)
                .eq(TripStandard::getTrafficType, trafficType)
                .le(TripStandard::getEffectiveAt, LocalDate.now())
                .orderByDesc(TripStandard::getEffectiveAt)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0).getMaxAmount();
    }

    // ---------- 差标维护：查 / 增 / 改 / 删 ----------

    /**
     * H-01：差标列表，每组只返回当前生效的那一档，历史版本不删但也不占位。
     */
    public List<StandardVO> listStandards() {
        List<TripStandard> all = tripStandardMapper.selectList(new LambdaQueryWrapper<TripStandard>()
                .orderByAsc(TripStandard::getCityLevel)
                .orderByAsc(TripStandard::getTrafficType)
                .orderByDesc(TripStandard::getEffectiveAt));
        List<StandardVO> result = new ArrayList<>();
        for (TripStandard item : all) {
            String group = item.getCityLevel() + "|" + item.getTrafficType();
            boolean exists = result.stream().anyMatch(vo -> group.equals(vo.getCityLevel() + "|" + vo.getTrafficType()));
            if (exists) {
                continue;
            }
            result.add(toStandardVO(item, prevMax(all, item)));
        }
        return result;
    }

    /**
     * 新增一档差标。用于这个组合从来没配过标准的场景，
     * 已经配过的话应该走 {@link #updateStandard}，分成两条是为了回执与留痕能说清是一次「新增」。
     */
    public StandardVO createStandard(StandardUpdateRequest request) {
        LocalDate effectiveAt = request.getEffectiveAt() == null ? LocalDate.now() : request.getEffectiveAt();
        Long exists = tripStandardMapper.selectCount(new LambdaQueryWrapper<TripStandard>()
                .eq(TripStandard::getCityLevel, request.getCityLevel())
                .eq(TripStandard::getTrafficType, request.getTrafficType()));
        if (exists != null && exists > 0) {
            throw BusinessException.paramError(request.getCityLevel() + "-" + request.getTrafficType()
                    + " 已经配过标准了，要改金额或日期请点「调整」");
        }

        TripStandard row = new TripStandard();
        row.setCityLevel(request.getCityLevel());
        row.setTrafficType(request.getTrafficType());
        row.setMaxAmount(request.getMaxAmount());
        row.setEffectiveAt(effectiveAt);
        row.setUpdatedBy(UserContext.currentId());
        tripStandardMapper.insert(row);

        logRecorder.record(ConfigLog.TYPE_STANDARD, keyOf(row.getCityLevel(), row.getTrafficType()), ConfigLog.ACTION_INSERT,
                null, row.getMaxAmount(), request.getReason());

        return toStandardVO(row, null);
    }

    /**
     * H-02：调整差标。插一条新版本，不动旧行，留痕里记录前后两版金额与管理层填的原因。
     */
    public StandardVO updateStandard(StandardUpdateRequest request) {
        LocalDate effectiveAt = request.getEffectiveAt() == null ? LocalDate.now() : request.getEffectiveAt();
        // 同一组（等级+交通方式+生效日期）只允许一条，先查一次给业务提示；
        // 不然直接撞 uk_level_traffic 唯一键，DuplicateKeyException 被全局处理器
        // 兜底成 5000「系统繁忙」，测试的人根本不知道是自己重复插入了
        Long dup = tripStandardMapper.selectCount(new LambdaQueryWrapper<TripStandard>()
                .eq(TripStandard::getCityLevel, request.getCityLevel())
                .eq(TripStandard::getTrafficType, request.getTrafficType())
                .eq(TripStandard::getEffectiveAt, effectiveAt));
        if (dup != null && dup > 0) {
            throw BusinessException.paramError(request.getCityLevel() + "-" + request.getTrafficType()
                    + " 在 " + effectiveAt + " 已有标准，换一个生效日期即可（同组同日期只能插一条）");
        }
        BigDecimal before = currentMax(request.getCityLevel(), request.getTrafficType());

        TripStandard row = new TripStandard();
        row.setCityLevel(request.getCityLevel());
        row.setTrafficType(request.getTrafficType());
        row.setMaxAmount(request.getMaxAmount());
        row.setEffectiveAt(effectiveAt);
        row.setUpdatedBy(UserContext.currentId());
        tripStandardMapper.insert(row);

        logRecorder.record(ConfigLog.TYPE_STANDARD, keyOf(row.getCityLevel(), row.getTrafficType()), ConfigLog.ACTION_UPDATE,
                before, row.getMaxAmount(), request.getReason());

        return toStandardVO(row, before);
    }

    /**
     * 删除某一版差标。
     *
     * <p>只允许删该组生效日期最晚的一条（也就是正在生效的那一版），
     * 中间的历史版本不能抽——抽掉了那一版的追溯链就断了，历史申请按哪一档算的说不清楚。
     * 删之前的历史申请不受影响：同一单的差标结论在提交时就写进了 trip_apply.standard_result。
     *
     * <p>删除也是一次调整，照样往留痕表里 append 一条，afterValue 留空。
     */
    public void deleteStandard(Long id, String reason) {
        TripStandard row = tripStandardMapper.selectById(id);
        if (row == null) {
            throw BusinessException.paramError("差标记录不存在，可能已经被删掉了");
        }
        TripStandard latest = tripStandardMapper.selectOne(new LambdaQueryWrapper<TripStandard>()
                .eq(TripStandard::getCityLevel, row.getCityLevel())
                .eq(TripStandard::getTrafficType, row.getTrafficType())
                .orderByDesc(TripStandard::getEffectiveAt)
                .last("limit 1"));
        if (latest != null && !latest.getId().equals(row.getId())) {
            throw BusinessException.paramError("只能删最新一档（" + latest.getCityLevel() + "-" + latest.getTrafficType()
                    + "，生效日期 " + latest.getEffectiveAt() + "），历史版本要保留以便追溯");
        }
        tripStandardMapper.deleteById(id);

        BigDecimal after = currentMax(row.getCityLevel(), row.getTrafficType());
        logRecorder.record(ConfigLog.TYPE_STANDARD, keyOf(row.getCityLevel(), row.getTrafficType()), ConfigLog.ACTION_DELETE,
                row.getMaxAmount(), after, reason);
    }

    // ---------- 部门预算：查 / 增 / 改 / 删 ----------

    /**
     * 部门清单，替掉前端写死的那份常量。
     *
     * <p>顺带把「这个部门下有几个人、几张申请单」带上，删除按钮要靠它判断能不能点。
     */
    public List<DeptVO> listDepts() {
        List<Dept> depts = deptMapper.selectList(new LambdaQueryWrapper<Dept>().orderByAsc(Dept::getId));
        List<DeptVO> result = new ArrayList<>();
        for (Dept dept : depts) {
            result.add(toDeptVO(dept));
        }
        return result;
    }

    /**
     * 新增部门（连同它的初始月度预算）。
     *
     * <p>审批人不传时找一个 APPROVER 顶上——新建的部门没人批的话，
     * 员工提的单子会直接卡在数据流里，比选错审批人麻烦多了。
     */
    public DeptVO createDept(DeptCreateRequest request) {
        Long dup = deptMapper.selectCount(new LambdaQueryWrapper<Dept>()
                .eq(Dept::getDeptName, request.getDeptName()));
        if (dup != null && dup > 0) {
            throw BusinessException.paramError("已经有一个叫「" + request.getDeptName() + "」的部门了");
        }
        Long leaderId = request.getLeaderId();
        if (leaderId == null) {
            SysUser approver = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getRole, SysUser.ROLE_APPROVER)
                    .last("limit 1"));
            leaderId = approver == null ? UserContext.currentId() : approver.getId();
        } else if (sysUserMapper.selectById(leaderId) == null) {
            throw BusinessException.paramError("指定的审批人不存在");
        }

        Dept dept = new Dept();
        dept.setDeptName(request.getDeptName());
        dept.setMonthlyBudget(request.getMonthlyBudget());
        dept.setStandardLevel(request.getStandardLevel());
        dept.setLeaderId(leaderId);
        deptMapper.insert(dept);

        logRecorder.record(ConfigLog.TYPE_BUDGET, "dept:" + dept.getId(), ConfigLog.ACTION_INSERT,
                null, dept.getMonthlyBudget(), request.getReason());
        return toDeptVO(dept);
    }

    /**
     * H-03：调整部门月度预算。调整本身照旧改 dept 行，前后值与原因落到 config_log。
     */
    public DeptVO updateBudget(BudgetUpdateRequest request) {
        Dept dept = deptMapper.selectById(request.getDeptId());
        if (dept == null) {
            throw BusinessException.paramError("部门不存在");
        }
        BigDecimal before = dept.getMonthlyBudget();
        dept.setMonthlyBudget(request.getMonthlyBudget());
        deptMapper.updateById(dept);

        logRecorder.record(ConfigLog.TYPE_BUDGET, "dept:" + dept.getId(), ConfigLog.ACTION_UPDATE,
                before, request.getMonthlyBudget(), request.getReason());
        // 统一返回 DeptVO：前端拿到的字段名跟 GET /config/depts 一模一样，
        // 少一套 id / deptId 的口径，页面不用判断这次回来的是哪种
        return toDeptVO(dept);
    }

    /**
     * 删除部门。
     *
     * <p>有员工或者名下有申请单的部门不能删：dept 表没有物理外键，
     * 真删了会留下 sys_user.dept_id / trip_apply.dept_id 指向空部门的脏数据，
     * 月报和归因钻取 join 过来直接少一行，还查不出原因。
     */
    public void deleteDept(Long id, String reason) {
        Dept dept = deptMapper.selectById(id);
        if (dept == null) {
            throw BusinessException.paramError("部门不存在");
        }
        Long members = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getDeptId, id));
        if (members != null && members > 0) {
            throw BusinessException.paramError("「" + dept.getDeptName() + "」下还有 " + members
                    + " 名员工，先把人调到其他部门再删");
        }
        Long applies = tripApplyMapper.selectCount(new LambdaQueryWrapper<TripApply>().eq(TripApply::getDeptId, id));
        if (applies != null && applies > 0) {
            throw BusinessException.paramError("「" + dept.getDeptName() + "」名下有 " + applies
                    + " 张出差申请单，历史单据不能连根拔，这个部门不能删");
        }
        deptMapper.deleteById(id);

        logRecorder.record(ConfigLog.TYPE_BUDGET, "dept:" + id, ConfigLog.ACTION_DELETE,
                dept.getMonthlyBudget(), null, reason);
    }

    // ---------- H-04 调整留痕 ----------

    /**
     * H-04：调整留痕，按时间倒序分页。
     *
     * <p>直接查 config_log：差标与预算的增删改都往这张表 append，
     * 不用再从 trip_standard 版本链里推导（那条路子预算留痕根本上不来，
     * 而且得把整张差标表读进内存分页）。
     */
    public PageResult<ConfigLogVO> logs(ConfigLogQuery query) {
        LambdaQueryWrapper<ConfigLog> wrapper = new LambdaQueryWrapper<ConfigLog>()
                .eq(hasText(query.getType()), ConfigLog::getItemType, query.getType())
                .and(hasText(query.getKeyword()), w -> w
                        .like(ConfigLog::getItemKey, query.getKeyword())
                        .or()
                        .like(ConfigLog::getReason, query.getKeyword()))
                .orderByDesc(ConfigLog::getOperateAt)
                .orderByDesc(ConfigLog::getId);

        IPage<ConfigLog> page = configLogMapper.selectPage(
                new Page<>(query.safePageNum(), query.safePageSize()), wrapper);

        // 留痕一页 10 条，操作人一个一个查太浪费，先把这一页用到的人一次查出来
        Map<Long, String> userNames = new HashMap<>();
        for (ConfigLog log : page.getRecords()) {
            if (log.getOperatorId() != null) {
                userNames.put(log.getOperatorId(), "未知用户");
            }
        }
        if (!userNames.isEmpty()) {
            sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>().in(SysUser::getId, userNames.keySet()))
                    .forEach(user -> userNames.put(user.getId(), user.getRealName()));
        }

        return PageResult.of(page, log -> toLogVO(log, userNames));
    }

    // ---------- 内部 ----------

    private ConfigLogVO toLogVO(ConfigLog log, Map<Long, String> userNames) {
        ConfigLogVO vo = new ConfigLogVO();
        vo.setId(log.getId());
        vo.setType(log.getItemType());
        vo.setAction(log.getAction());
        vo.setTargetId(log.getItemKey());
        vo.setTargetName(readableTarget(log.getItemKey()));
        vo.setBefore(log.getBeforeValue());
        vo.setAfter(log.getAfterValue());
        vo.setReason(log.getReason());
        vo.setOperator(userNames.get(log.getOperatorId()));
        vo.setOperateAt(log.getOperateAt() == null ? null : log.getOperateAt().format(DATE_TIME));
        return vo;
    }

    /**
     * 把留痕里的 itemKey 翻成人话：「dept:3」→「销售部」，「二级-高铁」本来就能看。
     */
    private String readableTarget(String itemKey) {
        if (itemKey == null || !itemKey.startsWith("dept:")) {
            return itemKey;
        }
        String id = itemKey.substring("dept:".length());
        Dept dept;
        try {
            dept = deptMapper.selectById(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return itemKey;
        }
        return dept == null ? itemKey : dept.getDeptName();
    }

    private StandardVO toStandardVO(TripStandard row, BigDecimal prev) {
        StandardVO vo = new StandardVO();
        vo.setId(row.getId());
        vo.setCityLevel(row.getCityLevel());
        vo.setTrafficType(row.getTrafficType());
        vo.setMaxAmount(row.getMaxAmount());
        vo.setEffectiveAt(row.getEffectiveAt());
        vo.setUpdatedBy(name(row.getUpdatedBy()));
        vo.setPrevMaxAmount(prev);
        return vo;
    }

    private DeptVO toDeptVO(Dept dept) {
        DeptVO vo = new DeptVO();
        vo.setDeptId(dept.getId());
        vo.setDeptName(dept.getDeptName());
        vo.setMonthlyBudget(dept.getMonthlyBudget());
        vo.setStandardLevel(dept.getStandardLevel());
        vo.setLeaderId(dept.getLeaderId());
        vo.setApproverName(name(dept.getLeaderId()));
        vo.setMemberCount(sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeptId, dept.getId())));
        vo.setApplyCount(tripApplyMapper.selectCount(new LambdaQueryWrapper<TripApply>()
                .eq(TripApply::getDeptId, dept.getId())));
        return vo;
    }

    private static String keyOf(String cityLevel, String trafficType) {
        return cityLevel + "-" + trafficType;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String name(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : user.getRealName();
    }

    private BigDecimal prevMax(List<TripStandard> all, TripStandard current) {
        BigDecimal prev = null;
        for (TripStandard item : all) {
            if (!item.getCityLevel().equals(current.getCityLevel())
                    || !item.getTrafficType().equals(current.getTrafficType())
                    || item.getId().equals(current.getId())) {
                continue;
            }
            if (item.getEffectiveAt() != null && current.getEffectiveAt() != null
                    && !item.getEffectiveAt().isAfter(current.getEffectiveAt())) {
                prev = item.getMaxAmount();
            }
        }
        return prev;
    }

    /**
     * 目的地城市等级的内置兜底映射。
     *
     * <p>TODO-需要你补基础数据：正式做法应建 city_level 字典表。
     * 这份映射只覆盖演示会用到的城市，遇到表里没有的城市统一按三级处理。
     */
    public String cityLevelOf(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String name = city.trim();
        if (Map.of("北京", 1, "上海", 1, "广州", 1, "深圳", 1).containsKey(name)) {
            return "一级";
        }
        if (List.of("杭州", "成都", "武汉", "南京", "西安", "重庆", "苏州", "天津",
                "长沙", "郑州", "青岛", "合肥", "厦门", "宁波", "无锡", "东莞", "佛山").contains(name)) {
            return "二级";
        }
        return "三级";
    }

    /**
     * 差标结论的中文说明，给前端直接展示。
     */
    public static String describe(String standardResult) {
        if (TripApply.STANDARD_EXCEED.equals(standardResult)) {
            return "超出差标 10% 以上，需填写超标说明";
        }
        if (TripApply.STANDARD_HIGH.equals(standardResult)) {
            return "超出差标但在 10% 以内";
        }
        return "未超出差标";
    }
}
