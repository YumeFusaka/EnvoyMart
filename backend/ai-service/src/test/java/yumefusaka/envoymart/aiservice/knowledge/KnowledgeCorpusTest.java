package yumefusaka.envoymart.aiservice.knowledge;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.DocumentChunk;
import yumefusaka.envoymart.agent.rag.StructuralSplitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 前面几个测试锁的是「代码把字段带下去了吗」，这一层锁的是「<b>带下去的东西对不对</b>」——
 * 语料是所有引用的源头，源头上少一个字段，下游补不回来。
 * <p>
 * 最要紧的一条是把<b>两个仓库间的隐形约定</b>变成会失败的用例：售后政策表的
 * {@code doc_ref} 写的是 {@code KB-000x}，它指向知识库文档。之前知识库的文档 id 是
 * {@code after_sale_1} 这类名字，两边从来没有对上过——政策引擎给出的「依据编号」
 * 点开是空的，而这条链路断在两份互不知道对方存在的文件之间，没有任何机制会发现。
 */
class KnowledgeCorpusTest {

    private static final StructuralSplitter SPLITTER = new StructuralSplitter(512, 40);

    /** 语料根目录（测试工作目录是模块根 ai-service/） */
    private static final Path CORPUS_DIR = Path.of("src/main/resources/knowledge");

    private List<Document> corpus() {
        return KnowledgeCorpusLoader.load();
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
     * ai-service 的 {@code knowledge/*.md}），靠人记着对齐。这条用例是唯一会喊出声的地方。
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
}
