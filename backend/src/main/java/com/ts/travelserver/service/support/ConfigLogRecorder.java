package com.ts.travelserver.service.support;

import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.entity.ConfigLog;
import com.ts.travelserver.mapper.ConfigLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 往 config_log 追加一条变更记录。
 *
 * <p>差标、预算、往来对象、用户账号、部门档案的每一次增删改都经过这里，
 * 落的是「前值 → 后值 + 谁改的 + 为什么改」。
 *
 * <p>抽出来是因为会有两个人往里写：管理层改差标预算（StandardService）、
 * 系统管理员改基础档案（AdminService）。各写一份的话，第三个人加新操作时
 * 大概率照抄一份并抄漏 reason 或 operatorId，留痕就缺一块，
 * 而缺的那块恰恰是这张表存在的意义。
 *
 * <p>这张表应用层只追加，不修改、不删除，页面上也只读。
 */
@Component
@RequiredArgsConstructor
public class ConfigLogRecorder {

    private final ConfigLogMapper configLogMapper;

    /**
     * 数值型变更：差标金额、部门预算这类。
     */
    public void record(String itemType, String itemKey, String action,
                       BigDecimal before, BigDecimal after, String reason) {
        insert(itemType, itemKey, action,
                before == null ? null : before.toPlainString(),
                after == null ? null : after.toPlainString(),
                reason);
    }

    /**
     * 描述型变更：对象名称、用户姓名或角色这类没有数字可言的改动。
     *
     * <p>before / after 直接记文字差，例如「EMPLOYEE → MANAGER」。
     * 留痕页 applicant 看的是这句话，不是一串无处可查的数字。
     */
    public void record(String itemType, String itemKey, String action,
                       String before, String after, String reason) {
        insert(itemType, itemKey, action, before, after, reason);
    }

    private void insert(String itemType, String itemKey, String action,
                        String before, String after, String reason) {
        ConfigLog log = new ConfigLog();
        log.setItemType(itemType);
        log.setItemKey(itemKey);
        log.setAction(action);
        log.setBeforeValue(before);
        log.setAfterValue(after);
        log.setOperatorId(UserContext.currentId());
        log.setOperateAt(LocalDateTime.now());
        log.setReason(hasText(reason) ? reason.trim() : null);
        configLogMapper.insert(log);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
