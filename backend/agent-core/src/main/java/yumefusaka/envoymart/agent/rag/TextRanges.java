package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 按字符区间从答案里抠掉片段，并清理留下的排版残骸。
 * <p>
 * 被抠掉的可能是句子里的一个引用角标，也可能是整整一句话或一个段落——两者的善后
 * 是同一件事：删完会留下孤零零的列表符号（{@code - }、{@code 1. }）与连排空行，
 * 不清理的话答案里到处是短横线。抽成一处是因为抠的人有两个
 * （{@link CitationVerifier} 抠无出处的句子，{@link ConflictReporter} 抠冲突段落），
 * 而「删完长什么样」必须是同一个答案。
 * <p>
 * 用区间而不是字符串替换：同一句话在答案里可能出现两次，按文本找会删错那一处。
 */
final class TextRanges {

    private TextRanges() {
    }

    /**
     * @param ranges 待删区间 {@code [start, end)}，无需有序；重叠部分只删一次
     */
    static String delete(String text, List<int[]> ranges) {
        if (text == null || text.isEmpty() || ranges.isEmpty()) {
            return text;
        }
        List<int[]> sorted = ranges.stream()
                .sorted((a, b) -> Integer.compare(a[0], b[0]))
                .toList();
        StringBuilder sb = new StringBuilder(text.length());
        int cursor = 0;
        for (int[] range : sorted) {
            if (range[0] < cursor) {
                continue; // 落在上一段里了，已经删过
            }
            sb.append(text, cursor, range[0]);
            cursor = range[1];
        }
        sb.append(text, cursor, text.length());
        return tidy(sb.toString());
    }

    /**
     * 删完之后必做的排版清理。
     * <p>
     * 三件事：只剩列表符号的行（{@code - }、{@code 1. } 后面的句子被删了）、
     * 连排空行、以及标点前空出来的格。最后一条来自只摘编号不删句的情形——
     * 「上限 4000IU [7]。」摘掉 {@code [7]} 后会留下一个悬空的空格。
     * 中文排版里标点前本来就不该有空格。
     */
    static String tidy(String text) {
        return text.replaceAll("(?m)^[ \t>*•-]+$", "")
                .replaceAll("\n{3,}", "\n\n")
                .replaceAll("[ \t]+([，。、；：！？）」】])", "$1")
                .strip();
    }
}
