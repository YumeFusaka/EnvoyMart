package yumefusaka.envoymart.knowledgeservice.knowledge;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.StructuralSplitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 语料本身的质量契约。
 * <p>
 * 其它测试锁的是「代码把字段带下去了吗」，这一层锁的是「<b>带下去的东西对不对</b>」——
 * 语料是所有引用的源头，源头上少一个字段，下游补不回来。
 * <p>
 * 最要紧的一条是把<b>两个仓库间的隐形约定</b>变成会失败的用例：售后政策表的
 * {@code doc_ref} 写的是 {@code KB-000x}，它指向知识库文档。之前知识库的文档 id 是
 * {@code after_sale_1} 这类名字，两边从来没有对上过——政策引擎给出的「依据编号」
 * 点开是空的，而这条链路断在两份互不知道对方存在的文件之间，没有任何机制会发现。
 */
class KnowledgeCorpusTest {

    private static final StructuralSplitter SPLITTER = StructuralSplitter.standard();

    /** 语料根目录（测试工作目录是模块根 knowledge-service/） */
    private static final Path CORPUS_DIR = Path.of("src/main/resources/knowledge");

    private List<Document> corpus() {
        return CorpusLoader.load();
    }

    @Test
    void 语料装载成功且每篇都有完整的溯源元信息() {
        List<Document> documents = corpus();

        assertThat(documents).as("知识库不能为空").isNotEmpty();
        assertThat(documents).allSatisfy(doc -> {
            assertThat(doc.getId()).as("引用回跳要用 id").isNotBlank();
            assertThat(doc.getTitle()).as("引用要显示《标题》").isNotBlank();
            assertThat(doc.getSource()).as("来源决定这条依据的可信度").isNotBlank();
            assertThat(doc.getScope()).as("领域范围供前端分类").isNotBlank();
            assertThat(doc.getVersion()).as("引用必须能指明是哪一版").isNotBlank();
            assertThat(doc.getContent()).isNotBlank();
        });
    }

