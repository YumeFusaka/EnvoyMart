package yumefusaka.envoymart.agent.core;

import java.util.concurrent.CancellationException;

/**
 * 本轮对话已被取消（用户点了「停止生成」或直接断开了连接）。
 * <p>
 * <b>它不是一种失败，是第三种终态。</b>失败要重试、要降级、要如实说「暂时不可用」；
 * 取消是用户主动叫停，正确反应是<b>停止开始新工作</b>并把已经产生的部分如实收尾。
 * 两者混在一起会出各种荒谬的结果：把用户自己按的停止记成系统故障（ERROR + 堆栈），
 * 或者触发降级话术、让服务端继续跑完整个计划——用户已经走了，账单还在动。
 * <p>
 * 继承 {@link CancellationException} 而不是 Exception：取消与 JDK 对「任务被取消」
 * 的既有语义同构，任何通用的 Throwable 处理（线程池、Future）都不会把它误报成故障。
 * <p>
 * <b>抛出点</b>是执行链上所有「即将开始新工作」的位置（下一次模型调用、下一批工具执行、
 * 下一个已确认操作），检查信号来自 {@code ToolProgressListener#cancelled()}。
 * <b>不打断正在进行的工作</b>：模型流式调用没有中断句柄，工具可能已经落到下游——
 * 取消的语义是「不再开始」，不是「中途撕票」。
 */
public class AgentCancelledException extends CancellationException {

    public AgentCancelledException(String message) {
        super(message);
    }

    /**
     * 异常链里是否包着「本轮被取消」。
     * <p>
     * 必须查整条 cause 链：这个异常要从工具/模型层一路穿过 LangGraph4j 的节点执行
     * （框架可能包成 {@code CompletionException}）与各层的 catch，才会到达收尾处。
     * 只比较最外层类型的话，一个包装层就能让取消伪装成「智能助手暂时不可用」。
     * <p>
     * 只认自己的类型，不认 JDK 的 {@code CancellationException}：底层库（HTTP 客户端、
     * 线程池）也会用那个类型表达与用户无关的取消，混进来会把真实故障吞成静默停止。
     */
    public static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AgentCancelledException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /** 从异常链里取出那个取消异常；链上没有则把原异常包装成一个。 */
    public static AgentCancelledException unwrap(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AgentCancelledException cancelled) {
                return cancelled;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return new AgentCancelledException("本轮对话已被取消");
    }
}
