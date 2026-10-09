package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.dto.AiPanelSaveRequest;
import com.ts.travelserver.entity.AiPanelState;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.mapper.AiPanelStateMapper;
import com.ts.travelserver.vo.AiPanelStateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AI 助手面板状态：员工在四个页面上问过的话，退出助手后原样留着。
 *
 * <p>为什么存后端而不是 localStorage：需求是「下次登录还在」。
 * 浏览器本地存储换台机器、换个浏览器就没了，而这是业务内容不是偏好设置。
 *
 * <p>为什么按页面分开存：同一个人在这四个页面问的东西不一样——
 * 填单时问的是差标，报销时问的是票据要求。合成一份会互相覆盖，
 * 表现为「在 A 页问完，切到 B 页看到的是 A 的答案」。
 *
 * <p>归属只认本人：userId 全程由登录态注入，接口不接受前端传。
 */
@Service
@RequiredArgsConstructor
public class AiPanelStateService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiPanelStateMapper aiPanelStateMapper;

    /**
     * 保存。同一个人在同一个页面只留最后一条。
     *
     * <p>content 为空按删除处理：把面板清空之后不该再弹出「上次问到哪」。
     */
    public AiPanelStateVO save(AiPanelSaveRequest request) {
        String pageKey = assertPageKey(request.getPageKey());
        Long userId = UserContext.currentId();

        AiPanelState exist = find(pageKey, userId);

        if (!StringUtils.hasText(request.getContent())) {
            if (exist != null) {
                aiPanelStateMapper.deleteById(exist.getId());
            }
            return vo(pageKey, null, null);
        }

        // withNano(0)：DATETIME 只到秒，带纳秒落库再读回来会多出一截小数
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String content = request.getContent().trim();
        if (exist == null) {
            AiPanelState row = new AiPanelState();
            row.setPageKey(pageKey);
            row.setUserId(userId);
            row.setContent(content);
            row.setUpdatedAt(now);
            aiPanelStateMapper.insert(row);
        } else {
            exist.setContent(content);
            exist.setUpdatedAt(now);
            aiPanelStateMapper.updateById(exist);
        }
        return vo(pageKey, content, now);
    }

    /**
     * 读取本人在这一页的面板内容，没存过返回 content 为 null 的对象。
     */
    public AiPanelStateVO get(String pageKey) {
        String key = assertPageKey(pageKey);
        Long userId = currentIdOrNull();
        if (userId == null) {
            return vo(key, null, null);
        }
        AiPanelState row = find(key, userId);
        if (row == null) {
            return vo(key, null, null);
        }
        return vo(row.getPageKey(), row.getContent(), row.getUpdatedAt());
    }

    /**
     * 删除本人这一页的面板内容。重复删除不报错。
     */
    public void delete(String pageKey) {
        String key = assertPageKey(pageKey);
        Long userId = currentIdOrNull();
        if (userId == null) {
            return;
        }
        aiPanelStateMapper.delete(new LambdaQueryWrapper<AiPanelState>()
                .eq(AiPanelState::getPageKey, key)
                .eq(AiPanelState::getUserId, userId));
    }

    // ---------- 内部 ----------

    private AiPanelState find(String pageKey, Long userId) {
        return aiPanelStateMapper.selectOne(new LambdaQueryWrapper<AiPanelState>()
                .eq(AiPanelState::getPageKey, pageKey)
                .eq(AiPanelState::getUserId, userId)
                .last("LIMIT 1"));
    }

    /**
     * 页面键必须是四个之一。这是防串号的最后一道：
     * page_key 写错了不报错，只会存到一个谁也找不到的键上，
     * 表现为「退出了却恢复不出来」——最难查的那类问题。
     */
    private String assertPageKey(String pageKey) {
        if (!AiPanelState.PAGE_APPLY.equals(pageKey)
                && !AiPanelState.PAGE_LIST.equals(pageKey)
                && !AiPanelState.PAGE_INVOICE.equals(pageKey)
                && !AiPanelState.PAGE_EXPENSE.equals(pageKey)) {
            throw BusinessException.paramError("未知的助手页面：" + pageKey);
        }
        return pageKey;
    }

    private Long currentIdOrNull() {
        SysUser user = UserContext.get();
        return user == null ? null : user.getId();
    }

    private AiPanelStateVO vo(String pageKey, String content, LocalDateTime updatedAt) {
        AiPanelStateVO vo = new AiPanelStateVO();
        vo.setPageKey(pageKey);
        vo.setContent(content);
        vo.setUpdatedAt(updatedAt == null ? null : updatedAt.format(DATE_TIME));
        return vo;
    }
}
