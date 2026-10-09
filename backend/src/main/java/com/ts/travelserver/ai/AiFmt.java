package com.ts.travelserver.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 喂给模型的数字怎么格式化。月度洞察与钻取解释共用一份，别各写一套。
 *
 * <h3>为什么口径必须统一</h3>
 * 这些数字会被模型原样复述到页面上。两处格式化不一致（一处两位小数、一处三位），
 * 页面上同一件事就会出现两个写法，看的人只能认定有一个是错的。
 * 而且校验白名单是按"输入里出现过的字符串"建的，写法一多，误杀也跟着多。
 *
 * <h3>money 为什么不加千分位</h3>
 * 模型复述时容易把自己加的分隔符当成原值，写出来就通不过数字校验。
 * 整元取整也是同理：少一位小数，少一次"这个数对不对得上"的纠缠。
 */
public final class AiFmt {

    private AiFmt() {
    }

    public static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 金额显示：整元 */
    public static String money(BigDecimal value) {
        return nz(value).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 费效比显示，与前端 utils/dict.js 的 ratio() 同一口径：
     * 小于 0.1 的留三位（不然 0.05 和 0.06 看着一样），其余两位。
     */
    public static String r(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        BigDecimal abs = value.abs();
        return abs.compareTo(BigDecimal.ZERO) > 0 && abs.compareTo(new BigDecimal("0.1")) < 0
                ? value.setScale(3, RoundingMode.HALF_UP).toPlainString()
                : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 归因贡献保留四位，展示时再压 */
    public static String r4(BigDecimal value) {
        return value == null ? "—" : value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /** 比率转百分数，不带小数 */
    public static String pct(BigDecimal value) {
        return nz(value).multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    public static String str(String value) {
        return value == null ? "" : value;
    }

    /** 分组名缺失时的占位，与页面上一致 */
    public static String name(String value) {
        return value == null || value.isBlank() ? "未标注" : value.trim();
    }
}
