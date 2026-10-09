package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.ApplyQuery;
import com.ts.travelserver.service.MessageService;
import com.ts.travelserver.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内消息中心。
 *
 * <p>四个角色都能用，所以类上挂无参 {@code @RequireRole}——
 * 它表示「登录即可」，具体的行级隔离靠 SQL 里的 user_id。
 *
 * <p>这里没有「发消息」的接口：消息只能由业务动作触发（催办、审批、复核、付款），
 * 开一个谁都能调的发消息口子，等于把站内信变成骚扰通道。
 */
@RestController
@RequestMapping("/message")
@RequireRole
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * 我的消息列表。未读在前，同一个已读状态里新的在前。
     */
    @GetMapping("/page")
    public R<PageResult<MessageVO>> page(ApplyQuery query) {
        return R.ok(messageService.page(query));
    }

    /**
     * 未读条数，顶栏铃铛上的红点。
     */
    @GetMapping("/unread")
    public R<Long> unread() {
        return R.ok(messageService.unreadCount());
    }

    /**
     * 点开一条消息就算已读。
     */
    @PostMapping("/{id}/read")
    public R<Boolean> read(@PathVariable Long id) {
        return R.ok(messageService.read(id));
    }

    /**
     * 一键全部已读。
     */
    @PostMapping("/read-all")
    public R<Integer> readAll() {
        int n = messageService.readAll();
        return R.ok(n == 0 ? "没有未读消息" : n + " 条消息已标为已读", n);
    }
}
