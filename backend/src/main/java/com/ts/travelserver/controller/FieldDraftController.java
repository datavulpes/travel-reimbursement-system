package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.FieldDraftQuery;
import com.ts.travelserver.dto.FieldDraftSaveRequest;
import com.ts.travelserver.service.FieldDraftService;
import com.ts.travelserver.vo.FieldDraftVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 理由字段草稿（设计文档接口清单里没有，补齐闭环用）。
 *
 * <p>五个落点：审批意见、财务复核说明、付款备注、员工强制提交说明、超标说明。
 * 共同点是「一个要斟酌很久的文本框」，写一半丢了的代价很高，
 * 而它们所在的单据此时都处在某个确定的业务状态里，没有「存草稿」这一档可挂。
 *
 * <p>{@code @RequireRole} 不写 value：四个业务角色都可能用到
 * （员工写超标说明与强制说明、审批人写意见、财务写复核说明与付款备注），
 * 卡角色只会让名单跟着角色调整一起过期。真正的边界是「只能读写自己的草稿」，
 * 那一条在 Service 里靠登录态保证。
 *
 * <p>草稿不进任何统计与留痕：它不是业务事实，只是输入框的一份暂存。
 */
@RestController
@RequestMapping("/draft/field")
@RequireRole
@RequiredArgsConstructor
public class FieldDraftController {

    private final FieldDraftService fieldDraftService;

    /**
     * 保存。前端停手 800ms 调一次，同键只留最后一条。
     *
     * <p>content 留空按删除处理，见 {@code FieldDraftService.save}。
     */
    @PostMapping("/save")
    public R<FieldDraftVO> save(@Valid @RequestBody FieldDraftSaveRequest request) {
        return R.ok(fieldDraftService.save(request));
    }

    /**
     * 读取本人这条草稿。没存过时 content 为 null，前端不显示恢复入口。
     */
    @GetMapping("/get")
    public R<FieldDraftVO> get(FieldDraftQuery query) {
        return R.ok(fieldDraftService.get(query));
    }

    /**
     * 删除。提交成功后调一次；重复删除不报错。
     */
    @DeleteMapping
    public R<Void> delete(FieldDraftQuery query) {
        fieldDraftService.delete(query);
        return R.<Void>ok("草稿已清除", null);
    }
}
