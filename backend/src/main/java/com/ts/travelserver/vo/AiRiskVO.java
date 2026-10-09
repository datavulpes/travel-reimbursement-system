package com.ts.travelserver.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 审批风险摘要（AI 归纳，不替审批人下结论）。
 *
 * <p>输入全是系统里已经算好的东西：同类花费参考、差标结论、评价档位、流转记录。
 * 这里做的只是「把散落在四张卡里的信号汇总成几条话」，
 * 不新增任何判定逻辑，也不改任何分数——审批人照样可以全部无视。
 */
@Data
public class AiRiskVO {

    /** 申请号 */
    private String applyNo;

    /** 关注点，按重要程度排序 */
    private List<AiPointVO> points = new ArrayList<>();

    /** 一句话总结，前端摆在卡片底部 */
    private String summary;

    /** true = 本地启发式生成的措辞 */
    private Boolean local;
}
