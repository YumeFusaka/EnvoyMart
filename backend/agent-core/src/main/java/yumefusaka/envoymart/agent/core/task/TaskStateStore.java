package yumefusaka.envoymart.agent.core.task;

import java.util.Optional;

/**
 * 任务断点的存取端口。
 * <p>
 * <b>为什么是接口而不是直接用一个 Redis 客户端。</b>这个包在 {@code agent-core} 里，
 * 它对 Redis、数据库、HTTP 一无所知——那是这个模块能同时被 ai-service、单测、
 * 以及将来任何宿主复用的前提。存储介质是<b>部署决策</b>，不是任务编排的一部分：
 * 单机演练可以用内存实现，ai-service 用 Redis 实现，两者对编排层完全等价。
 * <p>
 * <b>所有方法都不抛异常。</b>断点是「帮下一次请求省一次重算」的优化，
 * 不是任务本身的一部分——存储层抖动不该让用户的一次正常提问失败。
 * 存不下就退化成「这一次没法恢复」，那是一个可接受的降级；
 * 为此抛异常把成功的执行变成一次报错，则是把优化的代价转嫁给了正确性。
 */
public interface TaskStateStore {

    /** 保存（覆盖）一份断点。失败时记日志并返回，不抛 */
    void save(TaskCheckpoint checkpoint);

    /** 读取一份断点。没有、已过期、或存储不可用时一律返回空 —— 对调用方都等价于「没有可恢复的现场」 */
    Optional<TaskCheckpoint> load(String userId, String taskId);

    /** 任务真正收尾后清除断点。留着它只会让下一次同号请求以为自己还能恢复 */
    void clear(String userId, String taskId);

    /** 不做任何持久化的实现 —— 用于单测与「不启用断点」的部署 */
    TaskStateStore NOOP = new TaskStateStore() {
        @Override
        public void save(TaskCheckpoint checkpoint) {
        }

        @Override
        public Optional<TaskCheckpoint> load(String userId, String taskId) {
            return Optional.empty();
        }

        @Override
        public void clear(String userId, String taskId) {
        }
    };
}