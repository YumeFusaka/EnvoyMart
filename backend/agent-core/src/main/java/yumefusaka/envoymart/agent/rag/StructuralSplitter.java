package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 结构感知的分层切分器 —— 先按文档自身的结构单元切，超限才逐级下钻。
 * <p>
 * 与 {@link FixedSizeSplitter} 的根本区别是：<b>结构边界优先于大小</b>。
 * 大小只是"触发下钻"的阈值，不是切分目标——一条 400 字的条款如果有完整语义，
 * 就让它 400 字。<b>长一点不会答错，切断了才会答错。</b>
 * <p>
 * 下钻顺序：章 → 条 → 段 → 句 → 字符（兜底，且回退到最近的标点）。
 * 过小的块会与相邻块合并，避免出现十几字符的碎片白占 topK 名额。
 * <p>
 * 每个切片前面拼上它在文档中的位置（{@code 《文档》 > 章 > 条}），
 * 让切片脱离原文后仍能自证语境——代价是零，因为位置信息本就来自文档结构，
 * 不需要像 Contextual Retrieval 那样跑模型生成。
 */
public class StructuralSplitter implements TextSplitter {

    /** 章标题：`一、` / `第二章` 起头的整行 */
    private static final Pattern CHAPTER_HEADING =
            Pattern.compile("^\\s*(?:第[一二三四五六七八九十百]+章|[一二三四五六七八九十]+、)\\s*\\S.*$");

    /** 条款起头：`第五条` / `3.1` / `1.2.3` 起头的整行 */
    private static final Pattern CLAUSE_HEADING =
            Pattern.compile("^\\s*(?:第[一二三四五六七八九十百]+条|\\d+(?:\\.\\d+)+)\\s*\\S?.*$");

    /** 句末标点 —— 下钻到句级时的切点，也是字符兜底时的回退目标 */
    private static final String SENTENCE_ENDS = "。！？；";

    /** 超过这个长度就往下钻一层 */
    private final int maxChars;
    /** 低于这个长度就尝试与相邻块合并 */
    private final int minChars;
    /** 是否在切片前拼上文档位置 */
    private final boolean withPath;

    public StructuralSplitter(int maxChars, int minChars) {
        this(maxChars, minChars, true);
    }

    public StructuralSplitter(int maxChars, int minChars, boolean withPath) {
        this.maxChars = maxChars;
        this.minChars = minChars;
        this.withPath = withPath;
    }

    @Override
    public List<DocumentChunk> split(Document doc) {
        String text = doc.getContent();
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        // 1. 按最外层的结构单元（章 / 条）切块，并记下每块的所属位置
        List<Block> blocks = parseBlocks(text);

        // 2. 超限的块逐级下钻
        List<Block> split = new ArrayList<>();
        for (Block block : blocks) {
            split.addAll(drillDown(block));
        }

        // 3. 过小的块与相邻块合并，避免碎片
        List<Block> merged = mergeSmall(split);

        // 4. 拼路径前缀并转成切片
        return toChunks(merged, doc);
    }

    // ==================== 结构解析 ====================

    /** 一个结构块：内容 + 它在文档里的位置。 */
    private record Block(String chapter, String clause, String text) {
    }

