package com.ts.travelserver.ai;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型输出的数字校验。月度洞察与钻取解释共用同一套，别各写一份。
 *
 * <h3>为什么必须共用</h3>
 * 这条闸是本项目「模型不算数」这条口径的唯一执行者：
 * 模型输出的任何一句话，只要里面出现一个输入中不存在的数字，整条丢弃。
 * 两个场景各写一份，迟早有一份漏掉一种等价写法（比如 0.4 与 40%），
 * 而漏掉的那一次不会报错——它会以一句通顺的结论出现在页面上。
 *
 * <h3>为什么允许等价写法</h3>
 * 不是为了放宽校验。模型把 0.45 写成 45%、把 0.40 写成 0.4 都是正常的表达习惯，
 * 因为写法不同就丢掉一条本来正确的结论，是把校验变成了罚站。
 * 真正要拦的是输入里压根不存在的数——那种数是编的。
 */
public final class AiTextGuard {

    /** 一段话里的数字。只认阿拉伯数字，中文数字与「两成」这种写法不进校验 */
    private static final Pattern NUM = Pattern.compile("\\d+(?:\\.\\d+)?");

    /** 结论档位。档位不在这个集合里的一律丢弃 */
    public static final List<String> LEVELS = List.of("high", "mid", "low");

    private AiTextGuard() {
    }

    /**
     * 输入里出现过的数字（含常见的等价写法）。
     *
     * @param inputJson 喂给模型的那份结构化入参
     */
    public static Set<String> allowedNumbers(String inputJson) {
        Set<String> set = new HashSet<>();
        Matcher m = NUM.matcher(inputJson == null ? "" : inputJson);
        while (m.find()) {
            String raw = m.group();
            set.add(raw);
            try {
                BigDecimal v = new BigDecimal(raw);
                set.add(v.stripTrailingZeros().toPlainString());
                for (int scale = 0; scale <= 2; scale++) {
                    set.add(v.setScale(scale, RoundingMode.HALF_UP).toPlainString());
                }
                BigDecimal hundred = v.multiply(new BigDecimal("100"));
                set.add(hundred.stripTrailingZeros().toPlainString());
                set.add(hundred.setScale(0, RoundingMode.HALF_UP).toPlainString());
                set.add(hundred.setScale(1, RoundingMode.HALF_UP).toPlainString());
            } catch (NumberFormatException ignored) {
                // 不是合法数字就只留原文
            }
        }
        return set;
    }

    /** 一段文字里出现的每个数字，是不是都在白名单里 */
    public static boolean numbersAllowed(String text, Set<String> allowed) {
        Matcher m = NUM.matcher(text == null ? "" : text);
        while (m.find()) {
            if (!allowed.contains(m.group())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 一条结论能不能上页面：档位合法、长度合规、依据挂在真实字段上、
     * 且正文与依据里出现的每个数字都能在输入里找到。
     *
     * @param fieldPrefixes 允许的 evidence.field 前缀；挂到一个不存在的字段等于凭空造依据
     * @param maxText       正文长度上限，超过说明模型在自由发挥
     */
    public static boolean okPoint(String level, String body, String field, String value,
                                  Set<String> allowed, List<String> fieldPrefixes, int maxText) {
        if (level == null || !LEVELS.contains(level.trim())) {
            return false;
        }
        if (body == null || body.isBlank() || body.trim().length() > maxText) {
            return false;
        }
        if (field == null || field.isBlank() || !knownField(field.trim(), fieldPrefixes)) {
            return false;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        return numbersAllowed(body, allowed) && numbersAllowed(value, allowed);
    }

    public static boolean knownField(String field, List<String> fieldPrefixes) {
        for (String prefix : fieldPrefixes) {
            if (field.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 取 JSON 节点上的文本；节点缺失或为 null 时返回 null */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        return child.asString();
    }
}
