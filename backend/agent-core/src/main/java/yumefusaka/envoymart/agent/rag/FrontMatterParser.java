package yumefusaka.envoymart.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * 带 front-matter 的 Markdown 文档解析 —— {@code ---} 包住的元信息 + 正文。
 * <p>
 * <b>为什么不是「在 Java 里写常量」。</b>知识库文档是长文、带章节结构的多行正文
 * （一份说明书两三章、上千字），塞进 Java 字符串要处理转义与拼接，diff 里全是噪音，
 * 改一个错别字都看不出改了什么。写成文件后，改语料的成本降到和其它文本一样。
 * <p>
 * <b>格式错误一律抛异常，不跳过、不补默认值。</b>少一个 {@code title} 的后果不是
 * 「这篇文档差一点」，而是它被引用时用户看到「依据：《null》」——错在源头、
 * 却在几屏之外的对话里现形，中间没有任何一处会报错。宁可装载失败。
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
 * 放在 agent-core 而不是某个服务里：装载（knowledge-service 建库）与消费
 * （ai-service 建索引）是两件事，但「什么算一篇合法的知识库文档」只能有一个答案。
 */
public final class FrontMatterParser {

    private static final Logger log = LoggerFactory.getLogger(FrontMatterParser.class);

    private static final String DELIMITER = "---";

    private FrontMatterParser() {
    }

    /**
     * @param filename 仅用于报错定位——解析结果里不含文件名
     * @param raw      文件全文
     * @throws IllegalStateException 缺少 front-matter、缺少必需字段或正文为空
     */
    public static Document parse(String filename, String raw) {
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
        if (isBlank(document.getId())) {
            throw new IllegalStateException(filename + " 缺少 id");
        }
        if (isBlank(document.getTitle())) {
            throw new IllegalStateException(filename + " 缺少 title —— 引用要显示的正是它");
        }
        if (isBlank(document.getVersion())) {
            throw new IllegalStateException(filename + " 缺少 version —— 引用必须能指明是哪一版，"
                    + "同一份说明书改版后旧版引用会变成错误依据");
        }
        if (content.isEmpty()) {
            throw new IllegalStateException(filename + " 正文为空");
        }
        return document;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
