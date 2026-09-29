package yumefusaka.envoymart.knowledgeservice.graph;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识图谱的连接装配。
 * <p>
 * 单独一个配置类而不是在存储类上打 {@code @Component}：连接参数有六项，
 * 用 {@code @Value} 注进构造器会把存储类绑死在 Spring 上——而它同时也被单元测试直接 new。
 */
@Configuration
public class GraphConfig {

    /**
     * 超时默认值的取值理由。
     * <p>
     * <b>建连 10s 而不是 3s</b>：Neo4j 容器刚起来的一两分钟里，Bolt 端口已经在监听、
     * 但服务端还在恢复存储，握手会被拖到几秒以上——3 秒会把这种「马上就好」
     * 判成「连不上」，而 {@code isAvailable()} 的判据就是这个探活。
     * <p>
     * <b>查询 15s 而不是 5s</b>：几跳内的局部匹配本身是毫秒级，
     * 但<b>首次查询</b>还要付连接建立与语句编译的钱，冷启动时会被 5 秒掐断——
     * 而这一次失败会被 {@code replaceDocument} 记成写入失败，白丢一篇文档的抽取结果。
     * 两个默认值都是在本地实测踩到之后调的，不是预估。
     */
    @Bean
    public KnowledgeGraphStore knowledgeGraphStore(
            @Value("${envoymart.graph.enabled:true}") boolean enabled,
            @Value("${envoymart.graph.uri:bolt://127.0.0.1:7687}") String uri,
            @Value("${envoymart.graph.username:neo4j}") String username,
            @Value("${envoymart.graph.password:}") String password,
            @Value("${envoymart.graph.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${envoymart.graph.query-timeout-ms:15000}") int queryTimeoutMs) {
        return new KnowledgeGraphStore(enabled, uri, username, password, connectTimeoutMs, queryTimeoutMs);
    }
}
