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

    /** 超过这个长度的行不可能是标题，只是恰好以冒号收尾的正文 */
    private static final int HEADING_MAX_CHARS = 30;

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
     * 四件事：只剩列表符号的行（{@code - }、{@code 1. } 后面的句子被删了）、
     * 连排空行、标点前空出来的格、以及下面已经没内容的标题。
     * 第三条来自只摘编号不删句的情形——「上限 4000IU [7]。」摘掉 {@code [7]}
     * 后会留下一个悬空的空格，中文排版里标点前本来就不该有空格。
     */
    static String tidy(String text) {
        String cleaned = text.replaceAll("(?m)^[ \t>*•-]+$", "")
                .replaceAll("\n{3,}", "\n\n")
                .replaceAll("[ \t]+([，。、；：！？）」】])", "$1")
                .strip();
        return dropOrphanHeadings(cleaned);
    }

    /**
     * 删掉「下面已经什么都没有了」的标题行。
     * <p>
     * 标题行本身不含断言（{@code ⚠️ 注意：}），它永远不违规，所以删句子时它会被留下——
     * 而它唯一的正文被抠掉了，用户看到的是一个标题下面直接跟着另一个标题。
     * 留着它比留着那句没出处的话更糟：读者会以为平台"确实有这么一段注意事项"，
     * 只是内容没渲染出来。
     * <p>
     * 判据是「是标题」+「本块内没有正文」。块从标题行开始、到下一个标题行为止——
     * 所以「⚠️ 注意：」下面即使紧跟「✅ 建议：」和它的内容，仍然算空块：
     * 那些内容属于后者。
     */
    private static String dropOrphanHeadings(String text) {
        String[] lines = text.split("\n", -1);
        // 从后往前扫：删掉一个孤儿标题后，它上面的标题也可能随之变成孤儿
        boolean contentBelow = false;
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!isHeading(line)) {
                contentBelow = true;
                continue;
            }
            if (!contentBelow) {
                lines[i] = null;
            }
            // 换块了：本行无论留不留，它下面的内容都不属于上面那个块
            contentBelow = false;
        }

        StringBuilder sb = new StringBuilder(text.length());
        for (String line : lines) {
            if (line != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().strip();
    }

    /** 标题行：很短，且以冒号收尾（{@code ⚠️ 注意：}、{@code **注意事项：**}）或是 Markdown 标题 */
    private static boolean isHeading(String line) {
        if (line.startsWith("#")) {
            return true;
        }
        if (line.length() > HEADING_MAX_CHARS) {
            return false;
        }
        String core = line.replaceAll("[*_\\s]+$", "");
        return core.endsWith("：") || core.endsWith(":");
    }
}
