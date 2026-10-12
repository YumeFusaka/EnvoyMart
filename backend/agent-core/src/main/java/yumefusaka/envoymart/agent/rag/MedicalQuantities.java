package yumefusaka.envoymart.agent.rag;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅规范核验副本中的数量写法；不换算单位、不推理范围，也不改原文与引用位置。 */
final class MedicalQuantities {
    private static final String CHINESE_NUMBER = "[零〇一二两三四五六七八九十百千万亿点半壹贰叁肆伍陆柒捌玖拾佰仟萬億]+";
    private static final String NUMBER = "(?:[0-9]+(?:\\.[0-9]+)?(?:[十百千万亿拾佰仟萬億](?:" + CHINESE_NUMBER + ")?)?|" + CHINESE_NUMBER + ")";
    private static final String UNIT = "(?:毫克|微克|公斤|千克|克|个?小时|个?分钟|天|周|个月|年|粒|片|丸|袋|iu|岁)";
    private static final String RANGE = "(?:至|到|[-~～—–])";
    private static final Pattern SEPARATOR = Pattern.compile(RANGE);
    private static final Pattern UNIT_RANGE = Pattern.compile("(" + NUMBER + ")(" + UNIT + ")" + RANGE + "(" + NUMBER + ")(" + UNIT + ")");
    private static final Pattern CANDIDATE = Pattern.compile("(" + NUMBER + "(?:" + RANGE + NUMBER + ")*)(" + UNIT + ")(半)?");
    private static final Pattern CONTEXT = Pattern.compile("(?:每日|每天|每次|间隔|剂量|用量|摄入量|含量|服用|服药|用药|补充|推荐|建议|上限|最高|含|摄入|取)(?:为|是|约|约为|至少|最多|不超过|不少于|应|需)*$");
    private static final Pattern DOSE_NAME_CONTEXT = Pattern.compile("(?:每日|每天|每次|剂量|用量|摄入量|含量|上限|最高)[^，,。；;！？!?\\n]*$");
    static final Pattern QUANTITY = Pattern.compile("\\d+(?:\\.\\d+)?(?:-\\d+(?:\\.\\d+)?)?(?:毫克|微克|公斤|千克|克|小时|分钟|天|周|个月|年|粒|片|丸|袋|iu)");

    private MedicalQuantities() { }

    static String normalize(String text) {
        String ranges = UNIT_RANGE.matcher(text).replaceAll(match -> {
            String left = number(match.group(1));
            String right = number(match.group(3));
            String boundary = left != null && right != null && match.start() > 0 && Character.isDigit(text.charAt(match.start() - 1)) ? " " : "";
            String replacement = left != null && right != null && unit(match.group(2)).equals(unit(match.group(4)))
                    ? boundary + left + "-" + right + unit(match.group(2)) : match.group();
            return Matcher.quoteReplacement(replacement);
        });
        return CANDIDATE.matcher(ranges).replaceAll(match -> {
            if (drugName(match.group(1), match.group(2), ranges.substring(0, match.start()))) return Matcher.quoteReplacement(match.group());
            String value = range(match.group(1));
            if (value != null && match.group(3) != null) {
                value = value.contains("-") ? null : new BigDecimal(value).add(new BigDecimal("0.5")).stripTrailingZeros().toPlainString();
            }
            String boundary = value != null && match.start() > 0 && Character.isDigit(ranges.charAt(match.start() - 1)) ? " " : "";
            return Matcher.quoteReplacement(value == null ? match.group() : boundary + value + unit(match.group(2)));
        });
    }

    /** 有剂量语法却不能确定数值时必须拒绝，不能退成空断言；裸药材名不当数量。 */
    static boolean hasUnparsedQuantity(String text) {
        Matcher repeatedUnits = UNIT_RANGE.matcher(text);
        while (repeatedUnits.find()) {
            if (!unit(repeatedUnits.group(2)).equals(unit(repeatedUnits.group(4)))) return true;
        }
        Matcher candidate = CANDIDATE.matcher(text);
        while (candidate.find()) {
            if (drugName(candidate.group(1), candidate.group(2), text.substring(0, candidate.start()))) continue;
            if (range(candidate.group(1)) != null && !(candidate.group(3) != null && SEPARATOR.matcher(candidate.group(1)).find())) continue;
            if (CONTEXT.matcher(text.substring(0, candidate.start())).find()
                    || candidate.group(1).matches(".*[0-9十百千万亿点半拾佰仟萬億.].*")) return true;
        }
        return false;
    }