    /**
     * 按行的结构标记切块。章的归属会一直沿用，直到遇到下一个章；
     * 条的归属同理。这样每个块都知道自己属于哪个章节。
     */
    private List<Block> parseBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        String chapter = null;
        String clause = null;
        StringBuilder buf = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            if (CHAPTER_HEADING.matcher(line).matches()) {
                flush(blocks, buf, chapter, clause);
                chapter = line.strip();
                clause = null;
            } else if (CLAUSE_HEADING.matcher(line).matches()) {
                flush(blocks, buf, chapter, clause);
                clause = line.strip();
            }
            buf.append(line).append('\n');
        }
        flush(blocks, buf, chapter, clause);
        return blocks;
    }

    private void flush(List<Block> blocks, StringBuilder buf, String chapter, String clause) {
        String text = buf.toString().strip();
        if (!text.isEmpty()) {
            blocks.add(new Block(chapter, clause, text));
        }
        buf.setLength(0);
    }

    // ==================== 逐级下钻 ====================

    /** 块不超限就原样保留；超限则依次尝试按段落、按句子、按字符切。 */
    private List<Block> drillDown(Block block) {
        if (block.text().length() <= maxChars) {
            return List.of(block);
        }

        List<Block> result = new ArrayList<>();
        for (String paragraph : block.text().split("\n\\s*\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= maxChars) {
                result.add(new Block(block.chapter(), block.clause(), p));
                continue;
            }
            for (String piece : splitBySentence(p)) {
                result.add(new Block(block.chapter(), block.clause(), piece));
            }
        }
        return result.isEmpty() ? List.of(block) : result;
    }

    /**
     * 按句号切分并贪心合句，使每片尽量接近但不超上限。
     * 单句本身就超上限时，退到按字符硬切——但仍优先落在标点上。
     */
    private List<String> splitBySentence(String text) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0) {
                sentences.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            sentences.add(text.substring(start));
        }

        List<String> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.length() > maxChars) {
                // 极长的单句：先把已攒的吐出去，再对它做回退式硬切
                if (cur.length() > 0) {
                    pieces.add(cur.toString());
                    cur.setLength(0);
                }
                pieces.addAll(hardSplit(sentence));
                continue;
            }
            if (cur.length() > 0 && cur.length() + sentence.length() > maxChars) {
                pieces.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(sentence);
        }
        if (cur.length() > 0) {
            pieces.add(cur.toString());
        }
        return pieces;
    }

    /**
     * 字符级兜底：按上限硬切，但每刀都回退到最近的标点。
     * 找不到标点（例如一整段没有句读）时才接受硬切——这是最后一道防线，
     * 不应在正常文档上触发。
     */
    private List<String> hardSplit(String text) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int retreat = -1;
                for (int i = end; i > start + maxChars / 2; i--) {
                    if (SENTENCE_ENDS.indexOf(text.charAt(i - 1)) >= 0) {
                        retreat = i;
                        break;
                    }
                }
                if (retreat > start) {
                    end = retreat;
                }
            }
            pieces.add(text.substring(start, end));
            start = end;
        }
        return pieces;
    }

    // ==================== 合并与输出 ====================

    /**
     * 合并过小的相邻块。只在同属一章时合并，且合并后不超过上限——
     * 目的是消掉碎片，不是把整篇压回去。
     */
    private List<Block> mergeSmall(List<Block> blocks) {
        List<Block> result = new ArrayList<>();
        for (Block block : blocks) {
            if (!result.isEmpty()) {
                Block last = result.get(result.size() - 1);
                boolean sameChapter = java.util.Objects.equals(last.chapter(), block.chapter());
                boolean tooSmall = last.text().length() < minChars || block.text().length() < minChars;
                boolean fits = last.text().length() + block.text().length() + 1 <= maxChars;
                if (sameChapter && tooSmall && fits) {
                    result.set(result.size() - 1, new Block(last.chapter(), last.clause(),
                            last.text() + "\n" + block.text()));
                    continue;
                }
            }
            result.add(block);
        }
        return result;
    }

    private List<DocumentChunk> toChunks(List<Block> blocks, Document doc) {
        List<DocumentChunk> chunks = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            String content = withPath ? pathOf(doc, block) + "\n" + block.text() : block.text();
            chunks.add(DocumentChunk.builder()
                    .chunkId(doc.getId() + "_" + i)
                    .docId(doc.getId())
                    .content(content)
                    .chunkIndex(i)
                    .build());
        }
        return chunks;
    }

    /** 切片的位置前缀：{@code 《文档标题》 > 章 > 条}，缺哪层就少哪层。 */
    private String pathOf(Document doc, Block block) {
        StringBuilder sb = new StringBuilder("《").append(doc.getTitle()).append("》");
        if (block.chapter() != null) {
            sb.append(" > ").append(block.chapter());
        }
        if (block.clause() != null && !block.clause().equals(block.chapter())) {
            sb.append(" > ").append(block.clause());
        }
        return sb.toString();
    }
}
