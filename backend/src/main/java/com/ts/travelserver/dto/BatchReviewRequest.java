package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量复核：一次给多张报销单下同一个结论。
 *
 * <p>reason 在「带异常通过 / 退回」时必填，与单笔 E-03 同一条规矩：
 * 这两档结论没有说明，事后翻单只看得到结论看不到为什么。
 */
@Data
public class BatchReviewRequest {

    /** 报销单号列表 */
    @NotEmpty(message = "请至少选择一张报销单")
    @Size(max = 50, message = "一次最多处理 50 张单")
    private List<String> reportNos;

    /** APPROVED / PASS_WITH_ISSUE / RETURNED */
    private String result;

    private String reason;
}
