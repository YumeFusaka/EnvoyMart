package yumefusaka.envoymart.agent.llm;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 计划步骤引用（{@code $N.字段}）的语法与检测 —— <b>单一来源</b>。
 * <p>
 * <b>为什么必须只有一份。</b>引用语法同时被三处需要：计划编译期的合法性检查、
 * 执行期的取值解析、以及「这个参数还没有真实值」的拦截判断。三者写在两个模块里
 * （{@code AgentGraph} 与 {@code LangChain4jLLMProvider}），各写一份正则意味着
 * 「什么算引用」这件事有两个定义——一处认、一处不认，症状就是某个形态的引用
 * 从其中一条路径静默漏过去。
 * <p>
 * <b>它服务的是「这条参数能不能执行」这个判断，不是文本处理。</b>参数里还留着
 * {@code $N} 就意味着它指向的值从来没被填进来；高危操作在这种情况下绝不能生成
 * 确认卡——用户批准的是他看见的那次调用，而那次调用没有真实目标。
 */
public final class StepReferences {

    /** 完整引用：步骤下标 + 至少一段字段路径（可含列表下标），如 {@code $0[1].skuId}。 */
    public static final Pattern REF = Pattern.compile("\\$(\\d+)((?:\\[\\d+])*(?:\\.[A-Za-z_][A-Za-z0-9_]*(?:\\[\\d+])*)+)");

    /**
     * 引用标记：{@code $} 后跟数字，不要求完整。
     * <p>
     * 它与 {@link #REF} 的分工是「剩下的文本里还有没有引用残留」——把完整引用逐段
     * 摘掉之后再看它，命中的就是 {@code $0}、{@code $0.skus.0} 这类<b>不完整或非法</b>
     * 的形态。用 {@link #REF} 做这件事会漏掉它们，而它们同样解析不出值。
     */
    public static final Pattern MARKER = Pattern.compile("\\$-?\\d+");

    /** 引用路径里的字段名与列表下标，用于逐段合法性检查。 */
    public static final Pattern PATH_TOKEN = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*)|\\[(\\d+)]");

    /**
     * 递归判断参数值里是否仍残留未解析的步骤引用。
     * <p>
     * 递归而非只看顶层字符串：模型完全可能把列表或对象当参数值
     * （例如 {@code items:[{"skuId":"$0.skuId"}]}），只处理顶层会让里面那层漏过去。
     */
    public static boolean hasUnresolved(Object value) {
        if (value instanceof String text) {
            return MARKER.matcher(text).find();
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(StepReferences::hasUnresolved);
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(StepReferences::hasUnresolved);
        }
        return false;
    }

    private StepReferences() {
    }
}