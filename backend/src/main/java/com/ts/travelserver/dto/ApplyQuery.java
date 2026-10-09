package com.ts.travelserver.dto;

import lombok.Data;

/**
 * 列表查询条件。
 *
 * <p>设计报告 7.2：列表接口统一接收 pageNum、pageSize、status、keyword。
 * 我的申请（A-03）、我的报销（D-03）、待复核（E-01）都复用这一份。
 */
@Data
public class ApplyQuery {

    /** 页码，从 1 开始 */
    private Long pageNum = 1L;

    /** 每页条数 */
    private Long pageSize = 10L;

    /**
     * 状态筛选，传空查全部。
     * 申请单传 {@link com.ts.travelserver.entity.TripApply} 的 STATUS_*，
     * 报销单传 {@link com.ts.travelserver.entity.ExpenseReport} 的 STATUS_*。
     */
    private String status;

    /** 关键字：申请号、事由、对象名称模糊匹配 */
    private String keyword;

    /**
     * 待办 / 已办切换，只有审批列表（B-01）用得上。
     *
     * <p>todo（默认）查「等我批的」；done 查「我已经批过的」——
     * 判定方式是 approval_record 里有没有我签过字的记录。
     * 前端 ApprovalTodo.vue 的"待办 / 已办"两个 tab 就是传这个字段，
     * 之前后端没有这个参数，已办 tab 一直显示的是待办数据。
     */
    private String listType;

    /**
     * 页码兜底，前端不传或传 0 时当第 1 页。
     */
    public long safePageNum() {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    /**
     * 每页条数兜底，上限压到 100，防止一次性拉全表。
     */
    public long safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return 10L;
        }
        return Math.min(pageSize, 100L);
    }
}
