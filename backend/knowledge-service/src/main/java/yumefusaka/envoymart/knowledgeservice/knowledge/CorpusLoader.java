package yumefusaka.envoymart.knowledgeservice.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import yumefusaka.envoymart.agent.rag.Document;
import yumefusaka.envoymart.agent.rag.FrontMatterParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 知识库<b>种子语料</b>装载 —— 从 {@code classpath:knowledge/*.md} 读取，按文件名排序。
 * <p>
 * <b>它是种子，不是事实源。</b>库建好之后，文档的事实源是 {@code knowledge_document} 表；
 * 这里只在启动时把仓库里随代码走的语料灌进去。这样一份新克隆的仓库能直接跑起来，
 * 而不必先手工导数据——但线上改文档走的是接口，不是改文件重启。
 * <p>
 * 排序是刻意的：{@link ResourcePatternResolver} 不保证返回顺序，而切片编号
 * （{@code KB-0005_3}）依赖于切分顺序。不排序会让同一份语料在两次装载后生成不同的
 * chunkId，检索结果看着没变、引用编号却对不上。文件名以 {@code KB-00xx-} 开头，
 * 按字典序排即按文档编号排。
 */
@Slf4j
public final class CorpusLoader {

    private static final String PATTERN = "classpath*:knowledge/*.md";

    private CorpusLoader() {
    }

    public static List<Document> load() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
        } catch (IOException e) {
            throw new UncheckedIOException("种子语料目录无法读取：" + PATTERN, e);
        }
        if (resources.length == 0) {
            // 空语料不是「知识库里没有这条」，而是部署出了问题。静默返回空列表会让
            // 一份没打进 jar 的语料伪装成「知识库确实没收录」，两者在日志里长得一样。
            throw new IllegalStateException("种子语料为空，检查 " + PATTERN + " 是否被打进 jar");
        }

        List<Document> documents = new ArrayList<>(resources.length);
        for (Resource resource : Arrays.stream(resources)
                .sorted((a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())))
                .toList()) {
            try {
                documents.add(FrontMatterParser.parse(String.valueOf(resource.getFilename()),
                        new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException("读取种子语料失败：" + resource.getFilename(), e);
            }
        }
        log.info("[Knowledge] 种子语料 {} 篇，总字符 {}", documents.size(),
                documents.stream().mapToInt(doc -> doc.getContent().length()).sum());
        return documents;
    }
}
