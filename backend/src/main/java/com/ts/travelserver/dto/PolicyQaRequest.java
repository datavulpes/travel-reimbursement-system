package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 制度问答的入参（就一句问话）。
 *
 * <p>刻意不带任何上下文参数：问的是「公司现行规定」，不是「我这趟能报多少」。
 * 后者的答案是跟着具体单据变的，那是填单页差标校验的事，不该混进问答里——
 * 混进来之后，「这趟超标了吗」和「超标了怎么办」会变成同一个问题的两种答案。
 */
@Data
public class PolicyQaRequest {

    @NotBlank(message = "请写下想问的问题")
    @Size(max = 100, message = "问题不超过 100 字")
    private String question;
}
