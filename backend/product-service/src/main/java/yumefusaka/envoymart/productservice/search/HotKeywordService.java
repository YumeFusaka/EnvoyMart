package yumefusaka.envoymart.productservice.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 热门搜索词：真实搜索行为的轻量排行。
 * <p>
 * 用 Redis ZSET 记词频而不是落库：热门词是典型的「过期很快、偶尔读、写很频繁」的数据，
 * 为它建一张表再配一个定时任务去聚合，换来的是一堆需要维护的作业；ZINCRBY 一条命令就够。
 * <p>
 * <b>它是旁路，不是主线</b>：记录失败绝不能让搜索失败（{@link #record} 里全部吞掉）。
 * 反过来，读取失败要能兜住——冷启动（新库、清过缓存）时 ZSET 是空的，
 * 这时回落到种子词，否则首页的「热门搜索」就是一片空白。
 */
@Slf4j
@Service
public class HotKeywordService {

    private static final String KEY = "envoymart:hot-keywords";

    /**
     * ZSET 的成员上限。无上限的 ZSET 会被长尾词与垃圾输入撑大，
     * 而热门词只有前几十个有意义——排名之后的一律删掉
     */
    private static final int MAX_TRACKED = 500;

    /** 超过这个长度不是「搜索词」，是有人往参数里灌东西 */
    private static final int MAX_KEYWORD_LENGTH = 32;

    private static final int MAX_LIMIT = 20;

    /** 30 天没人搜就把整份排行丢掉重来，避免一份半年前的榜单永远挂在首页 */
    private static final Duration TTL = Duration.ofDays(30);

    /**
     * 冷启动种子词。刻意选平台真实在售的品类——列一堆没有商品的词，
     * 用户点进去看到空结果，比不显示热门词更糟
     */
    private static final List<String> SEEDS =
            List.of("鱼油", "维生素D", "益生菌", "乳清蛋白", "钙片", "叶黄素", "辅酶Q10", "胶原蛋白");

    private final StringRedisTemplate redis;

    public HotKeywordService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 记一次搜索。失败只留一条 debug 日志——统计丢了是小事，搜索挂了是大事 */
    public void record(String keyword) {
        String word = normalize(keyword);
        if (word == null) {
            return;
        }
        try {
            redis.opsForZSet().incrementScore(KEY, word, 1);
            // 只留前 MAX_TRACKED 名（removeRange 的 end 为负表示从末尾倒数）
            redis.opsForZSet().removeRange(KEY, 0, -(MAX_TRACKED + 1));
            redis.expire(KEY, TTL);
        } catch (Exception e) {
            log.debug("[HotKeyword] 记录失败（不影响搜索）: {}", e.getMessage());
        }
    }

    /**
     * 热门词榜。真实排行**不够 {@code limit} 条时**用种子词补齐尾部，
     * 而不是「有排行就完全不要种子」——新库只被搜过一次时，榜单只有一条，
     * 界面上那排词会显得像坏了。补齐也不重复：已经上榜的词不会被种子再塞一遍。
     */
    public List<String> top(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<String> result = new ArrayList<>();
        try {
            Set<String> ranked = redis.opsForZSet().reverseRange(KEY, 0, size - 1);
            if (ranked != null) {
                result.addAll(ranked);
            }
        } catch (Exception e) {
            log.debug("[HotKeyword] 读取失败，回落种子词: {}", e.getMessage());
        }
        if (result.size() < size) {
            for (String seed : SEEDS) {
                if (result.size() >= size) {
                    break;
                }
                if (!result.contains(seed)) {
                    result.add(seed);
                }
            }
        }
        return result;
    }

    /** 归一化：去首尾空白、压掉连续空白、超长直接丢弃。返回 null 表示这个词不值得记 */
    private String normalize(String keyword) {
        if (keyword == null) {
            return null;
        }
        String word = keyword.trim().replaceAll("\\s+", " ");
        if (word.isEmpty() || word.length() > MAX_KEYWORD_LENGTH) {
            return null;
        }
        return word;
    }
}