    @Test
    void 文档编号不重复() {
        List<String> ids = corpus().stream().map(Document::getId).toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    /**
     * 语料必须真的会产出多片。
     * <p>
     * 上一版语料每篇都是一段两百字以内的独立文本，结构分层切分在它上面<b>零收益</b>：
     * 每篇恰好一片，章条下钻与位置前缀都无从触发。「结构分层切分更好」这句话在那个语料上
     * 无法被验证，只能靠评测语料间接说明。语料换成长文后，这条链路才有可观察的行为。
     */
    @Test
    void 长文档被切成多片且每片带位置() {
        List<DocumentChunk> chunks = corpus().stream()
                .filter(doc -> "nutrition".equals(doc.getScope()) || "after_sale".equals(doc.getScope()))
                .flatMap(doc -> SPLITTER.split(doc).stream())
                .toList();

        assertThat(chunks).isNotEmpty();
        assertThat(chunks)
                .as("位置必须落到具体的章条，而不只是《标题》")
                .anySatisfy(chunk -> assertThat(chunk.getPosition()).contains(" > "))
                .allSatisfy(chunk -> assertThat(chunk.getPosition()).startsWith("《"));
    }

    /**
     * 跨仓库的引用完整性：售后政策的 {@code doc_ref} 必须能在知识库语料里找到。
     * <p>
     * 约定写在两份互不引用的文件里（order-service 的 {@code data.sql} 与
     * knowledge-service 的 {@code knowledge/*.md}），靠人记着对标。这条用例是唯一会喊出声的地方。
     */
    @Test
    void 售后政策的依据编号在知识库中真实存在() throws IOException {
        Path dataSql = Path.of("..", "order-service", "src", "main", "resources", "data.sql");
        assumeTrue(Files.exists(dataSql), "不在完整仓库中构建，跳过跨模块引用检查");

        String sql = Files.readString(dataSql, StandardCharsets.UTF_8);
        Set<String> referenced = new HashSet<>();
        Matcher matcher = Pattern.compile("'(KB-\\d+)'").matcher(sql);
        while (matcher.find()) {
            referenced.add(matcher.group(1));
        }
        assumeTrue(!referenced.isEmpty(), "data.sql 中未找到 doc_ref 约定值，跳过");

        Set<String> available = corpus().stream().map(Document::getId).collect(java.util.stream.Collectors.toSet());

        assertThat(available)
                .as("售后政策引用了知识库里不存在的文档编号 —— 用户点开「依据」会看到空页")
                .containsAll(referenced);
    }

    /**
     * 语料必须真的会触发「下钻」与「合并」。
     * <p>
     * 结构分层切分主张的是「结构边界优先，超限才逐级下钻」。但若语料里每个结构块本来就短于阈值，
     * 下钻这条分支在<b>生产路径上从不执行</b>——切分器写着章→条→段→句，实际只跑到「按章条切」。
     * 上一版语料栽在「每篇恰好一片、结构完全用不上」，这是同一个坑换了层皮：
     * 功能在，触发条件不在。
     * <p>
     * 判据不依赖任何内部字段：把旋钮拧到不可能触发的极端值再切一遍，片数不同即说明它在起作用。
     * 两个方向都要测——只测下钻会漏掉「minChars 其实一个碎片也没合并」这种情况，
     * 而 minChars 恰恰是调参时最敏感的那个（实测 40→0 会让片数从 109 涨到 348）。
     */
    @Test
    void 语料里两个切分旋钮都真的在起作用() {
        List<Document> docs = corpus();

        StructuralSplitter neverDrillDown = new StructuralSplitter(
                1_000_000, StructuralSplitter.STANDARD_MIN_CHARS);
        List<String> drilled = docs.stream()
                .filter(doc -> SPLITTER.split(doc).size() != neverDrillDown.split(doc).size())
                .map(Document::getId)
                .toList();
        assertThat(drilled)
                .as("没有任何一篇文档的单个结构块超过 %d 字，「逐级下钻」在生产语料上从不执行。"
                        + "要让这条分支真的被跑到，语料里得有一篇含长段落的文档",
                        StructuralSplitter.STANDARD_MAX_CHARS)
                .isNotEmpty();

        StructuralSplitter neverMerge = new StructuralSplitter(
                StructuralSplitter.STANDARD_MAX_CHARS, 0);
        List<String> merged = docs.stream()
                .filter(doc -> SPLITTER.split(doc).size() != neverMerge.split(doc).size())
                .map(Document::getId)
                .toList();
        assertThat(merged)
                .as("没有一篇文档触发过碎片合并，minChars=%d 在当前语料上是空转的",
                        StructuralSplitter.STANDARD_MIN_CHARS)
                .isNotEmpty();
    }

    /** front-matter 解析的基本契约：正文不含元信息，元信息不进正文 */
    @Test
    void 元信息与正文被正确分离() {
        assertThat(corpus()).allSatisfy(doc -> {
            assertThat(doc.getContent())
                    .as("%s 的正文混入了 front-matter", doc.getId())
                    .doesNotContain("---")
                    .doesNotContain("source:")
                    .doesNotContain("version:");
            assertThat(doc.getContent()).doesNotContain("tags:");
        });
    }

    /** 语料目录里不应有装载器读不到的文件（例如解析失败被跳过的） */
    @Test
    void 目录中的每篇文档都被装载() throws IOException {
        long fileCount;
        try (var files = Files.list(CORPUS_DIR)) {
            fileCount = files.filter(path -> path.toString().endsWith(".md")).count();
        }
        assertThat(corpus()).hasSize((int) fileCount);
    }

    /**
     * 切分参数必须<b>只有一个定义</b> —— 全部生产代码走 {@link StructuralSplitter#standard()}。
     * <p>
     * ai-service 拿着 chunkId 去 Milvus 检索，knowledge-service 拿着它建
     * {@code knowledge_chunk}。两边各配一套参数的话，同一篇文档会切出两组编号，
     * 症状是「检索命中了、但点开引用 404」——而两边的日志都完全正常，没有任何一处报错。
     * 这个约定只靠"记得用 standard()"是守不住的，所以让它在这里喊出来。
     * <p>
     * 只扫 {@code src/main}：测试里写死参数是<b>故意的</b>——调参实验要固定住一组取值
     * 才能比较，那些数字正是被比较的对象。
     */
    @Test
    void 生产代码里切分参数只有一处定义() throws IOException {
        Path backend = Path.of("..");
        assumeTrue(Files.isDirectory(backend.resolve("agent-core")), "不在完整仓库中构建，跳过");

        List<String> offenders = new ArrayList<>();
        try (var paths = Files.walk(backend)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/"))
                    .toList()) {
                // 路径与内容别搞混：定义处自己当然会 new 一次，排除它要比的是<b>路径</b>；
                // 而要找的模式在<b>文件内容</b>里。第一版把内容当路径比对，于是它自己
                // 报了警——守卫用例写错方向时不会静默，会误伤，这比漏报好。
                String path = file.toString().replace('\\', '/');
                if (path.endsWith("agent/rag/StructuralSplitter.java")) {
                    continue;
                }
                if (Files.readString(file, StandardCharsets.UTF_8).contains("new StructuralSplitter(")) {
                    offenders.add(backend.relativize(file).toString().replace('\\', '/'));
                }
            }
        }

        assertThat(offenders)
                .as("这些生产代码直接写死了切分参数，而不是走 StructuralSplitter.standard()。"
                        + "参数一旦与检索侧不一致，引用回跳会对不上号（检索命中、点开 404），"
                        + "而两边日志都正常")
                .isEmpty();
    }
}
