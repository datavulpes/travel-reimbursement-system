package com.ts.travelserver.ai;

import java.util.List;

/**
 * 城市名识别。
 *
 * <p>系统里没有城市字典表（设计报告里也确实没有这一张），
 * 而「对象在深圳、行程去武汉」这类判断又必须认出城市，
 * 这里先放一份常用城市清单兜底。
 *
 * <p>TODO-需要你补基础数据：正式做法应建 city_level 字典表（目的地 → 城市等级），
 * 那时候这个类改成查表即可，调用方不用动。
 */
public final class CityHints {

    private static final List<String> CITIES = List.of(
            "北京", "上海", "广州", "深圳", "东莞", "长沙", "武汉", "杭州", "成都", "苏州",
            "南京", "重庆", "西安", "青岛", "天津", "佛山", "珠海", "厦门", "宁波", "无锡");

    private CityHints() {
    }

    /**
     * 从一段文字里认出城市名；认不出来返回空串。
     *
     * <p>按清单顺序找，命中第一个就返回。对象名里出现多个城市的情况极少，
     * 真遇到了也只影响一条提示语的措辞，不值得为它排序。
     */
    public static String cityOf(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        for (String city : CITIES) {
            if (text.contains(city)) {
                return city;
            }
        }
        return "";
    }

    /**
     * 全部已知城市，给「模型输出里有没有凭空冒出一个城市」这类校验用。
     *
     * <p>返回不可变视图：这份清单是全系统的城市口径，被人在外面加一项减一项之后，
     * 语义评价与建议校验两边会得到不同的结论，到时候查不出是哪个环节改的。
     */
    public static List<String> cities() {
        return CITIES;
    }
}
