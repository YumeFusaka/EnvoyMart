package yumefusaka.envoymart.knowledgeservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.StructuralSplitter;
import yumefusaka.envoymart.agent.rag.TextSplitter;
import yumefusaka.envoymart.contract.KnowledgeDocumentPayload;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeChunkEntity;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeDocumentEntity;
import yumefusaka.envoymart.knowledgeservice.knowledge.CorpusLoader;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeChunkMapper;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeDocumentMapper;
import yumefusaka.envoymart.knowledgeservice.model.ChunkDetail;
import yumefusaka.envoymart.knowledgeservice.model.ChunkRef;
import yumefusaka.envoymart.knowledgeservice.model.DocumentDetail;
import yumefusaka.envoymart.knowledgeservice.model.DocumentSummary;
import yumefusaka.envoymart.knowledgeservice.service.KnowledgeDocumentService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    /** 与 ai-service 建索引时用的是同一个实现、同一组参数，见 StructuralSplitter.standard() */
    private final TextSplitter splitter = StructuralSplitter.standard();

    public KnowledgeDocumentServiceImpl(KnowledgeDocumentMapper documentMapper,
                                        KnowledgeChunkMapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @Override
    public List<DocumentSummary> list(String scope, String keyword, Integer status) {
        List<KnowledgeDocumentEntity> documents = documentMapper.selectList(
                Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                        .eq(scope != null && !scope.isBlank(), KnowledgeDocumentEntity::getScope, scope)
                        .eq(status != null, KnowledgeDocumentEntity::getStatus, status)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(KnowledgeDocumentEntity::getTitle, keyword)
                                .or().like(KnowledgeDocumentEntity::getTags, keyword))
                        .orderByAsc(KnowledgeDocumentEntity::getDocNo));

        return documents.stream().map(doc -> DocumentSummary.builder()
                .docNo(doc.getDocNo())
                .title(doc.getTitle())
                .source(doc.getSource())
                .scope(doc.getScope())
                .version(doc.getVersion())
                .tags(doc.getTags())
                .status(doc.getStatus())
                // 列表里的片数是给管理台看的「这篇切成了几片」。
                // 逐篇 count 会 N+1，一次查出来按 docId 分组
                .chunkCount(chunkCounts().getOrDefault(doc.getId(), 0))
                .contentLength(doc.getContent() == null ? 0 : doc.getContent().length())
                .updatedAt(doc.getUpdatedAt())
                .build()).toList();
    }

    private java.util.Map<Long, Integer> chunkCounts() {
        return chunkMapper.selectList(Wrappers.<KnowledgeChunkEntity>lambdaQuery()
                        .select(KnowledgeChunkEntity::getDocId))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        KnowledgeChunkEntity::getDocId,
                        java.util.stream.Collectors.summingInt(c -> 1)));
    }

    @Override
    public DocumentDetail detail(String docNo) {
        KnowledgeDocumentEntity doc = requireDocument(docNo);
        List<KnowledgeChunkEntity> chunks = chunksOf(doc.getId());

        return DocumentDetail.builder()
                .docNo(doc.getDocNo())
                .title(doc.getTitle())
                .source(doc.getSource())
                .scope(doc.getScope())
                .version(doc.getVersion())
                .tags(doc.getTags())
                .status(doc.getStatus())
                .content(doc.getContent())
                .updatedAt(doc.getUpdatedAt())
                .chunks(toRefs(chunks, doc.getContent()))
                .build();
    }

    @Override
    public ChunkDetail chunk(String chunkId) {
        KnowledgeChunkEntity chunk = chunkMapper.selectOne(Wrappers.<KnowledgeChunkEntity>lambdaQuery()
                .eq(KnowledgeChunkEntity::getChunkId, chunkId));
        if (chunk == null) {
            throw new IllegalArgumentException("切片不存在：" + chunkId
                    + "。它可能来自一次已经作废的索引——知识库重建过之后，旧编号就不再有效");
        }
        KnowledgeDocumentEntity doc = documentMapper.selectById(chunk.getDocId());
        if (doc == null) {
            // 切片在、文档没了：外键没建约束，说明是直接删了主表。宁可报错也不要回一个半截的引用
            throw new IllegalStateException("切片 " + chunkId + " 指向的文档不存在（docId=" + chunk.getDocId() + "）");
        }

        List<KnowledgeChunkEntity> siblings = chunksOf(doc.getId());
        ChunkRef ref = toRefs(siblings, doc.getContent()).stream()
                .filter(r -> chunkId.equals(r.getChunkId()))
                .findFirst().orElse(null);

        return ChunkDetail.builder()
                .chunkId(chunk.getChunkId())
                .chunkIndex(chunk.getChunkIndex())
                .content(chunk.getContent())
                .position(chunk.getPosition())
                .charOffset(chunk.getCharOffset())
                .charEnd(ref == null ? null : ref.getCharEnd())
                .docNo(doc.getDocNo())
                .title(doc.getTitle())
                .source(doc.getSource())
                .scope(doc.getScope())
                .version(doc.getVersion())
                .status(doc.getStatus())
                .documentContent(doc.getContent())
                .build();
    }

    @Override
    public List<KnowledgeDocumentPayload> corpus() {
        return documentMapper.selectList(Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                        .eq(KnowledgeDocumentEntity::getStatus, 1)
                        .orderByAsc(KnowledgeDocumentEntity::getDocNo))
                .stream().map(KnowledgeDocumentServiceImpl::toPayload).toList();
    }

    // ==================== 种子导入 ====================

    @Override
    @Transactional
    public List<String> seed() {
        List<Document> seeds = CorpusLoader.load();
        List<String> touched = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Document seed : seeds) {
            KnowledgeDocumentEntity existing = documentMapper.selectOne(Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                    .eq(KnowledgeDocumentEntity::getDocNo, seed.getId()));

            KnowledgeDocumentEntity doc;
            boolean rowChanged;
            if (existing == null) {
                KnowledgeDocumentEntity created = new KnowledgeDocumentEntity();
                created.setDocNo(seed.getId());
                apply(created, seed);
                created.setStatus(1);
                created.setCreatedAt(now);
                created.setUpdatedAt(now);
                documentMapper.insert(created);
                doc = created;
                rowChanged = true;
            } else {
                KnowledgeDocumentEntity updated = new KnowledgeDocumentEntity();
                updated.setDocNo(seed.getId());
                apply(updated, seed);
                updated.setUpdatedAt(now);
                // 判断「要不要重切」交给数据库：先查出来比对再写回是读-改-写，两个实例同时启动会各写一次
                rowChanged = documentMapper.updateIfChanged(updated) > 0;
                doc = rowChanged ? requireDocument(seed.getId()) : existing;
            }

            List<DocumentChunk> expected = splitter.split(toDocument(doc));
            // 文档行没变、切片却对不上，只有一种可能：切分参数改过了。
            // 这时必须重切——ai-service 每次启动都按**当前**参数重建索引，
            // 而这里若只看文档行，库里就会留着旧边界的切片。
            if (!rowChanged && chunksMatch(doc, expected)) {
                continue;
            }
            rebuildChunks(doc, expected);
            touched.add(seed.getId());
            log.info("[Knowledge] 种子{} {} 《{}》 切片 {} 片",
                    existing == null ? "新建" : "更新", seed.getId(), seed.getTitle(), expected.size());
        }

        if (touched.isEmpty()) {
            log.info("[Knowledge] 种子语料 {} 篇，与库中一致，无需重建", seeds.size());
        } else {
            log.info("[Knowledge] 种子语料 {} 篇，本次重建 {} 篇：{}", seeds.size(), touched.size(), touched);
        }
        return touched;
    }

    private static void apply(KnowledgeDocumentEntity target, Document seed) {
        target.setTitle(seed.getTitle());
        target.setSource(seed.getSource());
        target.setScope(seed.getScope());
        target.setVersion(seed.getVersion());
        target.setTags(seed.getTags() == null ? null : String.join(",", seed.getTags()));
        target.setContent(seed.getContent());
    }

    /**
     * 库里的切片，是不是<b>当前这套切分参数</b>产出的那一套。
     * <p>
     * 只比 chunkId 不够：编号是 {@code 文档号_序号}，参数微调后片数可能不变、
     * 边界却已经挪了，编号看上去完全对得上。要比到正文才能发现
     * 「第 3 片已经不是原来那段字了」。
     * <p>
     * 这条检查是种子幂等性的<b>前提</b>：ai-service 每次启动都按当前参数重新切片建索引，
     * knowledge-service 只在文档行变化时重切。少了它，改了 {@code standard()} 的参数就会出现
     * 「检索命中的是新的第 3 片、点开引用拿到的是旧的第 3 片」——两边日志都正常。
     */
    private boolean chunksMatch(KnowledgeDocumentEntity doc, List<DocumentChunk> expected) {
        List<KnowledgeChunkEntity> stored = chunksOf(doc.getId());
        if (stored.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < stored.size(); i++) {
            KnowledgeChunkEntity row = stored.get(i);
            DocumentChunk want = expected.get(i);
            if (!java.util.Objects.equals(row.getChunkId(), want.getChunkId())
                    || !java.util.Objects.equals(row.getContent(), want.getContent())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 重切一篇文档的切片。
     * <p>
     * 先删后插而不是逐片 upsert：切分参数一变，片数与边界都会变，
     * 「第 3 片」在新旧两版里根本不是同一段字。逐片对齐会留下半新半旧的切片，
     * 而这种残留不会报错，只会让引用指向一段风马牛不相及的文字。
     * <p>
     * 切片由调用方传入而不是在这里再切一次：判断「要不要重切」本来就得先切一遍，
     * 再切一次等于同一份文档切两遍，两份结果还得能对上。
     */
    private void rebuildChunks(KnowledgeDocumentEntity doc, List<DocumentChunk> chunks) {
        chunkMapper.delete(Wrappers.<KnowledgeChunkEntity>lambdaQuery()
                .eq(KnowledgeChunkEntity::getDocId, doc.getId()));

        LocalDateTime now = LocalDateTime.now();
        for (DocumentChunk chunk : chunks) {
            KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
            entity.setDocId(doc.getId());
            entity.setChunkId(chunk.getChunkId());
            entity.setChunkIndex(chunk.getChunkIndex());
            entity.setContent(chunk.getContent());
            entity.setPosition(chunk.getPosition());
            entity.setCharOffset(chunk.getCharOffset());
            entity.setCreatedAt(now);
            chunkMapper.insert(entity);
        }
    }

    // ==================== 辅助 ====================

    private KnowledgeDocumentEntity requireDocument(String docNo) {
        KnowledgeDocumentEntity doc = documentMapper.selectOne(Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                .eq(KnowledgeDocumentEntity::getDocNo, docNo));
        if (doc == null) {
            throw new IllegalArgumentException("知识库中没有文档编号 " + docNo);
        }
        return doc;
    }

    private List<KnowledgeChunkEntity> chunksOf(Long docId) {
        return chunkMapper.selectList(Wrappers.<KnowledgeChunkEntity>lambdaQuery()
                .eq(KnowledgeChunkEntity::getDocId, docId)
                .orderByAsc(KnowledgeChunkEntity::getChunkIndex));
    }

    /**
     * 切片的原文区间。
     * <p>
     * 起点用库里存的 {@code charOffset}，终点用<b>下一片的起点</b>——不是本片正文长度。
     * 切片正文前面拼了位置前缀（{@code 《文档》 > 第三章}），那串字原文里没有，
     * 拿它当区间会多高亮一截。最后一片直到正文末尾。
     */
    private static List<ChunkRef> toRefs(List<KnowledgeChunkEntity> chunks, String content) {
        int docLength = content == null ? 0 : content.length();
        return chunks.stream().map(chunk -> {
            Integer start = chunk.getCharOffset();
            Integer end = null;
            if (start != null) {
                end = chunks.stream()
                        .map(KnowledgeChunkEntity::getCharOffset)
                        .filter(o -> o != null && o > start)
                        .min(Comparator.naturalOrder())
                        .orElse(docLength);
            }
            return ChunkRef.builder()
                    .chunkId(chunk.getChunkId())
                    .chunkIndex(chunk.getChunkIndex())
                    .position(chunk.getPosition())
                    .charOffset(start)
                    .charEnd(end)
                    .build();
        }).toList();
    }

    private static Document toDocument(KnowledgeDocumentEntity doc) {
        return Document.builder()
                .id(doc.getDocNo())
                .title(doc.getTitle())
                .source(doc.getSource())
                .scope(doc.getScope())
                .version(doc.getVersion())
                .content(doc.getContent())
                .build();
    }

    private static KnowledgeDocumentPayload toPayload(KnowledgeDocumentEntity doc) {
        return KnowledgeDocumentPayload.builder()
                .docNo(doc.getDocNo())
                .title(doc.getTitle())
                .source(doc.getSource())
                .scope(doc.getScope())
                .version(doc.getVersion())
                .tags(doc.getTags() == null || doc.getTags().isBlank()
                        ? List.of() : List.of(doc.getTags().split(",")))
                .content(doc.getContent())
                .build();
    }
}
