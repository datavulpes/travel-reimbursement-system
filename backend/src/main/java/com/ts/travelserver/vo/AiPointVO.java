package com.ts.travelserver.vo;

import lombok.Data;

/**
 * AI 建议卡里的一条要点。
 *
 * <p>字段名与前端 {@code components/AiAdvice.vue} 的 points 一一对应：
 * {@code [{ level: 'high'|'mid'|'low', text, evidence?: { field, value } }]}。
 * 四个页面共用同一张卡，所以所有 AI 产出必须是这个形状。
 *
 * <p>evidence 是防幻觉的关键：每一条结论都必须指回一个真实字段，
 * 页面上会原样展示「依据 · reason = xxx」，让看的人自己判断这句话有没有道理。
 */
@Data
public class AiPointVO {

    /** 高：high，中：mid，低：low */
    private String level;

    /** 说给人看的一句话 */
    private String text;

    /** 依据；可以为空，但能带上的尽量带上 */
    private EvidenceVO evidence;

    /**
     * 这条是哪来的：{@code MODEL} 模型归纳，{@code RULE} 统计规则直接算出。
     *
     * <p>一份月报结论常常是混的：模型给出两三条，规则兜底补上一两条，
     * 而 {@code MonthlyInsightVO.local} 那种「整份报告一个布尔」的说法到此就不够用了。
     * 加这个字段是为了能逐条回答「这句话凭什么这么说」——
     * 规则出的那几条可以拿去跟统计层对账，模型出的那几条只能当措辞看。
     *
     * <p>页面上不印成一行小字（那是讲解型文案），只在鼠标停上去时显示。
     */
    private String source;

    public static final String SRC_MODEL = "MODEL";

    public static final String SRC_RULE = "RULE";

    /**
     * 结论依据：字段名 + 原文值。
     */
    @Data
    public static class EvidenceVO {

        /** 字段路径，如 reason、itinerary[0].toCity、invoice.invoiceDate */
        private String field;

        /** 该字段当时的值 */
        private String value;

        public EvidenceVO() {
        }

        public EvidenceVO(String field, String value) {
            this.field = field;
            this.value = value;
        }
    }

    public static AiPointVO of(String level, String text) {
        AiPointVO vo = new AiPointVO();
        vo.setLevel(level);
        vo.setText(text);
        return vo;
    }

    public static AiPointVO of(String level, String text, String field, String value) {
        AiPointVO vo = of(level, text);
        vo.setEvidence(new EvidenceVO(field, value));
        return vo;
    }

    /** 带来源的那一种。规则出的结论一定要标 RULE，别让它冒充模型输出 */
    public static AiPointVO of(String level, String text, String field, String value, String source) {
        AiPointVO vo = of(level, text, field, value);
        vo.setSource(source);
        return vo;
    }
}
