package yumefusaka.envoymart.aiservice.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import yumefusaka.envoymart.agent.rag.Document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 知识库语料装载 —— 从 {@code classpath:knowledge/*.md} 读取，按文件名排序。
 * <p>
 * <b>为什么不在 Java 里写常量。</b>这些文档是<b>长文、带章节结构</b>的多行正文
 * （一份说明书两三章、上千字），塞进 Java 字符串要处理转义与拼接，diff 里全是噪音，
 * 改一个错别字都看不出改了什么。写成文件后，改语料的成本降到和其它文本一样。
 * <p>
 * 排序是刻意的：{@link ResourcePatternResolver} 不保证返回顺序，而切片编号
 * （{@code KB-0005_3}）依赖于装载顺序。不排序会让同一份语料在两次启动后
 * 生成不同的 chunkId，检索结果看着没变、引用编号却对不上。文件名以 {@code KB-00xx-} 开头，
 * 按字典序排即按文档编号排。
 * <p>
 * 文件格式：YAML 风格的 front-matter + 正文。
 * <pre>
 * ---
 * id: KB-0005
 * title: 维生素 D3 软胶囊产品说明书
 * source: manual
 * scope: nutrition
 * version: v2026.03
 * tags: 维生素D3,钙,用量
 * ---
 * 第一章 ...
 * </pre>
 */
@Slf4j
public final class KnowledgeCorpusLoader {

    private static final String PATTERN = "classpath*:knowledge/*.md";
    private static final String DELIMITER = "---";

    private KnowledgeCorpusLoader() {
    }

    public static List<Document> load() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
        } catch (IOException e) {
            throw new UncheckedIOException("知识库语料目录无法读取：" + PATTERN, e);
        }
        if (resources.length == 0) {
            // 空语料不是「检索不到」，而是部署出了问题。静默返回空列表会让 RAG 全程"无依据"，
            // 表现和"知识库里确实没有这条"完全一样，必须显式失败。
            throw new IllegalStateException("知识库语料为空，检查 " + PATTERN + " 是否被打进 jar");
        }

        List<Document> documents = new ArrayList<>(resources.length);
        for (Resource resource : Arrays.stream(resources)
                .sorted((a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())))
                .toList()) {
            documents.add(parse(resource));
        }
        log.info("[Knowledge] 装载语料 {} 篇，总字符 {}", documents.size(),
                documents.stream().mapToInt(doc -> doc.getContent().length()).sum());
        return documents;
    }

    private static Document parse(Resource resource) {
        String raw;
        try {
            raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取语料失败：" + resource.getFilename(), e);
        }

        String filename = String.valueOf(resource.getFilename());
        String[] lines = raw.split("\r?\n", -1);
        if (lines.length == 0 || !DELIMITER.equals(lines[0].strip())) {
            throw new IllegalStateException(filename + " 缺少 front-matter（首行应为 ---）");
        }

        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (DELIMITER.equals(lines[i].strip())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalStateException(filename + " 的 front-matter 没有结束标记 ---");
        }

        Document.DocumentBuilder builder = Document.builder();
        for (int i = 1; i < end; i++) {
            String line = lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IllegalStateException(filename + " 第 " + (i + 1) + " 行不是 key: value —— " + line);
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            switch (key) {
                case "id" -> builder.id(value);
                case "title" -> builder.title(value);
                case "source" -> builder.source(value);
                case "scope" -> builder.scope(value);
                case "version" -> builder.version(value);
                case "tags" -> builder.tags(value.isEmpty() ? List.of() : List.of(value.split("\\s*,\\s*")));
                default -> log.warn("[Knowledge] {} 含未知的元信息字段 {}，已忽略", filename, key);
            }
        }

        String content = String.join("\n", Arrays.copyOfRange(lines, end + 1, lines.length)).strip();
        builder.content(content);

        Document document = builder.build();
        if (document.getId() == null || document.getId().isBlank()) {
            throw new IllegalStateException(filename + " 缺少 id");
        }
        if (document.getTitle() == null || document.getTitle().isBlank()) {
            throw new IllegalStateException(filename + " 缺少 title —— 引用要显示的正是它");
        }
        if (content.isEmpty()) {
            throw new IllegalStateException(filename + " 正文为空");
        }
        return document;
    }
}
