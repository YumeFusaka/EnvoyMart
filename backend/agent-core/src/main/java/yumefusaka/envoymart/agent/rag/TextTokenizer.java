package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 轻量文本分词器。
 * <p>
 * 中日韩字符按二元组（bigram）切分——与 Lucene CJKAnalyzer 同策略，
 * 不需要词典就能让中文查询命中；拉丁字母与数字按非字母数字边界切分。
 * 生产环境可替换为 IK / jieba 等带词典的分词器。
 */
public final class TextTokenizer {

    private TextTokenizer() {
    }

    /** 切分文本，返回按出现顺序去重后的词元列表。 */
    public static List<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (isCjk(c)) {
                flushAscii(ascii, tokens);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, tokens);
                ascii.append(c);
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);

        return new ArrayList<>(tokens);
    }

    private static void flushAscii(StringBuilder buffer, Set<String> tokens) {
        if (!buffer.isEmpty()) {
            tokens.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    private static void flushCjk(StringBuilder buffer, Set<String> tokens) {
        int len = buffer.length();
        if (len == 1) {
            tokens.add(buffer.toString());
        } else if (len > 1) {
            for (int i = 0; i + 1 < len; i++) {
                tokens.add(buffer.substring(i, i + 2));
            }
        }
        buffer.setLength(0);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)     // 中日韩统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF) // 扩展 A
                || (c >= 0x3040 && c <= 0x30FF) // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF); // 韩文
    }
}
