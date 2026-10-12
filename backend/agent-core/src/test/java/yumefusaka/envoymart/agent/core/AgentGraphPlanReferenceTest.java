package yumefusaka.envoymart.agent.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import yumefusaka.envoymart.agent.llm.ChatMessage;
import yumefusaka.envoymart.agent.llm.LLMConfig;
import yumefusaka.envoymart.agent.llm.LLMProvider;
import yumefusaka.envoymart.agent.llm.LLMResponse;
import yumefusaka.envoymart.agent.llm.PlanStep;
import yumefusaka.envoymart.agent.loop.LoopBudget;
import yumefusaka.envoymart.agent.loop.LoopGuard;
import yumefusaka.envoymart.agent.tool.Tool;
import yumefusaka.envoymart.agent.tool.ToolCall;
import yumefusaka.envoymart.agent.tool.ToolDefinition;
import yumefusaka.envoymart.agent.tool.ToolRegistry;
import yumefusaka.envoymart.agent.tool.ToolResult;
import yumefusaka.envoymart.agent.tool.ToolProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实复杂任务失败的回归：计划拒绝后必须能修复，规格引用必须解析真实嵌套输出。 */
class AgentGraphPlanReferenceTest {
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ToolRegistry registry = new ToolRegistry();
    private final List<ToolCall> writes = new ArrayList<>();

    @AfterEach
    void close() {
        executor.shutdownNow();
    }

