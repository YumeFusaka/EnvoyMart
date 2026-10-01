package yumefusaka.envoymart.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扩写夹具的采集 —— <b>一次性运行，产出 {@code resources/eval/query-expansions.json}</b>。
 * <p>
 * <b>为什么不写两份提示词。</b>采集脚本自己拼提示词，是最省事的做法，也是唯一会出错的
 * 做法：线上扩写器改一个字，夹具就变成"另一个系统的输出"，而这不会报错——它只会让
 * CI 门禁安静地守着一个已经不存在的实现。所以这里<b>直接调用 {@link LlmQueryExpander}
 * 本身</b>，模型输入输出全部经由产品代码，提示词改了两边一起变。
 * <p>
 * <b>为什么要预录而不是让 CI 真调模型。</b>CI 无 Key、不该有网络、更不该计费；
 * 且真实模型有随机性，同一份代码两次跑出不同数字的断言不是门禁。
 * 这与 {@code GroundingFixtures} 是同一套办法，代价也一样：<b>夹具会过期</b>——
 * 所以文件里记着采集时间与模型标识，报告页与数字一起展示，不冒充"现在的表现"。
 * <p>
 * <b>必须显式开关。</b>{@code RUN_EXPANSION_CAPTURE=true} 之外还要求 {@code LLM_API_KEY}：
 * 这是一次 120 条的真实计费调用，不能被"碰巧配了 Key"的开发者在 {@code mvn test} 时静默触发。
 * <pre>
 * set -a; source .env.local; set +a
 * RUN_EXPANSION_CAPTURE=true mvn -pl agent-core test -Dtest=ExpansionCaptureTest
 * </pre>
 * 语料换代、评测样本增删、或扩写提示词大改之后都要重新采集。
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "RUN_EXPANSION_CAPTURE", matches = "true")
class ExpansionCaptureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 采集落点的源文件路径（相对模块目录）。写进源码树而不是 target：它是要提交的产物 */
    private static final Path OUTPUT = Path.of("src", "main", "resources", "eval", "query-expansions.json");

    @Test
    void 采集全部评测查询的真实扩写() throws Exception {
        String apiKey = System.getenv("LLM_API_KEY");
        assertThat(apiKey)
                .as("没有 Key 就采不出真实输出——采集件不接受 Mock 结果")
                .isNotBlank();
        String baseUrl = System.getenv().getOrDefault("LLM_BASE_URL", "https://api.deepseek.com");
        String model = System.getenv().getOrDefault("LLM_MODEL", "deepseek-flash");

        CountingProvider provider = new CountingProvider(new OpenAiChatClient(baseUrl, apiKey, model));
        LlmQueryExpander expander = new LlmQueryExpander(provider, LLMConfig.builder()
                .model(model).baseUrl(baseUrl).apiKey(apiKey).build());

        List<RetrievalEvaluator.EvalCase> cases = EvalFixtures.allCases();
        List<Map<String, Object>> records = new ArrayList<>(cases.size());
        int withHypothetical = 0;
        int withAngles = 0;

        for (RetrievalEvaluator.EvalCase evalCase : cases) {
            QueryExpansions expansions = expander.expand(evalCase.query());
            if (expansions.hypothetical() != null) {
                withHypothetical++;
            }
            withAngles += expansions.angles().isEmpty() ? 0 : 1;

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("query", evalCase.query());
            record.put("hypothetical", expansions.hypothetical());
            record.put("angles", expansions.angles());
            records.add(record);
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("capturedAt", Instant.now().toString());
        document.put("model", model);
        document.put("note", "真实模型输出快照，用于扩写的离线重放与 CI 门禁。"
                + "它不是标准答案，是某一时刻模型对这批查询的真实扩写；"
                + "语料换代、评测样本增删或扩写提示词大改之后要重新采集"
                + "（agent-core/src/test 下的 ExpansionCaptureTest）。");
        document.put("cases", records);

        Files.createDirectories(OUTPUT.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(OUTPUT.toFile(), document);

        System.out.println();
        System.out.println("========== 扩写夹具采集 ==========");
        System.out.printf("模型 %s   样本 %d 条   落点 %s%n", model, cases.size(), OUTPUT.toAbsolutePath());
        System.out.printf("产出假想答案 %d 条，产出角度改写 %d 条，失败 %d 次%n",
                withHypothetical, withAngles, provider.failures);
        System.out.println("==================================");

        assertThat(records).hasSameSizeAs(cases);
        assertThat(provider.failures)
                .as("有调用失败说明这批夹具是残缺的——它不会报错，只会让门禁少测一部分。"
                        + "文件已写出，重跑一次覆盖它；持续失败则查 Key 与配额")
                .isZero();

        // 覆盖率这条线画在 90%，它拦的不是"个别查询模型没照做"，而是**提示词里那个逃逸口又回来了**。
        // 逃逸口在的时候，语义档 40 条里有 25 条被模型判成"无需扩写"（覆盖率 37.5%），
        // 而那 25 条恰恰是最短最口语、最需要扩写的查询——指标因此整段塌掉。
        // 两个量级差得很远，90% 能干净地把它们分开。
        assertThat(Math.min(withHypothetical, withAngles))
                .as("扩写覆盖率过低 —— 先看提示词是不是又给了模型「这条不用扩」的许可。"
                        + "实测：有许可时语义档覆盖率 37.5%，无许可时 100%")
                .isGreaterThanOrEqualTo((int) Math.ceil(cases.size() * 0.9));
    }

    /**
     * 把 {@link OpenAiChatClient} 接到 {@link LLMProvider} 上的采集适配器。
     * <p>
     * 只做一件事：把 {@code List<ChatMessage>} 拆成"系统提示词 + 用户消息"。
     * 扩写器只会发这两段，多出来的角色不该出现在这里——真出现了要早失败，
     * 而不是丢掉那一段继续跑（丢掉的是模型输入的一部分，采集结果会看着正常但其实是错的）。
     */
    private static final class CountingProvider implements LLMProvider {
        private final OpenAiChatClient client;
        private int failures;

        private CountingProvider(OpenAiChatClient client) {
            this.client = client;
        }

        @Override
        public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            String system = null;
            String user = null;
            for (ChatMessage message : messages) {
                if (message.getRole() == ChatMessage.Role.SYSTEM) {
                    system = message.getContent();
                } else if (message.getRole() == ChatMessage.Role.USER) {
                    user = message.getContent();
                } else {
                    throw new IllegalStateException("扩写提示词里出现了预期外的角色：" + message.getRole());
                }
            }
            try {
                OpenAiChatClient.Reply reply = client.chat(system, user, config.getTemperature());
                return LLMResponse.builder()
                        .content(reply.content())
                        .promptTokens(reply.promptTokens())
                        .completionTokens(reply.completionTokens())
                        .build();
            } catch (RuntimeException e) {
                // 扩写器会把异常吞成"没有扩写"，那样这里看不出这次采集是残缺的，
                // 所以要在这层单独记一笔，让采集结束时能对账
                failures++;
                log.warn("[Capture] 采集失败：{}", e.toString());
                throw e;
            }
        }
    }
}
