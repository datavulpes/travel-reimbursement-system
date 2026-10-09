package com.ts.travelserver.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量操作的结果。
 *
 * <p>三个批量接口（审批 / 复核 / 付款）共用这一个返回体：
 * 前端要的都是同一件事——成了几单、哪几单没成、为什么。
 */
@Data
public class BatchResultVO {

    /** 本次提交的单数 */
    private int total;

    /** 成功单数 */
    private int successCnt;

    /** 失败单数，等于 failed.size() */
    private int failCnt;

    /** 逐单的失败原因 */
    private List<FailVO> failed = new ArrayList<>();

    @Data
    public static class FailVO {

        /** 申请号或报销单号 */
        private String bizNo;

        /** 为什么没成，直接给人看 */
        private String msg;
    }
}
