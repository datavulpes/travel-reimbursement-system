package com.ts.travelserver.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

/**
 * 全局异常处理器。
 *
 * <p>设计报告 6.9 与 7.7：Controller 不写 try-catch，异常统一在这里转成 R。
 * 三条原则：
 * <ol>
 *   <li>业务异常原样返回错误码和原因，前端按 code 分流；</li>
 *   <li>参数校验失败返回字段级定位信息，前端滚动定位到出错字段（NFR-06）；</li>
 *   <li>系统异常记 ERROR 日志，对外只给通用提示，不暴露堆栈。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：直接透传错误码与原因。
     */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常 code={}, msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * @RequestBody 校验失败（@Valid 打在 body 上）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        return R.fail(ResultCode.PARAM_ERROR, firstFieldError(e.getBindingResult().getFieldErrors()));
    }

    /**
     * 表单 / 查询参数绑定校验失败（@Valid 打在对象参数上）。
     */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        return R.fail(ResultCode.PARAM_ERROR, firstFieldError(e.getBindingResult().getFieldErrors()));
    }

    /**
     * @Validated 打在方法参数上的校验失败。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> handleConstraintViolationException(ConstraintViolationException e) {
        StringBuilder sb = new StringBuilder();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            sb.append(violation.getPropertyPath()).append("：").append(violation.getMessage()).append("；");
            break;
        }
        return R.fail(ResultCode.PARAM_ERROR, trimTail(sb.toString()));
    }

    /**
     * 缺必填的请求参数，例如列表接口没带 applyNo。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParamException(MissingServletRequestParameterException e) {
        return R.fail(ResultCode.PARAM_ERROR, "缺少参数 " + e.getParameterName());
    }

    /**
     * 参数类型对不上，例如 /apply/abc 想转成 Long。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
        return R.fail(ResultCode.PARAM_ERROR, "参数 " + e.getName() + " 格式不正确");
    }

    /**
     * body 为空或 JSON 解析失败。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return R.fail(ResultCode.PARAM_ERROR, "请求体格式不正确");
    }

    /**
     * 票据影像超过 10MB 上限。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUploadSizeException(MaxUploadSizeExceededException e) {
        return R.fail(ResultCode.PARAM_ERROR, "文件超过 10MB 上限，请压缩后再上传");
    }

    /**
     * 兜底。系统异常必须记日志，对外只给通用提示。
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return R.fail(ResultCode.SYSTEM_ERROR);
    }

    /**
     * 取第一个字段错误拼成「字段名：原因」，够前端定位就行，不一次性堆全部错误。
     */
    private String firstFieldError(List<FieldError> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return ResultCode.PARAM_ERROR.getMsg();
        }
        FieldError error = fieldErrors.get(0);
        return error.getField() + "：" + error.getDefaultMessage();
    }

    private String trimTail(String text) {
        if (text == null || text.isEmpty()) {
            return ResultCode.PARAM_ERROR.getMsg();
        }
        return text.endsWith("；") ? text.substring(0, text.length() - 1) : text;
    }
}
