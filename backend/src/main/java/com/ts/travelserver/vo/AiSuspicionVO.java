package com.ts.travelserver.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 票据可疑模式提示（E-02 的补充）。
 *
 * <p>四维比对抓的是「不一致」：人不对、日期不在行程里、金额对不上。
 * 这里抓的是「太巧了」：同一天同一家店开了好几张、发票号连号、
 * 单张金额显著高于本单均值。
 *
 * <p>只提示，不判违规——系统没有权力替业务认定这张票该不该报（BR-06）。
 */
@Data
public class AiSuspicionVO {

    /** 报销单号 */
    private String reportNo;

    /** 票据张数，0 张时下面的 points 只有一句「没有可分析的票据」 */
    private Integer invoiceCnt;

    private List<AiPointVO> points = new ArrayList<>();

    private String summary;

    /** true = 本地启发式（本能力是确定性规则，恒为 true） */
    private Boolean local;
}
