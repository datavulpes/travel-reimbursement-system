package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.AiPanelSaveRequest;
import com.ts.travelserver.service.AiPanelStateService;
import com.ts.travelserver.vo.AiPanelStateVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 助手侧边栏的暂存（设计文档接口清单里没有，补齐闭环用）。
 *
 * <p>四个挂点：填写申请、我的申请、票据上传、报销提交。
 * 共同点是「页面上已经有一堆要填的东西，临时想问一句规定」——
 * 问完关掉面板，下次回到同一页还能看到刚才那句问答。
 *
 * <p>{@code @RequireRole} 不写 value：登录用户都能用，
 * 真正的边界是「只能读写自己那份」，那一条在 Service 里靠登录态保证。
 *
 * <p>不进任何统计与留痕：它不是业务事实，只是面板的一屏暂存。
 */
@RestController
@RequestMapping("/ai/panel")
@RequireRole
@RequiredArgsConstructor
public class AiPanelStateController {

    private final AiPanelStateService aiPanelStateService;

    /** 保存。关闭面板时调一次，同键只留最后一条；content 留空按删除处理。 */
    @PostMapping("/save")
    public R<AiPanelStateVO> save(@Valid @RequestBody AiPanelSaveRequest request) {
        return R.ok(aiPanelStateService.save(request));
    }

    /** 读取本人在这一页的面板内容。没存过时 content 为 null，前端不做恢复。 */
    @GetMapping("/get")
    public R<AiPanelStateVO> get(@RequestParam String pageKey) {
        return R.ok(aiPanelStateService.get(pageKey));
    }

    /** 删除。重复删除不报错。 */
    @DeleteMapping
    public R<Void> delete(@RequestParam String pageKey) {
        aiPanelStateService.delete(pageKey);
        return R.<Void>ok("已清除", null);
    }
}
