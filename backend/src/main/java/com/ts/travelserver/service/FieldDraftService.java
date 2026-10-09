package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.dto.FieldDraftQuery;
import com.ts.travelserver.dto.FieldDraftSaveRequest;
import com.ts.travelserver.entity.FieldDraft;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.mapper.FieldDraftMapper;
import com.ts.travelserver.vo.FieldDraftVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 理由字段草稿：审批意见、复核说明这类文本框的暂存。
 *
 * <p>为什么不用 trip_apply 的 DRAFT 状态顺带存：草稿状态管的是「整张单据还没交」，
 * 而这里的场景是「单据流程已经走到某一步，只有一个理由框写了半截」。
 * 后者没有对应的业务状态可挂，硬塞进单据会把「写了一半的意见」变成必须清理的脏数据。
 *
 * <p>归属只认本人：驳回意见这种东西，A 审批人写的草稿不该出现在 B 的页面上，
 * 更不该被 B 恢复成自己的意见。userId 全程由登录态注入，接口不接受前端传。
 *
 * <p>另一个服务要在提交成功后清草稿时调 {@link #clear}，它不抛异常——
 * 清不掉草稿不能把「提交成功」变成「提交失败」，那才是本末倒置。
 */
@Service
@RequiredArgsConstructor
public class FieldDraftService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FieldDraftMapper fieldDraftMapper;

    /**
     * 保存。同一个人在同一张单的同一个字段上只留最后一条。
     *
     * <p>内容为空按删除处理：把框清空之后不该弹出「恢复上次保存的内容」。
     */
    public FieldDraftVO save(FieldDraftSaveRequest request) {
        assertBizType(request.getBizType());
        Long userId = UserContext.currentId();
        String bizNo = request.getBizNo().trim();
        String fieldName = request.getFieldName().trim();

        FieldDraft exist = find(request.getBizType(), bizNo, fieldName, userId);

        if (!StringUtils.hasText(request.getContent())) {
            if (exist != null) {
                fieldDraftMapper.deleteById(exist.getId());
            }
            return vo(request.getBizType(), bizNo, fieldName, null, null);
        }

        // withNano(0)：DATETIME 只到秒，带着纳秒落库再读回来会比前端算的时间多出一截小数
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (exist == null) {
            FieldDraft draft = new FieldDraft();
            draft.setBizType(request.getBizType());
            draft.setBizNo(bizNo);
            draft.setFieldName(fieldName);
            draft.setUserId(userId);
            draft.setContent(request.getContent().trim());
            draft.setUpdatedAt(now);
            fieldDraftMapper.insert(draft);
        } else {
            exist.setContent(request.getContent().trim());
            exist.setUpdatedAt(now);
            fieldDraftMapper.updateById(exist);
        }
        return vo(request.getBizType(), bizNo, fieldName, request.getContent().trim(), now);
    }

    /**
     * 读取本人在这张单这个字段上的草稿，没存过返回 content 为 null 的对象。
     */
    public FieldDraftVO get(FieldDraftQuery query) {
        assertBizType(query.getBizType());
        Long userId = currentIdOrNull();
        if (userId == null) {
            return vo(query.getBizType(), query.getBizNo(), query.getFieldName(), null, null);
        }
        FieldDraft draft = find(query.getBizType(), query.getBizNo(), query.getFieldName(), userId);
        if (draft == null) {
            return vo(query.getBizType(), query.getBizNo(), query.getFieldName(), null, null);
        }
        return vo(draft.getBizType(), draft.getBizNo(), draft.getFieldName(),
                draft.getContent(), draft.getUpdatedAt());
    }

    /**
     * 删除本人这条草稿。重复删除不报错——前端提交成功后无条件调一次。
     */
    public void delete(FieldDraftQuery query) {
        assertBizType(query.getBizType());
        Long userId = currentIdOrNull();
        if (userId == null) {
            return;
        }
        fieldDraftMapper.delete(new LambdaQueryWrapper<FieldDraft>()
                .eq(FieldDraft::getBizType, query.getBizType())
                .eq(FieldDraft::getBizNo, query.getBizNo())
                .eq(FieldDraft::getFieldName, query.getFieldName())
                .eq(FieldDraft::getUserId, userId));
    }

    /**
     * 供业务 Service 在「理由已经正式落库」之后调用：草稿的使命到此结束。
     *
     * <p>刻意不抛异常：提交、复核、付款这些动作已经成功了，
     * 清不掉一条草稿不该把整笔业务回滚掉。
     */
    public void clear(String bizType, String bizNo, String fieldName) {
        Long userId = currentIdOrNull();
        if (userId == null || !StringUtils.hasText(bizNo)) {
            return;
        }
        try {
            fieldDraftMapper.delete(new LambdaQueryWrapper<FieldDraft>()
                    .eq(FieldDraft::getBizType, bizType)
                    .eq(FieldDraft::getBizNo, bizNo)
                    .eq(FieldDraft::getFieldName, fieldName)
                    .eq(FieldDraft::getUserId, userId));
        } catch (Exception e) {
            // 草稿清不掉就算了，不牵连主流程
        }
    }

    // ---------- 内部 ----------

    private FieldDraft find(String bizType, String bizNo, String fieldName, Long userId) {
        return fieldDraftMapper.selectOne(new LambdaQueryWrapper<FieldDraft>()
                .eq(FieldDraft::getBizType, bizType)
                .eq(FieldDraft::getBizNo, bizNo)
                .eq(FieldDraft::getFieldName, fieldName)
                .eq(FieldDraft::getUserId, userId)
                .last("LIMIT 1"));
    }

    /**
     * 场景值必须是五个之一。这里是防串号的最后一道：
     * biz_type 写错了不会报错，只会让草稿存到一个谁也找不到的键上，
     * 表现为「明明保存了，下次进来却什么都没有」——最难查的那类问题。
     */
    private void assertBizType(String bizType) {
        if (!FieldDraft.BIZ_APPLY_REVIEW.equals(bizType)
                && !FieldDraft.BIZ_EXPENSE_REVIEW.equals(bizType)
                && !FieldDraft.BIZ_EXPENSE_PAY.equals(bizType)
                && !FieldDraft.BIZ_EXPENSE_SUBMIT.equals(bizType)
                && !FieldDraft.BIZ_APPLY_SUBMIT.equals(bizType)) {
            throw BusinessException.paramError("未知的草稿场景：" + bizType);
        }
    }

    private Long currentIdOrNull() {
        SysUser user = UserContext.get();
        return user == null ? null : user.getId();
    }

    private FieldDraftVO vo(String bizType, String bizNo, String fieldName,
                            String content, LocalDateTime updatedAt) {
        FieldDraftVO vo = new FieldDraftVO();
        vo.setBizType(bizType);
        vo.setBizNo(bizNo);
        vo.setFieldName(fieldName);
        vo.setContent(content);
        vo.setUpdatedAt(updatedAt == null ? null : updatedAt.format(DATE_TIME));
        return vo;
    }
}
