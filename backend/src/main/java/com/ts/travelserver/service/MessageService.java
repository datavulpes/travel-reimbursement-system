package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.dto.ApplyQuery;
import com.ts.travelserver.entity.SysMessage;
import com.ts.travelserver.mapper.SysMessageMapper;
import com.ts.travelserver.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内消息（C 档新增）。
 *
 * <p>只做一件事：把已经发生的事告诉该知道的人。它不改变任何单据状态，
 * 也不参与任何业务判断——业务接口一律不读这张表。
 *
 * <h3>发送失败绝不回滚主业务</h3>
 * 审批刚下完结论、付款刚打完款，这时候因为一条通知没写进去把整个事务回滚掉，
 * 是典型的「通知骑在业务头上」。所以 {@link #send} 自己吞掉异常只记 WARN，
 * 调用方也不该去判断它的返回值。
 *
 * <h3>不做邮件 / 短信</h3>
 * 这个项目没有外网通道。做一条发不出去的通道，比不做更糟——
 * 页面上写着「已通知对方」，而对方其实什么都没收到。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final SysMessageMapper sysMessageMapper;

    // ---------- 发送 ----------

    /**
     * 给一个人发消息。收件人为空或异常一律静默跳过。
     */
    public void send(Long userId, String msgType, String bizType, String bizNo, String title, String content) {
        if (userId == null) {
            return;
        }
        sendTo(List.of(userId), msgType, bizType, bizNo, title, content);
    }

    /**
     * 给一批人发同一条消息（复审环节的管理层不止一个）。
     *
     * <p>逐条插入不加事务：这批消息之间没有一致性要求，
     * 少发一个人也只是那个人晚点知道，不值得为它开事务。
     */
    public void sendTo(List<Long> userIds, String msgType, String bizType, String bizNo,
                       String title, String content) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            try {
                SysMessage row = new SysMessage();
                row.setUserId(userId);
                row.setMsgType(msgType);
                row.setBizType(bizType);
                row.setBizNo(bizNo);
                row.setTitle(cut(title, 100));
                row.setContent(cut(content, 500));
                row.setReadFlag(0);
                sysMessageMapper.insert(row);
            } catch (Exception e) {
                log.warn("站内消息发送失败，收件人 {} 类型 {}：{}", userId, msgType, e.getMessage());
            }
        }
    }

    // ---------- 读取 ----------

    /**
     * 我的消息，未读在前、新的在前。
     */
    public PageResult<MessageVO> page(ApplyQuery query) {
        Long userId = UserContext.currentId();
        Page<SysMessage> page = new Page<>(query.safePageNum(), query.safePageSize());
        sysMessageMapper.selectPage(page, new LambdaQueryWrapper<SysMessage>()
                .eq(SysMessage::getUserId, userId)
                .orderByAsc(SysMessage::getReadFlag)
                .orderByDesc(SysMessage::getId));
        return PageResult.of(page, MessageVO::of);
    }

    /**
     * 我的未读条数。顶栏铃铛用。
     */
    public long unreadCount() {
        try {
            return sysMessageMapper.countUnread(UserContext.currentId());
        } catch (Exception e) {
            log.warn("未读消息数查询失败，顶栏按 0 显示：{}", e.getMessage());
            return 0L;
        }
    }

    // ---------- 已读 ----------

    /**
     * 标一条已读。不是自己的消息就当没这条消息（返回 false），
     * 不报「无权限」——消息 id 是枚举不出来才有意义的，报出来反而给了探测口子。
     */
    public boolean read(Long id) {
        Long userId = UserContext.currentId();
        SysMessage row = sysMessageMapper.selectOne(new LambdaQueryWrapper<SysMessage>()
                .eq(SysMessage::getId, id)
                .eq(SysMessage::getUserId, userId));
        if (row == null) {
            return false;
        }
        if (row.getReadFlag() != null && row.getReadFlag() == 1) {
            return true;
        }
        row.setReadFlag(1);
        row.setReadAt(LocalDateTime.now());
        sysMessageMapper.updateById(row);
        return true;
    }

    /**
     * 全部标已读。只动自己的那批，WHERE 里带 user_id 是硬要求。
     */
    public int readAll() {
        Long userId = UserContext.currentId();
        List<SysMessage> unread = sysMessageMapper.selectList(new LambdaQueryWrapper<SysMessage>()
                .eq(SysMessage::getUserId, userId)
                .eq(SysMessage::getReadFlag, 0));
        if (unread.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (SysMessage row : unread) {
            row.setReadFlag(1);
            row.setReadAt(now);
            sysMessageMapper.updateById(row);
            n++;
        }
        return n;
    }

    // ---------- 内部 ----------

    /**
     * 内容截断：意见、说明这类字段可能很长，而消息正文只有 500 字。
     *
     * <p>截断而不是让它插库失败——少看几个字不影响理解，一条消息都发不出去才是问题。
     */
    private String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
