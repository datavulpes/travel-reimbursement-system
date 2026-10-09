package com.ts.travelserver.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一返回体。
 *
 * <p>设计报告 7.2：所有接口都是 { code, msg, data } 三层结构，前端只看 code。
 * 列表接口把 PageResult 塞进 data，单对象接口直接塞对象。
 *
 * @param <T> data 的实际类型
 */
@Data
@NoArgsConstructor
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 0 成功，非 0 为错误码 */
    private int code;

    /** 成功为 ok，失败为可直接展示给用户的中文原因 */
    private String msg;

    /** 业务数据 */
    private T data;

    private R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    // ---------- 成功 ----------

    public static <T> R<T> ok() {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    /**
     * 成功但想换一句提示语，比如「提交成功，申请号 TA202609180001」。
     */
    public static <T> R<T> ok(String msg, T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), msg, data);
    }

    // ---------- 失败 ----------

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public static <T> R<T> fail(ResultCode resultCode) {
        return new R<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    /**
     * 在标准错误码的提示后面追加细节，比如「参数校验失败：出差事由不能为空」。
     */
    public static <T> R<T> fail(ResultCode resultCode, String detail) {
        String msg = detail == null || detail.isEmpty()
                ? resultCode.getMsg()
                : resultCode.getMsg() + "：" + detail;
        return new R<>(resultCode.getCode(), msg, null);
    }

    /**
     * 只给一句中文原因、错误码用 5000 的兜底写法。业务异常请明确指定错误码。
     */
    public static <T> R<T> fail(String msg) {
        return new R<>(ResultCode.SYSTEM_ERROR.getCode(), msg, null);
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