    private static boolean drugName(String number, String unit, String prefix) {
        return "三七".equals(number) && "片".equals(unit) && !DOSE_NAME_CONTEXT.matcher(prefix).find();
    }

    private static String unit(String text) {
        return "个小时".equals(text) ? "小时" : "个分钟".equals(text) ? "分钟" : text;
    }

    private static String range(String token) {
        String[] values = SEPARATOR.split(token, -1);
        if (values.length > 2) return null;
        String left = number(values[0]);
        if (left == null) return null;
        if (values.length == 1) return left;
        String right = number(values[1]);
        return right == null ? null : left + "-" + right;
    }

    private static String number(String raw) {
        String text = raw.replace('壹', '一').replace('贰', '二').replace('叁', '三').replace('肆', '四')
                .replace('伍', '五').replace('陆', '六').replace('柒', '七').replace('捌', '八').replace('玖', '九')
                .replace('拾', '十').replace('佰', '百').replace('仟', '千').replace('萬', '万').replace('億', '亿');
        if ("半".equals(text)) return "0.5";
        if (text.matches("\\d+(?:\\.\\d+)?")) return new BigDecimal(text).stripTrailingZeros().toPlainString();
        String[] decimal = text.split("点", -1);
        if (decimal.length > 2) return null;
        BigInteger integer = integer(decimal[0]);
        if (integer == null) return null;
        if (decimal.length == 1) return integer.toString();
        if (decimal[1].isEmpty()) return null;
        StringBuilder fraction = new StringBuilder();
        for (char character : decimal[1].toCharArray()) {
            int digit = digit(character);
            if (digit < 0) return null;
            fraction.append(digit);
        }
        return new BigDecimal(integer + "." + fraction).stripTrailingZeros().toPlainString();
    }

    private static BigInteger integer(String text) {
        if (text.isEmpty()) return null;
        if (text.matches("\\d+")) return new BigInteger(text);
        if (!text.matches(".*[十百千万亿].*")) {
            if (text.length() > 1 && !text.contains("零") && !text.contains("〇")) return null;
            StringBuilder digits = new StringBuilder();
            for (char character : text.toCharArray()) {
                int digit = digit(character);
                if (digit < 0) return null;
                digits.append(digit);
            }
            return new BigInteger(digits.toString());
        }
        BigInteger total = BigInteger.ZERO;
        BigInteger section = BigInteger.ZERO;
        int current = -1;
        int previousSmall = 10000;
        int previousLarge = Integer.MAX_VALUE;
        int lastUnit = 0;
        boolean explicitZero = false;
        for (char character : text.toCharArray()) {
            int digit = digit(character);
            if (digit >= 0) {
                if (current > 0) return null;
                current = digit;
                if (digit == 0) explicitZero = true;
                continue;
            }
            int unit = switch (character) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; case '万' -> 10000; case '亿' -> 100000000; default -> -1; };
            if (unit < 0) return null;
            if (unit < 10000) {
                if (unit >= previousSmall) return null;
                section = section.add(BigInteger.valueOf((current < 0 ? 1 : current) * (long) unit));
                previousSmall = unit;
            } else {
                if (unit >= previousLarge) return null;
                BigInteger value = section.add(BigInteger.valueOf(Math.max(current, 0)));
                total = total.add((value.signum() == 0 ? BigInteger.ONE : value).multiply(BigInteger.valueOf(unit)));
                section = BigInteger.ZERO;
                previousSmall = 10000;
                previousLarge = unit;
            }
            current = -1;
            lastUnit = unit;
            explicitZero = false;
        }
        // “一百二”可能指120或102；只认明确的“一百二十”“一百零二”。
        if (current > 0 && lastUnit >= 100 && !explicitZero) return null;
        return total.add(section).add(BigInteger.valueOf(Math.max(current, 0)));
    }

    private static int digit(char value) {
        return switch (value) {
            case '零', '〇', '0' -> 0; case '一', '1' -> 1; case '二', '两', '2' -> 2;
            case '三', '3' -> 3; case '四', '4' -> 4; case '五', '5' -> 5; case '六', '6' -> 6;
            case '七', '7' -> 7; case '八', '8' -> 8; case '九', '9' -> 9; default -> -1;
        };
    }
}
