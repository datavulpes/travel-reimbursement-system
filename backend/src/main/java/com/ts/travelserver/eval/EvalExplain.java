package com.ts.travelserver.eval;

import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.vo.EvalResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评价依据（eval_result.explain_json）的读写。
 *
 * <p>存它是为了「分数可解释」：审批人点开评价，能看到每个因子的原始取值和命中描述，
 * 而不是只有一句「总分 72.7，值得关注」。
 *
 * <p>JSON 结构：
 * <pre>{ "factors": [ { "key","name","score","weight","value" } ], "hints": [ "..." ] }</pre>
 *
 * <p>TODO-版本注意：Spring Boot 4 自带 Jackson 3，包名是 tools.jackson.databind，
 * 不是老教程里的 com.fasterxml.jackson.databind。照抄网上的写法会编译不过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalExplain {

    private final ObjectMapper objectMapper;

    /**
     * 把因子明细与提示语序列化成 JSON 文本。
     */
    public String write(List<EvalResultVO.EvalFactorVO> factors, List<String> hints) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> factorList = new ArrayList<>();
        if (factors != null) {
            for (EvalResultVO.EvalFactorVO factor : factors) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", factor.getKey());
                item.put("name", factor.getName());
                item.put("score", factor.getScore() == null ? null : factor.getScore().toPlainString());
                item.put("weight", factor.getWeight() == null ? null : factor.getWeight().toPlainString());
                item.put("value", factor.getValue());
                factorList.add(item);
            }
        }
        root.put("factors", factorList);
        root.put("hints", hints == null ? List.of() : hints);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("评价依据序列化失败", e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "评价依据序列化失败");
        }
    }

    /**
     * 把 AI 理由序列化成 JSON 文本，存进 eval_result.ai_reasons。
     *
     * <p>和 factors 分开存：AI 结论是「可能被丢弃的调节量」，
     * 混进依据明细里会让「分数可解释」这件事变得说不清。
     */
    public String writeAiReasons(List<com.ts.travelserver.vo.AiPointVO> reasons) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (reasons != null) {
            for (com.ts.travelserver.vo.AiPointVO item : reasons) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("point", item.getText());
                node.put("level", item.getLevel());
                Map<String, Object> evidence = new LinkedHashMap<>();
                if (item.getEvidence() != null) {
                    evidence.put("field", item.getEvidence().getField());
                    evidence.put("value", item.getEvidence().getValue());
                }
                node.put("evidence", evidence);
                list.add(node);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("reasons", list);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("AI 理由序列化失败", e);
            return null;
        }
    }

    /**
     * 读回 AI 理由。解析不出来返回空列表——AI 结论丢了不影响规则分。
     */
    public List<com.ts.travelserver.vo.AiPointVO> readAiReasons(String json) {
        List<com.ts.travelserver.vo.AiPointVO> result = new ArrayList<>();
        JsonNode array = array(json, "reasons");
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            com.ts.travelserver.vo.AiPointVO vo = new com.ts.travelserver.vo.AiPointVO();
            vo.setText(text(node, "point"));
            vo.setLevel(text(node, "level"));
            JsonNode ev = node.get("evidence");
            if (ev != null && !ev.isNull()) {
                com.ts.travelserver.vo.AiPointVO.EvidenceVO evidence =
                        new com.ts.travelserver.vo.AiPointVO.EvidenceVO();
                evidence.setField(text(ev, "field"));
                evidence.setValue(text(ev, "value"));
                vo.setEvidence(evidence);
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 读回因子明细。解析不出来就返回空列表，不能因为依据坏了连主结论都查不出来。
     */
    public List<EvalResultVO.EvalFactorVO> readFactors(String json) {
        List<EvalResultVO.EvalFactorVO> result = new ArrayList<>();
        JsonNode array = array(json, "factors");
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            EvalResultVO.EvalFactorVO vo = new EvalResultVO.EvalFactorVO();
            vo.setKey(text(node, "key"));
            vo.setName(text(node, "name"));
            vo.setScore(decimal(node, "score"));
            vo.setWeight(decimal(node, "weight"));
            vo.setValue(text(node, "value"));
            result.add(vo);
        }
        return result;
    }

    /**
     * 读回事前提示。
     */
    public List<String> readHints(String json) {
        List<String> hints = new ArrayList<>();
        JsonNode array = array(json, "hints");
        if (array == null) {
            return hints;
        }
        for (int i = 0; i < array.size(); i++) {
            String text = array.get(i).asString();
            if (text != null && !text.isBlank()) {
                hints.add(text);
            }
        }
        return hints;
    }

    private JsonNode array(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode node = root == null ? null : root.get(field);
            return node == null || node.isNull() ? null : node;
        } catch (Exception e) {
            log.warn("评价依据解析失败，按空依据处理：{}", e.getMessage());
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        return child.asString();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
