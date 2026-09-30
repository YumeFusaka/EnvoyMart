package yumefusaka.envoymart.productservice.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 热门搜索词的行为约束。
 * <p>
 * 这里钉的不是 Redis 本身（那是中间件的事），而是三条**本项目自己的判据**：
 * 什么词值得记、记的时候顺带做了什么清理、以及读取失败时界面拿到什么。
 * 最后一条尤其要紧——热门词是旁路，它坏掉的表现必须是「回落到种子词」，
 * 而不是「首页那块区域空白」或「搜索接口跟着报错」。
 */
class HotKeywordServiceTest {

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private HotKeywordService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        service = new HotKeywordService(redis);
    }

    @Test
    void 记录时同时计数裁剪并续期() {
        service.record("鱼油");

        verify(zset).incrementScore("envoymart:hot-keywords", "鱼油", 1);
        // 裁剪与续期是「记一次」的组成部分：少了它们，ZSET 会被长尾词撑大、或整份榜单永不消失
        verify(zset).removeRange(anyString(), anyLong(), anyLong());
        verify(redis).expire(anyString(), any(java.time.Duration.class));
    }

    @Test
    void 空白词与超长输入不记() {
        service.record("   ");
        service.record(null);
        service.record("长".repeat(33));

        verify(zset, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    @Test
    void 词中的连续空白被压成一个() {
        service.record("  鱼  油 ");

        verify(zset).incrementScore(anyString(), eq("鱼 油"), eq(1.0));
    }

    @Test
    void Redis故障不影响搜索() {
        when(zset.incrementScore(anyString(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatCode(() -> service.record("鱼油")).doesNotThrowAnyException();
    }

    @Test
    void 排行不足时用种子词补齐且不重复() {
        when(zset.reverseRange(anyString(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of("蛋白", "鱼油")));

        List<String> top = service.top(5);

        assertThat(top).hasSize(5);
        assertThat(top).startsWith("蛋白", "鱼油");
        assertThat(top).doesNotHaveDuplicates();
        // 「鱼油」已在榜上，补齐时不能再出现一次
        assertThat(top.stream().filter("鱼油"::equals)).hasSize(1);
    }

    @Test
    void 排行充足时完全用真实数据() {
        Set<String> ranked = new LinkedHashSet<>(List.of("a", "b", "c"));
        when(zset.reverseRange(anyString(), anyLong(), anyLong())).thenReturn(ranked);

        assertThat(service.top(3)).containsExactly("a", "b", "c");
    }

    @Test
    void Redis不可读时回落种子词而不是报错() {
        when(zset.reverseRange(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("connection refused"));

        List<String> top = service.top(4);

        assertThat(top).hasSize(4);
        assertThat(top).doesNotContainNull();
    }

    @Test
    void 条数参数被夹在上限内() {
        when(zset.reverseRange(anyString(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of("a")));

        // 负数与超大值都不该原样透传给 Redis（size=0 会让 ZSET 命令语义变得可疑）
        assertThat(service.top(0)).hasSize(1);
        assertThat(service.top(10_000).size()).isLessThanOrEqualTo(20);
    }
}
