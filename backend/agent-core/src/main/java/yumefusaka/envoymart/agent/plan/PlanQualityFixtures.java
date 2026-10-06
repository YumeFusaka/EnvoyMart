package yumefusaka.envoymart.agent.plan;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.llm.PlanStep;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 复杂任务规划质量评测夹具（P2-1）。
 * <p>
 * 它补的是哪段空白：plan -> act -> evaluate -> replan 这条执行图已用 LangGraph4j 编排，
 * critique 也做了四分类失败诊断，但没有任何一条用例回答「5 步以上的任务规划得怎么样」。
 * 现有评测全部集中在检索与问答质量上，规划质量只能靠描述机制，给不出数字。
 * <p>
 * 判据为什么落在结构与顺序上。「让模型自己判断这份计划好不好」是不可复现的，也不可信——
 * 那等于拿一个黑箱去评另一个黑箱。这里每一条只标注两样客观事实：
 * expectTools（这份任务该用到哪些工具，工具名是注册表里的实名）与 
 * mustPrecede（某些步骤之间的偏序，如「先查商品、再算券/运费」）。
 * 于是覆盖率、错规划率、顺序满足率、步数全部可以确定性算出来，不需要模型参与判定。
 * <p>
 * 为什么还要一条 dependsOn 判据：Plan-and-Execute 相对 ReAct 的结构性卖点是「互不依赖的
 * 步骤可并发执行」——如果模型把所有步骤都平铺、从不声明依赖，那份计划虽然工具都对了，
 * 却退化成了串行的 ReAct，卖点没兑现。这条判据盯的就是这个。
 * <p>
 * 样本覆盖：单域多步 / 跨域多步（商品 + 订单 + 知识）/ 带条件过滤 / 应先澄清 / 该拒答。
 */
public final class PlanQualityFixtures {

    /**
     * 一条标注任务。
     *
     * @param query         用户请求原文
     * @param expectTools   该用到的工具集合（空集合表示「不该规划任何工具」，如该拒答/该澄清）
     * @param mustPrecede   形如 [["product_search","cart_add"]]，前者必须排在后者之前
     * @param expectMinSteps 计划至少几步（低于它说明退化成了单步）
     * @param expectMaxSteps 计划至多几步（高于它说明过度拆解、在制造无谓调用）
     * @param note          这条样本考什么（报告里打印，便于人读）
     */
    public record Case(String query, List<String> expectTools, List<List<String>> mustPrecede,
                       int expectMinSteps, int expectMaxSteps, String note) {
    }

    public static final List<Case> CASES;

    static {
        Map<String, Object> raw = load();
        CASES = ((List<?>) raw.get("cases")).stream().map(PlanQualityFixtures::toCase).toList();
    }

    private PlanQualityFixtures() {
    }

    /** 一份计划的评测结果 —— 四条确定性指标 + 逐条明细 */
    public record Verdict(boolean toolsCovered, int missingTools, int unexpectedTools,
                          boolean orderOk, boolean stepCountOk, boolean dependsDeclared,
                          List<String> planned, String detail) {

        /** 这份计划是否三项硬指标（覆盖 / 顺序 / 步数）全过；错规划只报告、不单独否决 */
        public boolean pass() {
            return toolsCovered && orderOk && stepCountOk;
        }
    }

    /**
     * 对一份计划做确定性判定。不调用模型——判定只比对工具名与先后位置。
     * <p>
     * 判定口径：
     * 覆盖：expectTools 里每个都出现在计划中（按工具名，忽略出现次数与顺序）；
     * 错规划：计划里有、但不在 expectTools 里的工具数（如该拒答却调了工具）；
     * 顺序：mustPrecede 的每一对中，前者在计划里的首次下标小于后者首次下标；
     * 步数：expectMinSteps <= 计划长度 <= expectMaxSteps。
     * <p>
     * 任一工具在计划里没出现时，涉及它的 mustPrecede 对判为不满足——「该做的事没做」与
     * 「顺序错了」是两种失败，但在「这一步没达标」上是同一件事。
     */
    public static Verdict judge(Case c, List<PlanStep> plan) {
        List<String> planned = plan.stream().map(PlanStep::getTool).toList();

        List<String> missing = c.expectTools().stream()
                .filter(t -> !planned.contains(t)).toList();
        List<String> unexpected = planned.stream()
                .filter(t -> !c.expectTools().contains(t)).distinct().toList();

        boolean orderOk = true;
        for (List<String> pair : c.mustPrecede()) {
            int first = planned.indexOf(pair.get(0));
            int second = planned.indexOf(pair.get(1));
            if (first < 0 || second < 0 || first >= second) {
                orderOk = false;
                break;
            }
        }

        boolean stepCountOk = planned.size() >= c.expectMinSteps()
                && planned.size() <= c.expectMaxSteps();

        // 依赖声明：只要至少有一条 dependsOn 非空就算「声明过」。不要求每条都填——单步计划本来就没有依赖可填。
        boolean dependsDeclared = plan.stream()
                .anyMatch(s -> s.getDependsOn() != null && !s.getDependsOn().isEmpty());

        String detail = "计划=[" + String.join(",", planned) + "]"
                + (missing.isEmpty() ? "" : " 缺=" + missing)
                + (unexpected.isEmpty() ? "" : " 多=" + unexpected);

        return new Verdict(missing.isEmpty(), missing.size(), unexpected.size(),
                orderOk, stepCountOk, dependsDeclared, planned, detail);
    }

    private static Map<String, Object> load() {
        try (InputStream in = PlanQualityFixtures.class
                .getResourceAsStream("/eval/plan-quality-fixtures.json")) {
            if (in == null) {
                throw new IllegalStateException("缺少评测夹具资源 /eval/plan-quality-fixtures.json");
            }
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>() {
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("规划质量夹具读取失败", e);
        }
    }

    private static Case toCase(Object raw) {
        Map<?, ?> map = (Map<?, ?>) raw;
        List<String> expectTools = ((List<?>) map.get("expectTools")).stream()
                .map(String::valueOf).toList();
        List<List<String>> mustPrecede = ((List<?>) map.get("mustPrecede")).stream()
                .map(p -> ((List<?>) p).stream().map(String::valueOf).toList()).toList();
        return new Case((String) map.get("query"), expectTools, mustPrecede,
                ((Number) map.get("expectMinSteps")).intValue(),
                ((Number) map.get("expectMaxSteps")).intValue(),
                (String) map.get("note"));
    }
}

