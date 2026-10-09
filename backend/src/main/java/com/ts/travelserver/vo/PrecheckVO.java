package com.ts.travelserver.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 前置预检结果（D-02）。
 *
 * <p>提交前的「预检一次」走这个 VO：口径与提交时完全一致（同一个 computeChecks），
 * 不落库、不生成报销单。员工看到 FORCE 就知道必须写强制说明才能交。
 *
 * <p>金额三件套一并返回：页面上的冲抵/净额本来是前端自己算的，
 * 有后端口径后就以后端为准，避免「页面显示的和提交落库的对不上」。
 */
@Data
public class PrecheckVO {

    /** 总结论：PASS / WARN / FORCE */
    private String precheckResult;

    /** 逐项明细，level 三档 PASS / WARN / FORCE */
    private List<PrecheckIssueVO> checks;

    /** 票据合计 */
    private BigDecimal totalAmount;

    /** 预借冲抵 */
    private BigDecimal offsetAmount;

    /** 应付净额 */
    private BigDecimal netAmount;

    /** 是否必须填写强制提交说明（precheckResult == FORCE 时为 true） */
    private Boolean needForceReason;
}
