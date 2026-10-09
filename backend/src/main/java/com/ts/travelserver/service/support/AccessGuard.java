package com.ts.travelserver.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.entity.ApprovalRecord;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.mapper.ApprovalRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 越权校验的公共判据。
 *
 * <p>列表接口天然是按人过滤的（我的申请 / 待我审批），但<b>按单号直接查详情的接口不是</b>——
 * 换个 applyNo 就能看到别人的单，列表那层过滤等于白做。所以每个按单号取数的 Service
 * 都要单独判一次「这个号跟当前登录人有没有关系」，判据统一放这里，避免三处各写一套走样。
 *
 * <h3>两条判据</h3>
 * <ul>
 *   <li>{@link #approvedByMe}：审批轨迹里出现过他。比按部门猜准得多——
 *       复审、转交都可能跨部门，而只要他审过这一单，approval_record 里必定有他。</li>
 *   <li>{@link #broadVisible}：财务、管理层、系统管理员看全部。财务要复核并付款所有人的报销单，
 *       管理层本来就是看全公司的，系统管理员是运维视角。</li>
 * </ul>
 *
 * <p>为什么两张单（申请 / 报销）都认「审过这一单」：复审详情里要摆票据与报销明细，
 * 把复审人挡在门外的话，页面上不报错、只是「恰好没有数据」——这种静默的空比报错更难查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessGuard {

    private final ApprovalRecordMapper approvalRecordMapper;

    /**
     * 这一单的审批轨迹里有没有他（审过、正在审都算）。
     *
     * <p>查不动时返回 false 而不是放行：宁可多挡一次，也不能因为查询异常把门敞开。
     */
    public boolean approvedByMe(String applyNo, Long userId) {
        if (applyNo == null || userId == null) {
            return false;
        }
        try {
            Long cnt = approvalRecordMapper.selectCount(new LambdaQueryWrapper<ApprovalRecord>()
                    .eq(ApprovalRecord::getApplyNo, applyNo)
                    .eq(ApprovalRecord::getApproverId, userId));
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            log.warn("审批轨迹查询失败：{}", e.getMessage());
            return false;
        }
    }

    /** 财务、管理层、系统管理员：看全部 */
    public boolean broadVisible(String role) {
        return SysUser.ROLE_FINANCE.equals(role)
                || SysUser.ROLE_MANAGER.equals(role)
                || SysUser.ROLE_SYS_ADMIN.equals(role);
    }
}
