package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.dto.DeptInfoUpdateRequest;
import com.ts.travelserver.dto.PartnerSaveRequest;
import com.ts.travelserver.dto.UserSaveRequest;
import com.ts.travelserver.entity.ApprovalRecord;
import com.ts.travelserver.entity.BizResult;
import com.ts.travelserver.entity.ConfigLog;
import com.ts.travelserver.entity.Dept;
import com.ts.travelserver.entity.LoanRecord;
import com.ts.travelserver.entity.Partner;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.mapper.ApprovalRecordMapper;
import com.ts.travelserver.mapper.BizResultMapper;
import com.ts.travelserver.mapper.DeptMapper;
import com.ts.travelserver.mapper.LoanRecordMapper;
import com.ts.travelserver.mapper.PartnerMapper;
import com.ts.travelserver.mapper.SysUserMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.service.support.ConfigLogRecorder;
import com.ts.travelserver.vo.AdminUserVO;
import com.ts.travelserver.vo.DeptVO;
import com.ts.travelserver.vo.PartnerVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 基础档案维护（系统管理员专用）：往来对象、用户账号、部门的名称与负责人。
 *
 * <p>划出去的依据是「这个字段参不参与运行时计算」：
 * <ul>
 *   <li>归这里：对象名称与类型、用户的姓名角色部门、部门的名称与负责人——只被引用，不参与计算。</li>
 *   <li>归管理层：差标金额与部门月度预算——{@code dept.monthly_budget} 直接决定 BR-04 的预借额度上限，
 *       差标决定是否判超标、要不要强制写超标说明。这属于财权，动手的人得对费效分析的结论负责。</li>
 * </ul>
 *
 * <p>两个怎么着都不能碰的地方，即使这里写了增改接口：
 * <ul>
 *   <li>{@code partner.total_invest / total_output}：付款与结果回填时增量累加的冗余汇总，
 *       对象投入产出页直接读它。开了这个口子等于开了改报表且不会报错的口子。</li>
 *   <li>三张日志表与派生表：只能看不能写（看的部分在 BrowseService）。</li>
 * </ul>
 *
 * <p>每一次增删改都往 config_log 追加一条，带上前后值、操作人和填写的原因。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 密码最短长度。演示账号是 123456，别低于这个数 */
    private static final int MIN_PASSWORD = 6;

    /** 合法角色清单，新增角色时这里必须同步，否则新角色会被拒在外面 */
    private static final Set<String> ROLES = Set.of(
            SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER,
            SysUser.ROLE_FINANCE, SysUser.ROLE_MANAGER, SysUser.ROLE_SYS_ADMIN);

    private final PartnerMapper partnerMapper;
    private final SysUserMapper sysUserMapper;
    private final DeptMapper deptMapper;
    private final TripApplyMapper tripApplyMapper;
    private final BizResultMapper bizResultMapper;
    private final LoanRecordMapper loanRecordMapper;
    private final ApprovalRecordMapper approvalRecordMapper;
    private final ConfigLogRecorder logRecorder;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // ---------- 往来对象 ----------

    /**
     * 往来对象列表，按名称关键字过滤。
     *
     * <p>applyCount 是这一行的引用计数，唯一的用处是判断能不能删。
     */
    public PageResult<PartnerVO> partners(String keyword, long pageNum, long pageSize) {
        LambdaQueryWrapper<Partner> wrapper = new LambdaQueryWrapper<Partner>()
                .like(hasText(keyword), Partner::getPartnerName, keyword)
                .orderByDesc(Partner::getId);
        IPage<Partner> page = partnerMapper.selectPage(new Page<>(safeNum(pageNum), safeSize(pageSize)), wrapper);
        return PageResult.of(page, this::toPartnerVO);
    }

    /**
     * 新增往来对象。累计投入与产出一律从 0 起，不由这里指定。
     */
    @Transactional
    public PartnerVO createPartner(PartnerSaveRequest request) {
        String name = request.getPartnerName().trim();
        checkNameFree(name, null);
        Partner partner = new Partner();
        partner.setPartnerName(name);
        partner.setPartnerType(type(request.getPartnerType()));
        partnerMapper.insert(partner);

        logRecorder.record(ConfigLog.TYPE_PARTNER, "partner:" + name, ConfigLog.ACTION_INSERT,
                null, describe(name, partner.getPartnerType()), request.getReason());
        return toPartnerVO(partner);
    }

    /**
     * 修改往来对象。名称与类型都可以改，累计的两个金额不动。
     */
    @Transactional
    public PartnerVO updatePartner(Long id, PartnerSaveRequest request) {
        Partner partner = loadPartner(id);
        String name = request.getPartnerName().trim();
        checkNameFree(name, id);
        String beforeName = partner.getPartnerName();
        String beforeType = partner.getPartnerType();

        partner.setPartnerName(name);
        partner.setPartnerType(type(request.getPartnerType()));
        partnerMapper.updateById(partner);

        logRecorder.record(ConfigLog.TYPE_PARTNER, "partner:" + name, ConfigLog.ACTION_UPDATE,
                describe(beforeName, beforeType), describe(name, partner.getPartnerType()), request.getReason());
        return toPartnerVO(partner);
    }

    /**
     * 删除往来对象。被出差申请单或业务结果引用过就不给删。
     *
     * <p>技术上数据库没有物理外键，删得掉；删完那些申请单会挂着一个不存在的对象，
     * 月报 join 过来直接少一行，而且查不出原因。宁可拦在前面。
     */
    @Transactional
    public void deletePartner(Long id, String reason) {
        Partner partner = loadPartner(id);
        long applies = applyCount(id);
        if (applies > 0) {
            throw BusinessException.paramError("「" + partner.getPartnerName() + "」已被 " + applies
                    + " 张单据引用，删了这些单据的对象就找不回来了，不能删");
        }
        partnerMapper.deleteById(id);

        logRecorder.record(ConfigLog.TYPE_PARTNER, "partner:" + partner.getPartnerName(), ConfigLog.ACTION_DELETE,
                describe(partner.getPartnerName(), partner.getPartnerType()), null, reason);
    }

    // ---------- 用户账号 ----------

    /**
     * 用户列表，可按角色、部门、姓名或登录名过滤。
     *
     * <p>self 标记当前登录的是哪一行：页面上不给删自己，也不给改自己的角色——
     * 手一滑把自己从管理员位置挪走，那就没人能把它挪回来了。
     */
    public PageResult<AdminUserVO> users(String role, Long deptId, String keyword, long pageNum, long pageSize) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(hasText(role), SysUser::getRole, role)
                .eq(deptId != null, SysUser::getDeptId, deptId)
                .and(hasText(keyword), w -> w.like(SysUser::getRealName, keyword)
                        .or()
                        .like(SysUser::getUsername, keyword))
                .orderByAsc(SysUser::getId);
        IPage<SysUser> page = sysUserMapper.selectPage(new Page<>(safeNum(pageNum), safeSize(pageSize)), wrapper);
        Long me = currentIdOrNull();
        return PageResult.of(page, user -> toUserVO(user, me));
    }

    /**
     * 新增用户。密码用 BCrypt 存密文，库里不落明文（设计报告 5.8）。
     */
    @Transactional
    public AdminUserVO createUser(UserSaveRequest request) {
        String username = request.getUsername().trim();
        Long dup = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (dup != null && dup > 0) {
            throw BusinessException.paramError("登录名「" + username + "」已经被占用了，换一个");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(request.getRealName().trim());
        user.setRole(role(request.getRole()));
        user.setDeptId(loadDept(request.getDeptId()).getId());
        user.setBankAccount(safe(request.getBankAccount()));
        user.setPassword(passwordEncoder.encode(password(request.getPassword(), true)));
        user.setCreatedAt(LocalDateTime.now());
        sysUserMapper.insert(user);

        logRecorder.record(ConfigLog.TYPE_USER, "user:" + username, ConfigLog.ACTION_INSERT,
                null, user.getRealName() + "（" + user.getRole() + "）", request.getReason());
        return toUserVO(user, null);
    }

    /**
     * 修改用户。密码留空表示不动原来的；自己的角色不接受在这个页面上改。
     */
    @Transactional
    public AdminUserVO updateUser(Long id, UserSaveRequest request) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw BusinessException.paramError("用户不存在，可能已经被删掉了");
        }
        String username = request.getUsername().trim();
        Long dup = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .ne(SysUser::getId, id));
        if (dup != null && dup > 0) {
            throw BusinessException.paramError("登录名「" + username + "」已经被占用了");
        }
        String newRole = role(request.getRole());
        if (!newRole.equals(user.getRole()) && id.equals(currentIdOrNull())) {
            throw BusinessException.paramError("不能在这里改自己的角色，改完就没人能进这个页面了");
        }

        String before = user.getRealName() + "（" + user.getRole() + "）" + deptLabel(user.getDeptId());
        user.setUsername(username);
        user.setRealName(request.getRealName().trim());
        user.setRole(newRole);
        user.setDeptId(loadDept(request.getDeptId()).getId());
        user.setBankAccount(safe(request.getBankAccount()));
        if (hasText(request.getPassword())) {
            user.setPassword(passwordEncoder.encode(password(request.getPassword(), false)));
        }
        sysUserMapper.updateById(user);

        logRecorder.record(ConfigLog.TYPE_USER, "user:" + username, ConfigLog.ACTION_UPDATE,
                before, user.getRealName() + "（" + user.getRole() + "）" + deptLabel(user.getDeptId()),
                request.getReason());
        return toUserVO(user, currentIdOrNull());
    }

    /**
     * 删除用户。
     *
     * <p>三道拦截：不能删自己；有单据留痕的不删（删了那些单据就成了黑户）；
     * 还挂着部门负责人的先换人。sys_user 带 &#64;TableLogic，删是逻辑删，行还在。
     */
    @Transactional
    public void deleteUser(Long id, String reason) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw BusinessException.paramError("用户不存在，可能已经被删掉了");
        }
        if (id.equals(currentIdOrNull())) {
            throw BusinessException.paramError("不能删掉自己");
        }
        long refs = refCount(id);
        if (refs > 0) {
            throw BusinessException.paramError("「" + user.getRealName() + "」名下有 " + refs
                    + " 条单据或审批留痕，改部门或改姓名都行，删除不行");
        }
        Long deptCount = deptMapper.selectCount(new LambdaQueryWrapper<Dept>().eq(Dept::getLeaderId, id));
        if (deptCount != null && deptCount > 0) {
            throw BusinessException.paramError("「" + user.getRealName()
                    + "」还是某些部门的负责人，先在部门档案里换人再删");
        }
        sysUserMapper.deleteById(id);

        logRecorder.record(ConfigLog.TYPE_USER, "user:" + user.getUsername(), ConfigLog.ACTION_DELETE,
                user.getRealName() + "（" + user.getRole() + "）", null, reason);
    }

    // ---------- 部门档案 ----------

    /**
     * 改部门名称与负责人。
     *
     * <p>月度预算与差标等级不在这里改，那两项在 /config 下由管理层调，
     * 因为它们直接参与预借额度与超标判定。
     */
    @Transactional
    public DeptVO updateDeptInfo(Long id, DeptInfoUpdateRequest request) {
        Dept dept = deptMapper.selectById(id);
        if (dept == null) {
            throw BusinessException.paramError("部门不存在");
        }
        String name = request.getDeptName().trim();
        Long dup = deptMapper.selectCount(new LambdaQueryWrapper<Dept>()
                .eq(Dept::getDeptName, name)
                .ne(Dept::getId, id));
        if (dup != null && dup > 0) {
            throw BusinessException.paramError("已经有一个叫「" + name + "」的部门了");
        }
        SysUser leader = sysUserMapper.selectById(request.getLeaderId());
        if (leader == null) {
            throw BusinessException.paramError("指定的负责人不存在");
        }
        String before = dept.getDeptName() + " / " + deptLabel(dept.getLeaderId());
        dept.setDeptName(name);
        dept.setLeaderId(leader.getId());
        deptMapper.updateById(dept);

        logRecorder.record(ConfigLog.TYPE_DEPT_INFO, "dept:" + id, ConfigLog.ACTION_UPDATE,
                before, dept.getDeptName() + " / " + leader.getRealName(), request.getReason());

        // 与 GET /config/depts 返回同一套字段：前端改完直接拿它替掉列表里那一行，
        // 不用再回头查一次，也省得这里漏掉 memberCount 让「能不能删」的判断忽然失效
        DeptVO vo = new DeptVO();
        vo.setDeptId(dept.getId());
        vo.setDeptName(dept.getDeptName());
        vo.setMonthlyBudget(dept.getMonthlyBudget());
        vo.setStandardLevel(dept.getStandardLevel());
        vo.setLeaderId(dept.getLeaderId());
        vo.setApproverName(leader.getRealName());
        vo.setMemberCount(sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeptId, dept.getId())));
        vo.setApplyCount(tripApplyMapper.selectCount(new LambdaQueryWrapper<TripApply>()
                .eq(TripApply::getDeptId, dept.getId())));
        return vo;
    }

    // ---------- 内部 ----------

    private Partner loadPartner(Long id) {
        Partner partner = partnerMapper.selectById(id);
        if (partner == null) {
            throw BusinessException.paramError("往来对象不存在，可能已经被删掉了");
        }
        return partner;
    }

    private void checkNameFree(String name, Long excludeId) {
        Long dup = partnerMapper.selectCount(new LambdaQueryWrapper<Partner>()
                .eq(Partner::getPartnerName, name)
                .ne(excludeId != null, Partner::getId, excludeId));
        if (dup != null && dup > 0) {
            throw BusinessException.paramError("已经有一个叫「" + name + "」的往来对象了");
        }
    }

    /**
     * 一个对象被多少处引用。trip_apply.partner_id 与 biz_result.partner_id
     * 都指向它——业务结果里也冗余了一份 partner_id，所以两处都要数。
     */
    private long applyCount(Long partnerId) {
        long applies = zero(tripApplyMapper.selectCount(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getPartnerId, partnerId)));
        long results = zero(bizResultMapper.selectCount(
                new LambdaQueryWrapper<BizResult>().eq(BizResult::getPartnerId, partnerId)));
        return applies + results;
    }

    /** 一个用户被多少处引用：申请单、预借单、审批留痕 */
    private long refCount(Long userId) {
        return zero(tripApplyMapper.selectCount(new LambdaQueryWrapper<TripApply>().eq(TripApply::getUserId, userId)))
                + zero(loanRecordMapper.selectCount(new LambdaQueryWrapper<LoanRecord>().eq(LoanRecord::getUserId, userId)))
                + zero(approvalRecordMapper.selectCount(
                        new LambdaQueryWrapper<ApprovalRecord>().eq(ApprovalRecord::getApproverId, userId)));
    }

    private String describe(String name, String partnerType) {
        return name + "（" + ("CUSTOMER".equals(partnerType) ? "客户" : "供应商") + "）";
    }

    /** 类型只有两个取值，不是就明确报错，别让脏值进库 */
    private String type(String partnerType) {
        String value = partnerType == null ? "" : partnerType.trim();
        if (!Partner.TYPE_CUSTOMER.equals(value) && !Partner.TYPE_SUPPLIER.equals(value)) {
            throw BusinessException.paramError("对象类型只能是客户或供应商");
        }
        return value;
    }

    private String role(String role) {
        String value = role == null ? "" : role.trim();
        if (!ROLES.contains(value)) {
            throw BusinessException.paramError("角色取值不认识：" + value);
        }
        return value;
    }

    private Dept loadDept(Long deptId) {
        if (deptId == null) {
            throw BusinessException.paramError("请选择部门");
        }
        Dept dept = deptMapper.selectById(deptId);
        if (dept == null) {
            throw BusinessException.paramError("部门不存在");
        }
        return dept;
    }

    /**
     * 密码校验。新增必填，修改时留空跳过；长度下面是底线，
     * 再往下就谈不上什么密文存储了。
     */
    private String password(String raw, boolean required) {
        if (!hasText(raw)) {
            if (required) {
                throw BusinessException.paramError("新增账号必须设置初始密码");
            }
            return null;
        }
        if (raw.trim().length() < MIN_PASSWORD) {
            throw BusinessException.paramError("密码至少 " + MIN_PASSWORD + " 位");
        }
        return raw.trim();
    }

    private PartnerVO toPartnerVO(Partner partner) {
        PartnerVO vo = new PartnerVO();
        vo.setId(partner.getId());
        vo.setPartnerName(partner.getPartnerName());
        vo.setPartnerType(partner.getPartnerType());
        vo.setTotalInvest(partner.getTotalInvest());
        vo.setTotalOutput(partner.getTotalOutput());
        vo.setApplyCount(applyCount(partner.getId()));
        return vo;
    }

    private AdminUserVO toUserVO(SysUser user, Long me) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        vo.setDeptId(user.getDeptId());
        vo.setDeptName(deptName(user.getDeptId()));
        vo.setBankAccountMasked(mask(user.getBankAccount()));
        vo.setRefCount(refCount(user.getId()));
        vo.setSelf(me != null && me.equals(user.getId()));
        vo.setCreatedAt(user.getCreatedAt() == null ? null : user.getCreatedAt().format(DATE_TIME));
        return vo;
    }

    /** 银行账号脱敏，口径与财务侧一致（前 3 后 4） */
    private String mask(String bankAccount) {
        if (bankAccount == null || bankAccount.isBlank()) {
            return null;
        }
        int len = bankAccount.length();
        if (len <= 7) {
            return "***";
        }
        return bankAccount.substring(0, 3) + "****" + bankAccount.substring(len - 4);
    }

    private String deptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        Dept dept = deptMapper.selectById(deptId);
        return dept == null ? null : dept.getDeptName();
    }

    /** 留痕里写人话：部门查不到时不写 null，写「未分配部门」 */
    private String deptLabel(Long deptId) {
        String name = deptName(deptId);
        return name == null ? "未分配部门" : name;
    }

    /**
     * 当前登录者。浏览接口有可能在没登录时被调到，这里不去抛未登录，
     * 返回 null 由调用方决定怎么处理。
     */
    private Long currentIdOrNull() {
        try {
            return UserContext.currentId();
        } catch (BusinessException e) {
            return null;
        }
    }

    private static long zero(Long value) {
        return value == null ? 0L : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? null : value.trim().isEmpty() ? null : value.trim();
    }

    private static long safeNum(long pageNum) {
        return Math.max(1, pageNum);
    }

    private static long safeSize(long pageSize) {
        return Math.min(Math.max(1, pageSize), 100L);
    }
}
