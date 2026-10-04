package yumefusaka.envoymart.aiservice.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import yumefusaka.envoymart.agent.core.task.TaskCheckpoint;
import yumefusaka.envoymart.agent.core.task.TaskStateStore;

import java.time.Duration;
import java.util.Optional;

/**
 * 任务断点的 Redis 实现。
 * <p>
 * <b>为什么 TTL 比对话历史短得多。</b>断点存在的前提是「用户还会回来点那个确认」，
 * 而确认令牌本身只有 10 分钟有效期（{@code ApprovalTokens} 的 TTL）。断点活得比令牌久，
 * 只会造成一种让人困惑的状态：提示说「有个操作在等你确认」，卡片却已经过期点不动了。
 * 30 分钟给了用户「去接个电话再回来」的余量，又不会长到与令牌脱节。
 * <p>
 * <b>为什么存 JSON 而不是 Redis Hash。</b>快照是一个整体（阶段 + 意图 + 步骤 + 载荷），
 * 没有任何一个字段会被单独读写；Hash 只是在「能部分更新」时才更划算，
 * 而这里部分更新意味着「改了一半的现场」，那比没有现场更坏。
 * <p>
 * <b>所有失败都吞掉。</b>理由见 {@link TaskStateStore} 接口注释：断点是优化不是本体。
 * 这里的 catch 与历史存储相反（那边读取失败必须上抛）——
 * 因为历史读取失败会让界面显示「我的会话全没了」，而断点读取失败只是「这次没法恢复」，
 * 用户看到的是同一个确认卡片，他点下去照样能执行。
 */
@Slf4j
@Component
public class RedisTaskStateStore implements TaskStateStore {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisTaskStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(TaskCheckpoint checkpoint) {
        if (checkpoint == null) {
            return;
        }
        try {
            redis.opsForValue().set(key(checkpoint.userId(), checkpoint.taskId()),
                    objectMapper.writeValueAsString(checkpoint), TTL);
        } catch (Exception e) {
            // 存不下就退化成「这一次恢复不了」。为此把一次已经成功的中断变成报错，
            // 是把优化的代价转嫁给正确性
            log.warn("[TaskState] 断点保存失败 taskId={}: {}", checkpoint.taskId(), e.toString());
        }
    }

    @Override
    public Optional<TaskCheckpoint> load(String userId, String taskId) {
        if (userId == null || taskId == null) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(key(userId, taskId));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, TaskCheckpoint.class));
        } catch (Exception e) {
            log.warn("[TaskState] 断点读取失败 taskId={}: {}", taskId, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void clear(String userId, String taskId) {
        if (userId == null || taskId == null) {
            return;
        }
        try {
            redis.delete(key(userId, taskId));
        } catch (Exception e) {
            log.warn("[TaskState] 断点清除失败 taskId={}: {}", taskId, e.toString());
        }
    }

    /** 键里同时带 userId 与 taskId：taskId 已含 userId，再带一次是为了让归属一眼可见 */
    private static String key(String userId, String taskId) {
        return "agent:task:" + userId + ":" + taskId;
    }
}