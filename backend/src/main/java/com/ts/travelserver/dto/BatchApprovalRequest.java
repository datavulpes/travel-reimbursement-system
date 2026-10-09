package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量审批：一次给多张申请单下同一个结论。
 *
 * <p>共用同一份意见——批量操作本来就是「这几张单我看过，都是同一个处理」，
 * 要求每张单各写一句意见的话，这个按钮就没有意义了。
 * 要各写各的，就一张一张点进去批。
 */
@Data
public class BatchApprovalRequest {

    /** 申请号列表 */
    @NotEmpty(message = "请至少选择一张申请单")
    @Size(max = 50, message = "一次最多处理 50 张单")
    private List<String> applyNos;

    /** APPROVED / REJECTED，与单笔 B-03 同口径 */
    private String result;

    /** 审批意见，驳回时必填（与单笔一致，BR-03） */
    private String opinion;
}
