package com.kami.qqbot.BilibiliRelated;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 千用 k、万用 w、百万用 m，数字部分最多三个数字（不含小数点）。 */
public final class CompactNumberFormatter {
    // b 及以上仅用于极大计数继续进位，避免百万数超过三位。
    private static final long[] DIVISORS = {
            1_000L, 10_000L, 1_000_000L, 1_000_000_000L,
            1_000_000_000_000L, 1_000_000_000_000_000L, 1_000_000_000_000_000_000L
    };
    private static final String[] UNITS = {"k", "w", "m", "b", "t", "q", "Q"};

    private CompactNumberFormatter() {}

    /** 缺失或无效的统计使用 —，不伪装成零。 */
    public static String format(long count) {
        if (count < 0) return "—";
        if (count < DIVISORS[0]) return Long.toString(count);

        int unit = 0;
        while (unit + 1 < DIVISORS.length && count >= DIVISORS[unit + 1]) unit++;

        BigDecimal number = BigDecimal.valueOf(count);
        while (true) {
            BigDecimal divisor = BigDecimal.valueOf(DIVISORS[unit]);
            BigDecimal scaled = number.divide(divisor);
            int decimals = scaled.compareTo(BigDecimal.valueOf(100)) < 0 ? 1 : 0;
            BigDecimal rounded = scaled.setScale(decimals, RoundingMode.HALF_UP);
            if (rounded.compareTo(BigDecimal.valueOf(100)) >= 0) {
                rounded = rounded.setScale(0, RoundingMode.HALF_UP);
            }
            // 如 9.999k -> 1.0w、99.999w -> 1.0m，而非显示四位数字。
            if (unit + 1 < DIVISORS.length
                    && rounded.multiply(divisor).compareTo(BigDecimal.valueOf(DIVISORS[unit + 1])) >= 0) {
                unit++;
                continue;
            }
            return rounded.toPlainString() + UNITS[unit];
        }
    }
}
