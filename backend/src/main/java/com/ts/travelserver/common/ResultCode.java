package com.ts.travelserver.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局错误码。
 *
 * <p>口径来自设计报告 7.7，前端 request.js 直接按 code 判断，数值不要随意改。
 * 约定：0 成功；1xxx 参数问题；2xxx 业务规则拦截；3xxx 权限；4xxx 登录态；5xxx 系统异常。
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    /** 成功 */
    SUCCESS(0, "ok"),

    /** 参数校验失败：必填缺失、格式错误，msg 里会带上具体字段名 */
    PARAM_ERROR(1001, "参数校验失败"),

    /** 重复发票：invoice_no 命中唯一索引，msg 附带原申请号 */
    INVOICE_DUPLICATE(2001, "该发票号已被使用"),

    /** 状态不允许：非法状态流转，例如已进入复核还去改票据挂接（BR-10） */
    STATUS_FORBIDDEN(2002, "当前状态不允许该操作"),

    /** 差标超限但没填超标说明（BR-01） */
    EXCEED_REASON_REQUIRED(2003, "超出差标，必须填写超标说明"),

    /** 报销预检未通过且没带强制说明（BR-05） */
    PRECHECK_FAILED(2004, "预检未通过，请修改后提交或填写强制说明"),

    /** 角色不匹配（NFR-03） */
    NO_PERMISSION(3001, "无权限执行该操作"),

    /** 未登录或 token 失效 */
    NOT_LOGIN(4001, "未登录或登录已失效"),

    /** 兜底：未预期异常，对外只给通用提示，不暴露堆栈 */
    SYSTEM_ERROR(5000, "系统繁忙，请稍后再试");

    private final int code;
    private final String msg;

    /**
     * 按 code 反查枚举，取不到就当系统异常，避免空指针。
     */
    public static ResultCode of(int code) {
        for (ResultCode item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return SYSTEM_ERROR;
    }
}
