package yumefusaka.envoymart.agent.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「重新生成」在会话窗口里的语义：**丢弃**上一轮，而不是再追加一轮。
 * <p>
 * 为什么这条要有测试：丢错了不会报错、不会崩，只会让模型看见一份错的历史——
 * 实测的产物是一段**编造的忏悔**（「我上一轮并没有真正检索到这些条款」，
 * 而知识库确实有那些条款）。这类缺陷只能靠断言形状来锁。
 */
class ShortTermMemoryDropLastTurnTest {

    private static MemoryItem message(String session, String content) {
        return MemoryItem.builder()
                .id(java.util.UUID.randomUUID().toString())
                .userId("u1")
                .sessionId(session)
                .content(content)
                .type(MemoryItem.Type.MESSAGE)
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void 丢弃一整轮后窗口里不再有旧答案() {
        ShortTermMemory memory = new ShortTermMemory(20);
        memory.add(message("s1", "user: 平台的满减活动规则是什么？"));
        memory.add(message("s1", "assistant: 满 199 减 20……"));
        memory.add(message("s1", "user: 推荐一款维生素D补充剂"));
        memory.add(message("s1", "assistant: 平台目前查到这三款……"));

        assertThat(memory.dropLastTurn("s1")).as("末尾是完整一轮，应当丢掉两条").isTrue();

        List<String> remaining = memory.recent("s1", 20).stream().map(MemoryItem::getContent).toList();
        assertThat(remaining).containsExactly(
                "user: 平台的满减活动规则是什么？",
                "assistant: 满 199 减 20……");
    }

    @Test
    void 末尾不是助手答复时只丢一条不误删上一轮内容() {
        ShortTermMemory memory = new ShortTermMemory(20);
        memory.add(message("s1", "user: 上一轮的问题"));
        memory.add(message("s1", "assistant: 上一轮的答复"));
        memory.add(message("s1", "user: 这一轮只落了用户消息"));

        assertThat(memory.dropLastTurn("s1")).as("凑不齐一轮，按 false 报").isFalse();

        // 上一轮的正经内容必须还在——按条数硬丢会把「assistant: 上一轮的答复」也删掉
        assertThat(memory.recent("s1", 20).stream().map(MemoryItem::getContent).toList())
                .containsExactly("user: 上一轮的问题", "assistant: 上一轮的答复");
    }

    @Test
    void 空窗口丢弃不报错() {
        ShortTermMemory memory = new ShortTermMemory(20);

        assertThat(memory.dropLastTurn("s-empty")).isFalse();
        assertThat(memory.recent("s-empty", 20)).isEmpty();
    }

    @Test
    void 丢弃会同步落到持久化载体() {
        java.util.List<String> appended = new java.util.ArrayList<>();
        int[] dropped = {0};
        ShortTermMemoryStore store = new ShortTermMemoryStore() {
            @Override
            public List<MemoryItem> load(String sessionId, int limit) {
                return List.of();
            }

            @Override
            public void append(String sessionId, MemoryItem item, int limit) {
                appended.add(item.getContent());
            }

            @Override
            public boolean dropLastTurn(String sessionId) {
                dropped[0]++;
                return true;
            }

            @Override
            public void clear(String sessionId) {
            }
        };
        ShortTermMemory memory = new ShortTermMemory(20, store);
        memory.add(message("s1", "user: 问"));
        memory.add(message("s1", "assistant: 答"));

        memory.dropLastTurn("s1");

        // 只改内存不改载体，会出现「本进程看到干净上下文、别的实例还看到旧答案」这种最难查的不一致
        assertThat(dropped[0]).as("持久化载体必须收到同一次丢弃").isEqualTo(1);
    }
}