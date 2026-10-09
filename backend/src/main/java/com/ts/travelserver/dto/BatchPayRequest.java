package com.ts.travelserver.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量付款。
 *
 * <p>这里只收报销单号，不收金额：批量付款就是「照单付」，
 * 每单付多少由报销单自己的净额（总额 − 冲抵）说了算，
 * 哪张单金额要改，本来也该单独打开去改，不该挤在批量里让人逐行核对。
 *
 * <p>预借款也由后端按申请号找，不收 loanId：
 * 一笔预借对一笔付款（FR-0305），不会找错。
 */
@Data
public class BatchPayRequest {

    @NotEmpty(message = "请至少选择一张报销单")
    @Size(max = 50, message = "一次最多处理 50 张单")
    private List<String> reportNos;

    /** 付款备注，写进每张付款流水 */
    private String payRemark;
}
