package com.ts.travelserver.dto;

import lombok.Data;

/**
 * 调整留痕查询条件（H-04）。
 *
 * <p>没有复用 {@link ApplyQuery} 是因为那边除了分页还带 status / listType / keyword，
 * 语义是「找单据」；留痕这边要按变更类型筛，混在一起容易误用，单开一份更清楚。
 */
@Data
public class ConfigLogQuery {

    /** 页码，从 1 开始 */
    private Long pageNum = 1L;

    /** 每页条数 */
    private Long pageSize = 10L;

    /**
     * 变更类型筛选：STANDARD 差标、BUDGET 预算、DEPT 部门。留空查全部。
     */
    private String type;

    /**
     * 关键字，模糊匹配变更项标识与调整原因，留空不过滤。
     */
    private String keyword;

    /** 页码兜底，前端不传或传 0 时当第 1 页 */
    public long safePageNum() {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    /** 每页条数兜底，上限压到 100，防止一次性拉全表 */
    public long safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return 10L;
        }
        return Math.min(pageSize, 100L);
    }
}
