package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 上传票据（C-01）。
 *
 * <p>前端用 multipart 提交两个字段：file（文件，≤10MB）和 applyNo。
 * 文件本身由 Controller 用 MultipartFile 单独接收，这个对象只装文本参数。
 *
 * <p>流程：文件落盘 → OCR 识别票号/金额/日期/销售方 → 回显待确认。
 * 识别失败返回 ocrSuccess=false，前端转手工录入，不阻塞主流程（设计报告 6.4.2）。
 */
@Data
public class InvoiceUploadRequest {

    /** 挂接申请号 */
    @NotBlank(message = "申请号不能为空")
    private String applyNo;

    /**
     * 是否跳过 OCR 直接走手工录入。前端识别失败后重传时可以设为 true，省一次无效调用。
     */
    private Boolean skipOcr = false;
}
