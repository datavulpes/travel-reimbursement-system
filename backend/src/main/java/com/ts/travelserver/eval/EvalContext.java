package com.ts.travelserver.eval;

import com.ts.travelserver.entity.Partner;
import com.ts.travelserver.entity.TripItinerary;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 评价引擎的输入。
 *
 * <p>G-02 事前预演时 applyNo 为空（还没落库），A-02 提交时才有申请号。
 * 四个因子都只读这个对象，不自己去查申请单，这样预演和正式评分走的是同一套代码。
 */
@Data
@Builder
public class EvalContext {

    /** 申请号，预演时为空 */
    private String applyNo;

    private Long userId;

    private Long deptId;

    /** 关联往来对象，F1 / F2 / F3 都围着它算 */
    private Long partnerId;

    /** 出差事由，F4 按长度与完整度评分 */
    private String reason;

    /** 出差类型，查同类参考时用 */
    private String tripType;

    /** 预估合计，后端按「交通 + 住宿」重算后的值 */
    private BigDecimal estTotal;

    /** 行程段，F4 要看填得全不全；缺票推算与差标校验也用同一份 */
    private List<TripItinerary> itineraries;

    /** 往来对象，调用方已经查过就带上，省一次查询 */
    private Partner partner;
}
