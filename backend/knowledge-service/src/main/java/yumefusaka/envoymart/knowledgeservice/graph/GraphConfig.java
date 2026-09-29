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
     * <b>读 5s、写 60s，两者分开</b>：读在<b>用户请求路径</b>上，一次挂起的查询会拖住
     * 整个检索，所以宁可短；写是<b>后台批量任务</b>（重建索引逐篇写图），被中途掐断
     * 等于白跑一趟，所以宁可长。这里曾经只有一个 `query-timeout-ms` 服务两种场景，
     * 结果实测撞上：一篇 11 条三元组的文档在一个事务里要发二十多条语句，
     * 超时之后整篇证据一条都没进图。
     * <p>
     * 注意<b>这里的默认值不一定是生效值</b>——{@code application.yml} 里的
     * {@code envoymart.graph.*} 会覆盖它，而本地那三个值（3s/5s）才是实际在跑的。
     * 改这里之前先看 yml：原先代码里写着「查询 15s」的修正，被 yml 的 5000 盖掉，
     * 一直没生效过。
     */
    @Bean
    public KnowledgeGraphStore knowledgeGraphStore(
            @Value("${envoymart.graph.enabled:true}") boolean enabled,
            @Value("${envoymart.graph.uri:bolt://127.0.0.1:7687}") String uri,
            @Value("${envoymart.graph.username:neo4j}") String username,
            @Value("${envoymart.graph.password:}") String password,
            @Value("${envoymart.graph.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${envoymart.graph.query-timeout-ms:5000}") int queryTimeoutMs,
            @Value("${envoymart.graph.write-timeout-ms:60000}") int writeTimeoutMs) {
        return new KnowledgeGraphStore(enabled, uri, username, password,
                connectTimeoutMs, queryTimeoutMs, writeTimeoutMs);
    }
}
