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
import yumefusaka.envoymart.knowledgeservice.model.DocumentUpsertRequest;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeDocProductEntity;
import yumefusaka.envoymart.knowledgeservice.service.KnowledgeDocBindingService;
import yumefusaka.envoymart.knowledgeservice.service.KnowledgeDocumentService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {


    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    /** 商品-文档绑定。上传商品文档时把「这篇讲的是哪个商品」落进关联表 */
    private final KnowledgeDocBindingService bindingService;
    /** 与 ai-service 建索引时用的是同一个实现、同一组参数，见 StructuralSplitter.standard() */
    private final TextSplitter splitter = StructuralSplitter.standard();

    /**
     * 允许的来源与领域取值。
     * <p>
     * 这些不是「随便填个字符串」，它们会进到文档记录里、被检索侧按 scope 做领域筛选、
     * 被图谱侧按来源判断权威度。放进一个自由文本字段的后果是同一个领域出现
     * {@code nutrition} / {@code Nutrition} / {@code 营养} 三种写法，
     * 而筛选是等值匹配——三种写法谁也筛不到谁。
     */
    private static final java.util.Set<String> SOURCES =
            java.util.Set.of("manual", "policy", "regulation", "spec", "guide");
    private static final java.util.Set<String> SCOPES =
            java.util.Set.of("nutrition", "after_sale", "logistics", "payment",
                    "promotion", "food_safety");

    public KnowledgeDocumentServiceImpl(KnowledgeDocumentMapper documentMapper,
                                        KnowledgeChunkMapper chunkMapper,
                                        KnowledgeDocBindingService bindingService) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.bindingService = bindingService;
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
        List<KnowledgeDocumentEntity> docs = documentMapper.selectList(
                Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                        .eq(KnowledgeDocumentEntity::getStatus, 1)
                        .orderByAsc(KnowledgeDocumentEntity::getDocNo));
        // 一次把全部 SUBJECT 关联查出来再分组，避免逐篇查（N+1）。
        // 这份映射随语料下发给 ai-service，供图谱构建期确定性地补出「商品→成分」这条边
        Map<String, List<Long>> subjectsByDoc = bindingService.all().stream()
                .filter(r -> KnowledgeDocProductEntity.Role.SUBJECT.name().equals(r.getRole()))
                .collect(Collectors.groupingBy(KnowledgeDocProductEntity::getDocNo,
                        Collectors.mapping(KnowledgeDocProductEntity::getSpuId, Collectors.toList())));
        return docs.stream().map(doc -> toPayload(doc, subjectsByDoc)).toList();
    }

    // ==================== 种子导入 ====================

    @Override
    @Transactional
    public List<String> seed() {
        List<Document> seeds = CorpusLoader.load();
        List<String> touched = new ArrayList<>();
        for (Document seed : seeds) {
            if (upsertInternal(seed, true)) {
                touched.add(seed.getId());
            }
        }

        if (touched.isEmpty()) {
            log.info("[Knowledge] 种子语料 {} 篇，与库中一致，无需重建", seeds.size());
        } else {
            log.info("[Knowledge] 种子语料 {} 篇，本次重建 {} 篇：{}", seeds.size(), touched.size(), touched);
        }
        return touched;
    }

    @Override
    @Transactional
    public String upsert(DocumentUpsertRequest request) {
        Document document = toDocument(request);
        Document stored = document;
        if (stored.getId() == null || stored.getId().isBlank()) {
            stored = Document.builder()
                    .id(nextDocNo())
                    .title(document.getTitle())
                    .source(document.getSource())
                    .scope(document.getScope())
                    .version(document.getVersion())
                    .tags(document.getTags())
                    .content(document.getContent())
                    .build();
        }
        upsertInternal(stored, false);
        // 归属声明必须在文档落库之后：先有文档行，才谈得上它属于谁。
        // 整体替换这一篇的 SUBJECT 集合——运营编辑文档时可能改了归属，
        // 不替换的话旧归属会留着，商品下架时会连坐一篇已经不相关了的文档
        bindingService.declareSubjects(stored.getId(), request.getSubjectSpuIds(),
                KnowledgeDocProductEntity.MatchedBy.MANUAL);
        return stored.getId();
    }

    @Override
    @Transactional
    public void changeStatus(String docNo, int status, String disabledBy) {
        if (status != 0 && status != 1) {
            throw new IllegalArgumentException("status 只能是 0（停用）或 1（启用）");
        }
        KnowledgeDocumentEntity doc = requireDocument(docNo);
        // 启用时把原因清掉：disabledBy 描述的是「这一次为什么停用」，
        // 文档已经是启用的状态还留着一个停用原因，会让「谁停用了它」查出来是错的。
        //
        // 这里必须走 update + set，不能 updateById 传一个字段为 null 的实体：
        // MyBatis-Plus 默认的字段策略是 NOT_NULL，null 字段**不会**进 SQL，
        // 于是「上架恢复」会只改 status、把 PRODUCT_OFF 留在库里——
        // 症状是文档明明启用了，却仍被标成「因商品下架而停用」，下一次上架判据读到的原因是错的
        String reason = status == 1 ? null : normalizeDisabledBy(disabledBy);
        documentMapper.update(null, Wrappers.<KnowledgeDocumentEntity>lambdaUpdate()
                .eq(KnowledgeDocumentEntity::getId, doc.getId())
                .set(KnowledgeDocumentEntity::getStatus, status)
                .set(KnowledgeDocumentEntity::getDisabledBy, reason)
                .set(KnowledgeDocumentEntity::getUpdatedAt, LocalDateTime.now()));
        log.info("[Knowledge] 文档 {} 状态改为 {}（原因 {}）", docNo,
                status == 1 ? "启用" : "停用", reason == null ? "-" : reason);
    }

    /**
     * 停用原因的取值收口。
     * <p>
     * 未指定一律记为 {@code MANUAL}：默认值必须落在「最保守」的那一侧——
     * 记成 MANUAL 时商品上架不会自动恢复它，需要有人再看一眼；
     * 记成 PRODUCT_OFF 则相反，一个来源不明的停用会被商品的动作悄悄打开。
     */
    private static String normalizeDisabledBy(String disabledBy) {
        return DISABLED_BY_PRODUCT_OFF.equalsIgnoreCase(disabledBy) ? DISABLED_BY_PRODUCT_OFF : DISABLED_BY_MANUAL;
    }

    @Override
    public int statusOf(String docNo) {
        return requireDocument(docNo).getStatus();
    }

    @Override
    public String disabledByOf(String docNo) {
        return requireDocument(docNo).getDisabledBy();
    }

    /**
     * 落库并（必要时）重切一篇文档。
     *
     * @param seedMode 种子导入模式：文档编号必须来自种子，缺失即报错。
     *                 管理端上传走 false，此时编号已由调用方分配好
     * @return 是否真的发生了新建或重切
     */
    private boolean upsertInternal(Document seed, boolean seedMode) {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocumentEntity existing = documentMapper.selectOne(Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                .eq(KnowledgeDocumentEntity::getDocNo, seed.getId()));

        KnowledgeDocumentEntity doc;
        boolean rowChanged;
        if (existing == null) {
            // 守卫的判据是「种子文档有没有带编号」，不是「库里有没有这一篇」。
            // 原先写在 existing == null 分支里，于是**每一篇新增的种子文档都会撞上它**——
            // 而「新增一篇种子」恰恰是这条链路最正常的用法。
            // 触发条件写错位置时，它平时不响（没人加过新文档），一响就报一个与真实原因无关的错
            if (seedMode && (seed.getId() == null || seed.getId().isBlank())) {
                throw new IllegalStateException("种子文档缺少编号：" + seed.getTitle());
            }
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

        // 归属声明与文档行一起落库：语料 front-matter 里的 subjects 是**人工声明的事实**，
        // 不是模型推测。它必须在这里（种子/上传两条路都经过的地方）写入关联表，
        // 否则图谱构建期就没有依据去补「SPUx -CONTAINS-> 成分」那条确定性边，
        // 商品会在图上凭空消失。空声明的语义是「这篇文档不属于任何商品」，
        // 因此在种子模式下只对带 subjects 的文档做替换，避免误清人工在管理台改过的归属。
        if (seedMode && seed.getSubjectSpuIds() != null && !seed.getSubjectSpuIds().isEmpty()) {
            bindingService.declareSubjects(seed.getId(), seed.getSubjectSpuIds(),
                    KnowledgeDocProductEntity.MatchedBy.BACKFILL);
        }

        List<DocumentChunk> expected = splitter.split(toDocument(doc));
        // 文档行没变、切片却对不上，只有一种可能：切分参数改过了。
        // 这时必须重切——ai-service 每次启动都按**当前**参数重建索引，
        // 而这里若只看文档行，库里就会留着旧边界的切片。
        if (!rowChanged && chunksMatch(doc, expected)) {
            return false;
        }
        rebuildChunks(doc, expected);
        log.info("[Knowledge] {} {} 《{}》 切片 {} 片",
                seedMode ? "种子" + (existing == null ? "新建" : "更新") : (existing == null ? "上传新建" : "上传更新"),
                seed.getId(), seed.getTitle(), expected.size());
        return true;
    }

    /**
     * 分配下一个文档编号。
     * <p>
     * 取当前库里的最大编号顺延，而不是数行数——行数在删过文档之后会与编号错位，
     * 分配出一个已经被用过的号，撞在 {@code doc_no} 的唯一约束上。
     */
    private String nextDocNo() {
        String max = documentMapper.selectList(Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                        .select(KnowledgeDocumentEntity::getDocNo)
                        .orderByDesc(KnowledgeDocumentEntity::getDocNo)
                        .last("limit 1"))
                .stream().findFirst().map(KnowledgeDocumentEntity::getDocNo).orElse(null);
        int number = 1;
        if (max != null && max.matches("KB-\\d+")) {
            number = Integer.parseInt(max.substring(3)) + 1;
        }
        return "KB-%04d".formatted(number);
    }

    private static Document toDocument(DocumentUpsertRequest request) {
        // 校验不放 Bean Validation：取值清单是检索侧与图谱侧共用的契约，
        // 写在这里能让「哪些值合法」与「谁在用这些值」待在同一个文件里。
        //
        // 与 FrontMatterParser 同一条纪律：不合法就抛，不补默认值。
        // 少一个 title 的后果不是「这篇文档差一点」，而是它被引用时用户看到
        // 「依据：《null》」——错在源头、却在几屏之外的对话里现形，中间没有任何一处会报错。
        String title = requireText(request.getTitle(), "标题");
        String content = requireText(request.getContent(), "正文");
        String source = requireOneOf(request.getSource(), SOURCES, "来源(source)");
        String scope = requireOneOf(request.getScope(), SCOPES, "领域(scope)");
        String docNo = request.getDocNo();
        if (docNo != null && !docNo.isBlank() && !docNo.matches("KB-\\d{4,}")) {
            throw new IllegalArgumentException("文档编号格式应为 KB-0001：" + docNo);
        }
        return Document.builder()
                .id(docNo == null || docNo.isBlank() ? null : docNo.strip())
                .title(title)
                .source(source)
                .scope(scope)
                .version(request.getVersion() == null || request.getVersion().isBlank()
                        ? "v1" : request.getVersion().strip())
                .tags(request.getTags())
                .content(content)
                .build();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.strip();
    }

    private static String requireOneOf(String value, java.util.Set<String> allowed, String label) {
        String stripped = requireText(value, label);
        if (!allowed.contains(stripped)) {
            throw new IllegalArgumentException(
                    "%s 取值不合法：%s；允许 %s".formatted(label, stripped, allowed));
        }
        return stripped;
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

    private static KnowledgeDocumentPayload toPayload(KnowledgeDocumentEntity doc,
                                                      Map<String, List<Long>> subjectsByDoc) {
        return KnowledgeDocumentPayload.builder()
                .docNo(doc.getDocNo())
                .title(doc.getTitle())
                .source(doc.getSource())
                .scope(doc.getScope())
                .version(doc.getVersion())
                .tags(doc.getTags() == null || doc.getTags().isBlank()
                        ? List.of() : List.of(doc.getTags().split(",")))
                .content(doc.getContent())
                .subjectSpuIds(subjectsByDoc.getOrDefault(doc.getDocNo(), List.of()))
                .build();
    }
}
