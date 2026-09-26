package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 定长滑动窗口切分 —— 按字符数硬切，不看标点也不看结构。
 * <p>
 * 这是 {@code SimpleRAGEngine} 原有的实现，行为一字未改，抽出来是为了能与
 * {@link StructuralSplitter} 在同一套指标下对比。
 * <p>
 * <b>它的行为边界很明确</b>：文档短于窗口时每篇恰好一片，看不出问题；一旦超过窗口，
 * 切点就落在任意字符上。实测在 800~1100 字的长文档上，96% 的切片不以标点收尾，
 * 且关键条款会被切散（见 {@code ChunkingQualityTest} 的基线）。
 */
public class FixedSizeSplitter implements TextSplitter {

    private final int chunkSize;
    private final int chunkOverlap;

    public FixedSizeSplitter(int chunkSize, int chunkOverlap) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    @Override
    public List<DocumentChunk> split(Document doc) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String text = doc.getContent();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(DocumentChunk.builder()
                    .chunkId(doc.getId() + "_" + index)
                    .docId(doc.getId())
                    .content(text.substring(start, end))
                    .chunkIndex(index++)
                    .build());
            start += chunkSize - chunkOverlap;
        }
        return chunks;
    }
}
