package yumefusaka.envoymart.agent.core;

import yumefusaka.envoymart.agent.core.task.TaskCheckpoint;
import yumefusaka.envoymart.agent.core.task.TaskStateStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 断点的内存实现 —— 单测里替掉 Redis 那份。
 * <p>
 * 与 {@code TaskStateStore.NOOP} 的区别是它<b>真的存</b>：需要跨轮读回断点的用例
 * （续作轮的写操作账本、确认口令的现场判断）不能挂在空实现上，
 * 否则测的是一条永远没有断点的链路，而那条链路上所有判据都恰好「不命中」。
 */
class InMemoryTaskStateStore implements TaskStateStore {

    private final Map<String, TaskCheckpoint> saved = new HashMap<>();

    @Override
    public void save(TaskCheckpoint checkpoint) {
        saved.put(checkpoint.userId() + "|" + checkpoint.taskId(), checkpoint);
    }

    @Override
    public Optional<TaskCheckpoint> load(String userId, String taskId) {
        return Optional.ofNullable(saved.get(userId + "|" + taskId));
    }

    @Override
    public void clear(String userId, String taskId) {
        saved.remove(userId + "|" + taskId);
    }
}