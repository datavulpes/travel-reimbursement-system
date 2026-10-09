package com.ts.travelserver.common;

import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>设计报告 6.9：所有可预期的业务问题都抛这个，由全局异常处理器转成统一返回体，
 * Controller 里不写 try-catch。抛出时带错误码，前端才能按 code 分流处理。
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码，取值见 {@link ResultCode} */
    private final int code;

    /**
     * 用标准错误码抛出，提示语取枚举默认值。
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMsg());
        this.code = resultCode.getCode();
    }

    /**
     * 用标准错误码 + 自定义提示语。
     *
     * <p>例如差标超限：new BusinessException(ResultCode.EXCEED_REASON_REQUIRED, "第 2 段行程超出差标 200 元")
     */
    public BusinessException(ResultCode resultCode, String msg) {
        super(msg);
        this.code = resultCode.getCode();
    }

    /**
     * 指定错误码与提示语，用于标准枚举没覆盖的场景。
     */
    public BusinessException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    /**
     * 高频场景的快捷构造：参数问题。
     */
    public static BusinessException paramError(String msg) {
        return new BusinessException(ResultCode.PARAM_ERROR, msg);
    }

    /**
     * 高频场景的快捷构造：状态流转不允许。
     */
    public static BusinessException statusForbidden(String msg) {
        return new BusinessException(ResultCode.STATUS_FORBIDDEN, msg);
    }
}
