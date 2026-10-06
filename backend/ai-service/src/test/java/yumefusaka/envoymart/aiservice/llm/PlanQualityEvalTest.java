package yumefusaka.envoymart.aiservice.llm;

import dev.langchain4j.model.openai.OpenAiChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.plan.PlanQualityFixtures;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复杂任务规划质量评测（P2-1）—— 调真实规划器，按确定性指标打分。
 * <p>
 * 判据全部落在结构与顺序上（覆盖率 / 错规划率 / 顺序满足率 / 步数 / 依赖声明），
 * 不掺任何模型自评——那等于拿一个黑箱去评另一个黑箱。判定逻辑在
 * {@link PlanQualityFixtures#judge}，本类只负责「真实调用 + 汇总报告 + 两条稳定断言」。
 * <p>
 * 需要 {@code RUN_PLAN_EVAL=true} 与 {@code LLM_API_KEY}。
 * 之所以要显式开关：它会真实计费，不该被「碰巧配了 Key」时静默触发。
 */
@EnabledIfEnvironmentVariable(named = "RUN_PLAN_EVAL", matches = "true")
class PlanQualityEvalTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("LLM_BASE_URL", "https://api.deepseek.com");
    private static final String MODEL =
            System.getenv().getOrDefault("LLM_MODEL", "deepseek-flash");

    /** 与线上注册表同名同描述的最小工具集 —— 规划器看到的就是这些 */
    private static ToolRegistry realToolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        for (ToolDefinition def : List.of(
                tool("product_search", "按关键词与条件搜索商品，返回商品编号、名称、价格区间、库存与销量"),
                tool("knowledge_search", "检索平台知识库原文（规则、政策条款、商品说明书、成分与用法、人群禁忌）"),
                tool("interaction_check", "查询若干实体之间是否存在相互作用或禁忌"),
                tool("order_query", "按订单编号查询当前用户的订单详情"),
                tool("order_cancel", "取消当前用户的订单"),
                tool("logistics_query", "查询订单的物流轨迹"),
                tool("cart_add", "把某款商品加入购物车"),
                tool("cart_checkout", "结算当前购物车并下单"),
                tool("after_sale_apply", "对订单发起售后申请"),
                tool("address_list", "列出当前用户的收货地址"))) {
            registry.register(new StubTool(def));
        }
        return registry;
    }

    private static ToolDefinition tool(String name, String description) {
        return ToolDefinition.builder().name(name).description(description).parameters(Map.of()).build();
    }

    @Test
    void 复杂任务的规划质量() {
        String apiKey = System.getenv("LLM_API_KEY");
        ChatModelHolder holder = new ChatModelHolder(apiKey);
        ToolRegistry registry = realToolRegistry();
        LangChain4jLLMProvider provider = new LangChain4jLLMProvider(holder.model(), null, registry,
                LLMConfig.builder().model(MODEL).temperature(0.0).build(), new SimpleMeterRegistry());

        List<ToolDefinition> defs = registry.listDefinitions();

        int pass = 0;
        int covered = 0;
        int orderOk = 0;
        int stepOk = 0;
        int dependsDeclared = 0;
        int totalMissing = 0;
        int totalUnexpected = 0;

        System.out.printf("%n========== 规划质量评测（%d 条任务，模型 %s） ==========%n",
                PlanQualityFixtures.CASES.size(), MODEL);

        for (PlanQualityFixtures.Case c : PlanQualityFixtures.CASES) {
            List<PlanStep> plan = provider.plan(c.query(), defs, "");
            PlanQualityFixtures.Verdict v = PlanQualityFixtures.judge(c, plan);

            if (v.pass()) {
                pass++;
            }
            if (v.toolsCovered()) {
                covered++;
            }
            if (v.orderOk()) {
                orderOk++;
            }
            if (v.stepCountOk()) {
                stepOk++;
            }
            if (v.dependsDeclared()) {
                dependsDeclared++;
            }
            totalMissing += v.missingTools();
            totalUnexpected += v.unexpectedTools();

            System.out.printf("%s [%s] %s%n    %s%n",
                    v.pass() ? "PASS" : "FAIL", c.note(), c.query(), v.detail());
        }

        int n = PlanQualityFixtures.CASES.size();
        System.out.println("--------------------------------------------------");
        System.out.printf("整条通过率   %d/%d = %.3f%n", pass, n, rate(pass, n));
        System.out.printf("工具覆盖率   %d/%d = %.3f%n", covered, n, rate(covered, n));
        System.out.printf("顺序满足率   %d/%d = %.3f%n", orderOk, n, rate(orderOk, n));
        System.out.printf("步数达标率   %d/%d = %.3f%n", stepOk, n, rate(stepOk, n));
        System.out.printf("依赖声明率   %d/%d = %.3f%n", dependsDeclared, n, rate(dependsDeclared, n));
        System.out.printf("累计缺工具 %d 次，累计多规划 %d 次%n", totalMissing, totalUnexpected);
        System.out.println("==================================================");

        // 只钉两条跨版本稳定的底线，不钉具体比率——具体数字随模型版本漂移，
        // 钉死它会让一条与产品无关的红灯长期挂着（bad 断言训练人忽略红灯）。
        assertThat(covered)
                .as("工具覆盖率不该低到一半以下——那说明规划器基本没在按任务选工具")
                .isGreaterThanOrEqualTo(n / 2);
        assertThat(pass)
                .as("整条通过率为 0 意味着夹具或接线坏了，不是模型差")
                .isGreaterThan(0);
    }

    private static double rate(int hit, int total) {
        return total == 0 ? 0.0 : (double) hit / total;
    }

    /** 把「建模型」这一步单独抽出来，好让异常信息一眼可读 */
    private static final class ChatModelHolder {
        private final dev.langchain4j.model.chat.ChatModel model;

        ChatModelHolder(String apiKey) {
            this.model = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(BASE_URL)
                    .modelName(MODEL)
                    .timeout(Duration.ofSeconds(60))
                    .build();
        }

        dev.langchain4j.model.chat.ChatModel model() {
            return model;
        }
    }

    /** 规划阶段只需要工具的定义，不需要真的执行 */
    private record StubTool(ToolDefinition definition)
            implements yumefusaka.envoymart.agent.tool.Tool {

        @Override
        public ToolDefinition getDefinition() {
            return definition;
        }

        @Override
        public yumefusaka.envoymart.agent.tool.ToolResult execute(
                yumefusaka.envoymart.agent.tool.ToolCall call) {
            return yumefusaka.envoymart.agent.tool.ToolResult.builder()
                    .success(true).output("stub").build();
        }
    }
}

