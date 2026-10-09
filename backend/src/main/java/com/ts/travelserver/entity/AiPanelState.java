package com.ts.travelserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 助手面板状态（ai_panel_state）：某个人在某个页面上，助手当时停在哪个问答。
 *
 * <p>为什么单开一张表，而不复用 {@link FieldDraft}：草稿的 content 只有 200 字，
 * 语义也是「一句话理由没写完」，装不下一问一答；而这里要存的是面板当前这一屏。
 * 两者形状不同——草稿是「某人+某单+某字段」，这里是「某人+某页面」，
 * 跟单据号没关系，也不参与任何统计与留痕。
 *
 * <p>page_key 必须与前端路由名逐字一致。写错了不会报错，
 * 只会存到一个谁也找不到的键上，表现为「明明退出了却恢复不出来」——
 * 所以 Service 层对 page_key 做了白名单校验，见 {@code AiPanelStateService.assertPageKey}。
 */
@Data
@TableName("ai_panel_state")
public class AiPanelState {

    // ---------- 页面键：与前端路由名逐字一致 ----------
    /** 填写出差申请 */
    public static final String PAGE_APPLY = "empApply";
    /** 我的申请 */
    public static final String PAGE_LIST = "empList";
    /** 票据上传 */
    public static final String PAGE_INVOICE = "empInvoice";
    /** 报销提交 */
    public static final String PAGE_EXPENSE = "empExpense";

    /**
     * 内容上限。列是 TEXT，这里卡一道是为了挡住异常大的 payload
     * （比如前端把整段对话历史无限累加进来），而不是为了省空间。
     */
    public static final int MAX_LEN = 8000;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 页面键，取值见本类 PAGE_* 常量 */
    private String pageKey;

    /** 归属人，指向 sys_user.id；只认本人的面板内容 */
    private Long userId;

    /** 面板当前内容（JSON 字符串），由前端序列化后原样存取，后端不解析 */
    private String content;

    /** 最后一次保存时间 */
    private LocalDateTime updatedAt;
}
