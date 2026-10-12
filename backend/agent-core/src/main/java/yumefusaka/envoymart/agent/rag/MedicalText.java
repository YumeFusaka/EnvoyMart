package yumefusaka.envoymart.agent.rag;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按现有Markdown界面的可见文字核验；链接目标和代码里的编号不能变成平台引用。 */
final class MedicalText {
    private static final Pattern CODE = Pattern.compile("(`+)([\\s\\S]*?)\\1");
    private static final Pattern EMPHASIS = Pattern.compile("(\\*\\*|__|~~)([^\\n]+?)\\1|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|(?<!_)_([^_\\n]+)_(?!_)");
    private static final Pattern ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);|&(nbsp|amp|lt|gt|quot|apos);", Pattern.CASE_INSENSITIVE);

    private MedicalText() { }

    static String references(String text) {
        String linked = links(text == null ? "" : text, true);
        String codeMasked = CODE.matcher(linked).replaceAll(match -> Matcher.quoteReplacement(match.group(2).replaceAll("\\[(\\d+)]", "【$1】")));
        StringBuilder result = new StringBuilder(codeMasked);
        for (int index = 0; index < codeMasked.length(); index++) {
            if (codeMasked.charAt(index) == '[' && escaped(codeMasked, index)) result.setCharAt(index, '【');
        }
        return result.toString();
    }

    static String visible(String text) {
        String result = links(text == null ? "" : text, false);
        result = CODE.matcher(result).replaceAll("$2");
        for (int depth = 0; depth < 4; depth++) {
            String prior = result;
            result = EMPHASIS.matcher(result).replaceAll(match -> {
                String body = match.group(2) != null ? match.group(2) : match.group(3) != null ? match.group(3) : match.group(4);
                return Matcher.quoteReplacement(body);
            });
            if (prior.equals(result)) break;
        }
        result = ENTITY.matcher(result).replaceAll(match -> Matcher.quoteReplacement(entity(match.group())));
        return Normalizer.normalize(result, Normalizer.Form.NFKC).replaceAll("\\p{Cf}", "");
    }

    private static String links(String text, boolean referenceMode) {
        int[] brackets = pairs(text, '[', ']');
        int[] parentheses = pairs(text, '(', ')');
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            int end = text.charAt(index) == '[' && !escaped(text, index) ? brackets[index] : -1;
            if (end >= 0 && end + 1 < text.length() && text.charAt(end + 1) == '(' && parentheses[end + 1] >= 0) {
                String label = text.substring(index + 1, end);
                boolean image = index > 0 && text.charAt(index - 1) == '!' && !escaped(text, index - 1);
                if (image && !result.isEmpty()) result.setLength(result.length() - 1);
                if (referenceMode && image) label = label.replaceAll("\\[(\\d+)]", "【$1】");
                result.append(referenceMode && label.matches("\\d+") && !image ? "[" + label + "]" : label);
                index = parentheses[end + 1];
            } else result.append(text.charAt(index));
        }
        return result.toString();
    }

    private static int[] pairs(String text, char opening, char closing) {
        int[] result = new int[text.length()];
        java.util.Arrays.fill(result, -1);
        var stack = new java.util.ArrayDeque<Integer>();
        int backslashes = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if ((backslashes & 1) == 0) {
                if (value == opening) stack.push(index);
                else if (value == closing && !stack.isEmpty()) result[stack.pop()] = index;
            }
            backslashes = value == '\\' ? backslashes + 1 : 0;
        }
        return result;
    }

    private static boolean escaped(String text, int index) {
        int count = 0;
        while (--index >= 0 && text.charAt(index) == '\\') count++;
        return (count & 1) != 0;
    }

    private static String entity(String text) {
        String key = text.substring(1, text.length() - 1).toLowerCase(java.util.Locale.ROOT);
        if (key.startsWith("#")) {
            try {
                int value = key.startsWith("#x") ? Integer.parseInt(key.substring(2), 16) : Integer.parseInt(key.substring(1));
                return Character.isValidCodePoint(value) && !(value >= 0xd800 && value <= 0xdfff) ? new String(Character.toChars(value)) : text;
            } catch (NumberFormatException invalid) { return text; }
        }
        return switch (key) { case "nbsp" -> " "; case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">";
            case "quot" -> "\""; case "apos" -> "'"; default -> text; };
    }
}