    @Test
    void 未来引用触发一次修复且修复成功清除旧错误() {
        registerTools(List.of(Map.of("skuId", 29L)));
        StubProvider provider = new StubProvider(
                List.of(step("write", Map.of("skuId", "$0.skuId"))),
                searchThenWrite("$0[0].skuId"));

        var result = run(provider);

        assertThat(provider.calls).hasValue(2);
        assertThat(provider.contexts.get(1)).contains("未来", "$0");
        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).getArguments().get("skuId")).isEqualTo(29L);
        assertThat(result.getAnswer()).isEqualTo("已完成");
    }

    @Test
    void 修复仍非法时只尝试一次并阻断全部工具() {
        registerTools(List.of(Map.of("skuId", 29L)));
        var invalid = List.of(step("write", Map.of("skuId", "$0.skuId")));
        StubProvider provider = new StubProvider(invalid, invalid);

        var result = run(provider);

        assertThat(provider.calls).hasValue(2);
        assertThat(result.getPlan()).isEmpty();
        assertThat(result.getToolExecutions()).isEmpty();
        assertThat(writes).isEmpty();
        assertThat(result.getAnswer()).contains("阻止", "未来");
    }

    @Test
    void 无修复预算保留拒绝原因且不执行任何工具() {
        registerTools(List.of(Map.of("skuId", 29L)));
        StubProvider provider = new StubProvider(List.of(step("write", Map.of("skuId", "$0.skuId"))),
                searchThenWrite("$0[0].skuId"));
        LoopGuard exhausted = new LoopGuard(new LoopBudget(8, 2, 1));
        assertThat(exhausted.allowPlanRound()).isTrue();
        var result = new AgentGraph(provider, LLMConfig.builder().model("stub").build(), registry, executor)
                .run("u1", "查后再操作", "", List.of(), exhausted, null, null);

        assertThat(provider.calls).hasValue(1);
        assertThat(result.getToolExecutions()).isEmpty();
        assertThat(result.getAnswer()).contains("阻止", "未来");
    }

    @Test
    void 修复放弃返回空计划不能悄悄转工具对话绕过校验() {
        registerTools(List.of(Map.of("skuId", 29L)));
        var result = run(new StubProvider(List.of(step("write", Map.of("skuId", "$0.skuId"))), List.of()));

        assertThat(writes).isEmpty();
        assertThat(result.getAnswer()).contains("阻止", "未给出可执行步骤");
    }

    @Test
    void 无引用的未注册工具仍应拒绝原始计划() {
        registerTools(List.of(Map.of("skuId", 29L)));
        var result = run(new StubProvider(List.of(step("unknown", Map.of())), List.of()));

        assertThat(result.getPlan()).isEmpty();
        assertThat(result.getToolExecutions()).isEmpty();
        assertThat(result.getAnswer()).contains("阻止");
    }

    @Test
    void 未知工具不删除步骤后悄悄改变引用编号() {
        registerTools(List.of(Map.of("skuId", 29L)));
        StubProvider provider = new StubProvider(List.of(
                step("unknown", Map.of()), step("search", Map.of()),
                step("write", Map.of("skuId", "$1[0].skuId"))),
                searchThenWrite("$0[0].skuId"));

        var result = run(provider);

        assertThat(provider.contexts).hasSize(2);
        assertThat(provider.contexts.get(1)).contains("未注册");
        assertThat(writes).hasSize(1);
        assertThat(result.getAnswer()).isEqualTo("已完成");
    }

    @Test
    void 非法依赖也走相同修复入口() {
        registerTools(List.of(Map.of("skuId", 29L)));
        StubProvider provider = new StubProvider(List.of(step("search", Map.of()).toBuilder()
                .dependsOn(List.of(0)).build()), searchThenWrite("$0[0].skuId"));

        var result = run(provider);

        assertThat(provider.calls).hasValue(2);
        assertThat(provider.contexts.get(1)).contains("dependsOn");
        assertThat(writes).hasSize(1);
        assertThat(result.getAnswer()).isEqualTo("已完成");
    }

    @Test
    void 显式嵌套下标取到选定规格并保留类型与混合文本() {
        registerTools(List.of(new Product(List.of(new Sku(29L), new Sku(30L)))));
        StubProvider provider = new StubProvider(List.of(
                step("search", Map.of()), step("write", Map.of(
                "skuId", "$0[0].skus[1].skuId", "note", "已选 SKU $0[0].skus[1].skuId",
                "items", List.of(Map.of("skuId", "$0[0].skus[0].skuId"))))), List.of());

        var result = run(provider);

        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).getArguments()).containsEntry("skuId", 30L)
                .containsEntry("note", "已选 SKU 30")
                .containsEntry("items", List.of(Map.of("skuId", 29L)));
        assertThat(result.getPlan().get(1).getDependsOn()).containsExactly(0);
        assertThat(result.getPlan().get(1).getStepOutputBindings())
                .containsEntry("$0[0].skus[1].skuId", 0);
    }

    @Test
    void 嵌套规格未带下标且不唯一时禁止默认选第一个() {
        registerTools(List.of(new Product(List.of(new Sku(29L), new Sku(30L)))));
        var result = run(new StubProvider(searchThenWrite("$0[0].skus.skuId"), List.of()));

        assertThat(writes).isEmpty();
        assertThat(result.getAnswer()).contains("阻止");
    }

    @Test
    void 嵌套列表每层唯一时兼容省略下标() {
        registerTools(List.of(new Product(List.of(new Sku(29L)))));
        var result = run(new StubProvider(searchThenWrite("$0.skus.skuId"), List.of()));

        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).getArguments().get("skuId")).isEqualTo(29L);
        assertThat(result.getAnswer()).isEqualTo("已完成");
    }

    @Test
    void 嵌套下标越界不会带着部分解析值执行() {
        registerTools(List.of(Map.of("skus", List.of(Map.of("skuId", 29L)))));
        var result = run(new StubProvider(searchThenWrite("$0[0].skus[2].skuId"), List.of()));

        assertThat(writes).isEmpty();
        assertThat(result.getAnswer()).contains("阻止");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$0", "$-1.skuId", "$999999999999999999.skuId",
            "$0[0].skus[-1].skuId", "$0[0].skus[999999999999999999].skuId",
            "$0.skus..skuId", "$0.skus.0.skuId"})
    void 非法引用语法和超范围下标由编译入口拒绝并允许修复(String reference) {
        registerTools(List.of(Map.of("skuId", 29L)));
        var provider = new StubProvider(searchThenWrite(reference), searchThenWrite("$0[0].skuId"));

        var result = run(provider);

        assertThat(provider.calls).hasValue(2);
        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).getArguments()).containsEntry("skuId", 29L);
        assertThat(result.getAnswer()).isEqualTo("已完成");
    }

    @Test
    void 输出列表含空元素时保留引用并阻断而不抛空指针() {
        registerTools(java.util.Arrays.asList((Object) null));
        var result = run(new StubProvider(searchThenWrite("$0[0].skuId"), List.of()));

        assertThat(writes).isEmpty();
        assertThat(result.getAnswer()).contains("阻止");
    }

    @Test
    void 初始规划结束后取消不会再发起修复模型调用() {
        registerTools(List.of(Map.of("skuId", 29L)));
        var provider = new StubProvider(List.of(step("write", Map.of("skuId", "$0.skuId"))),
                searchThenWrite("$0[0].skuId"));
        ToolProgressListener progress = new ToolProgressListener() {
            @Override public void onStart(String tool) { }
            @Override public void onFinish(String tool, boolean success, boolean noData, long latencyMs) { }
            @Override public boolean cancelled() { return provider.calls.get() > 0; }
        };

        assertThatThrownBy(() -> new AgentGraph(provider, LLMConfig.builder().model("stub").build(),
                registry, executor).run("u1", "查后再操作", "", List.of(), new LoopGuard(), null, progress))
                .satisfies(error -> assertThat(AgentCancelledException.isCancellation(error)).isTrue());
        assertThat(provider.calls).hasValue(1);
        assertThat(writes).isEmpty();
    }

    @Test
    void 嵌套规格引用在审批卡生成前解析为真实数值() {
        registerTools(List.of(new Product(List.of(new Sku(29L), new Sku(30L)))));
        registry.register(new Tool() {
            @Override public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("write").description("待审批写操作")
                        .requiresConfirmation(true).parameters(Map.of()).build();
            }
            @Override public ToolResult execute(ToolCall call) {
                writes.add(call);
                return ToolResult.builder().success(true).output("写入完成").build();
            }
        });

        var result = run(new StubProvider(searchThenWrite("$0[0].skus[1].skuId"), List.of()));

        assertThat(writes).isEmpty();
        assertThat(result.getPendingActions()).hasSize(1);
        assertThat(result.getPendingActions().get(0).arguments()).containsEntry("skuId", 30L);
        assertThat(result.getStage()).isEqualTo(TaskStage.WAITING_USER);
    }

    private void registerTools(Object data) {
        registry.register(tool("search", call -> ToolResult.builder()
                .success(true).output("已查到商品规格").rawData(data).build()));
        registry.register(tool("write", call -> {
            writes.add(call);
            return ToolResult.builder().success(true).output("写入完成").build();
        }));
    }

    private static Tool tool(String name, java.util.function.Function<ToolCall, ToolResult> execute) {
        return new Tool() {
            @Override public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name(name).description("测试工具").parameters(Map.of()).build();
            }
            @Override public ToolResult execute(ToolCall call) { return execute.apply(call); }
        };
    }

    private AgentGraph.GraphResult run(StubProvider provider) {
        return new AgentGraph(provider, LLMConfig.builder().model("stub").build(), registry, executor)
                .run("u1", "查商品后操作选定规格", "", List.of(),
                        new LoopGuard(new LoopBudget(8, 3, 2)), null, null);
    }

    private static PlanStep step(String name, Map<String, Object> arguments) {
        return PlanStep.builder().tool(name).arguments(arguments).build();
    }

    private static List<PlanStep> searchThenWrite(String reference) {
        return List.of(step("search", Map.of()), step("write", Map.of("skuId", reference)));
    }

    public record Product(List<Sku> skus) { }
    public record Sku(Long skuId) { }

    private static final class StubProvider implements LLMProvider {
        private final List<PlanStep> initial;
        private final List<PlanStep> repaired;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> contexts = new ArrayList<>();

        private StubProvider(List<PlanStep> initial, List<PlanStep> repaired) {
            this.initial = initial;
            this.repaired = repaired;
        }

        @Override public List<PlanStep> plan(String message, List<ToolDefinition> tools, String context) {
            contexts.add(context);
            return calls.incrementAndGet() == 1 ? initial : repaired;
        }

        @Override public LLMResponse chat(List<ChatMessage> messages, LLMConfig config) {
            return LLMResponse.builder().content("已完成").build();
        }
    }
}
