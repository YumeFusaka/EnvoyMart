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

    /**
     * 单字通道 —— 与 {@link #tokenize} 并行的第二条词法通道，只切中文单字。
     * <p>
     * 二元组在跨词界的查询上有系统性盲区：「钙片」的二元组就是「钙片」本身，
     * 而文档里写的是「碳酸钙 D3 咀嚼片」——它含「碳酸」「酸钙」「咀嚼」「嚼片」，
     * 偏偏没有连续的「钙片」，于是 BM25 得 0，词法路整条空掉。
     * 单字通道把「钙」「片」各自召回，就补上了这个跨词界盲区。
     * <p>
     * <b>为什么不直接改 {@link #tokenize}</b>：二元组是主通道，改它等于同时改掉
     * 所有既有词频、文档频率与评测基线（RetrievalQualityTest 的历史数字全部失去可比性）。
     * 把单字做成独立通道，两者各自的统计互不污染，融合时再按权重合并——
     * 出问题时可以单独关掉单字通道而不动主路。
     * <p>
     * 长连续中文串（如「碳酸钙」）在这里产出的是每一个单字而不是整词，
     * 因为词典化的整词切分需要词典，而这层刻意不做词典依赖。
     *
     * @return 按出现顺序去重后的中文字符列表；非中文内容一律丢弃
     */
    public static List<String> unigrams(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (isCjk(c)) {
                tokens.add(String.valueOf(c));
            }
        }
        return new ArrayList<>(tokens);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)     // 中日韩统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF) // 扩展 A
                || (c >= 0x3040 && c <= 0x30FF) // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF); // 韩文
    }
}
