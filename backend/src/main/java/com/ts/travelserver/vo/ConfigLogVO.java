package com.ts.travelserver.vo;

import lombok.Data;

/**
 * 调整留痕（H-04）。
 *
 * <p>字段名按 Apifox 接口文档与前端 StandardConfig.vue 来定：
 * type / targetId / before / after / reason，而不是后端早先写的
 * itemType / itemKey / beforeValue / afterValue / remark。
 * 三方口径不一致时以前端能跑起来为准，改的是后端。
 *
 * <p>数据来自 config_log 表：差标与部门预算的每一次增、删、改都会 append 一条，
 * 之前是从 trip_standard 的版本链临时推导的，预算那边根本查不到，
 * 管理层填的「调整原因」也没有地方存。留痕是只读的，谁也不能改。
 */
@Data
public class ConfigLogVO {

    /** 留痕主键 */
    private Long id;

    /** 变更类型：STANDARD 差标、BUDGET 预算、DEPT 部门 */
    private String type;

    /** 操作类型：INSERT 新增、UPDATE 修改、DELETE 删除 */
    private String action;

    /** 变更项标识，如「二级-高铁」「dept:1」 */
    private String targetId;

    /** 变更项的可读名称，如「销售部」，前端不用自己按 targetId 猜 */
    private String targetName;

    /** 调整前的值，新增时为 null，前端显示「—」 */
    private String before;

    /** 调整后的值，删除时为 null */
    private String after;

    /** 调整原因，管理层填写的原话 */
    private String reason;

    /** 操作人姓名 */
    private String operator;

    /** 操作时间，yyyy-MM-dd HH:mm:ss */
    private String operateAt;
}
