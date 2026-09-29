package yumefusaka.envoymart.knowledgeservice.graph;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.Value;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 知识图谱的读写门面。直接写 Cypher，不引 Spring Data Neo4j。
 * <p>
 * <b>单一线型 + 关系名做属性</b>（{@code (:Entity)-[:REL {relation:'CONTAINS'}]->(:Entity)}）
 * 而不是每种关系一个类型：动态类型名要么靠字符串拼接（等于把模型输出拼进 Cypher），
 * 要么引 APOC，两条路都比一个普通属性差。属性化之后所有查询都是<b>参数化</b>的，
 * 且「沿 CONTAINS/PROVIDES 走几跳」这类变长路径可以用
 * {@code ALL(r IN rels WHERE r.relation IN [...])} 一次表达。
 * <p>
 * <b>节点的键与展示名是两个字段</b>：{@code name} 是唯一键（商品为 SPU 编号），
 * {@code label} 是给人看的名字。查询一律按 name 匹配、按 label 显示——
 * 只按 label 匹配的话，商品改名或两种写法（「碳酸钙 D3 咀嚼片」有没有空格）
 * 都会让同一条路径忽然断开，而图上看起来只是「多了一组相似节点」。
 * <p>
 * <b>降级策略</b>：连不上时不抛异常、不阻断启动，但 {@link #isAvailable()} 会一直为 false，
 * 每个查询入口据此返回明确的「图谱不可用」而不是空结果。这两者的区别在本场景是要命的——
 * 「没有找到相互作用」和「图谱挂了所以没查」对用户是完全相反的两句话。
 */
@Slf4j
public class KnowledgeGraphStore implements DisposableBean {

    /** 关系类型统一为 REL，真正的语义在 relation 属性上 */
    private static final String REL = "REL";
    /** 沿「商品→成分→营养素」这几条边走，用来把用户所买的商品展开成活性物质 */
    private static final List<String> SUBSTANCE_RELATIONS = List.of("CONTAINS", "PROVIDES");
    /** 风险相关的关系。查询时按<b>无向</b>匹配，见 {@link #risksOf} */
    private static final List<String> RISK_RELATIONS = List.of("INTERACTS_WITH", "CAUTION_FOR");
    /** 邻域查询最大跳数。再深下去图上什么都连着什么，返回的图没有可读性 */
    private static final int MAX_DEPTH = 3;

    private final Driver driver;
    private final Duration queryTimeout;
    private final boolean enabled;

    private volatile boolean available;
    private volatile String unavailableReason;

    public KnowledgeGraphStore(boolean enabled, String uri, String username, String password,
                               int connectTimeoutMs, int queryTimeoutMs) {
        this.enabled = enabled;
        this.queryTimeout = Duration.ofMillis(queryTimeoutMs);
        Driver created = null;
        if (enabled) {
            try {
                created = GraphDatabase.driver(uri, AuthTokens.basic(username, password),
                        Config.builder()
                                .withConnectionTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                                .withMaxConnectionPoolSize(8)
                                .build());
            } catch (RuntimeException e) {
                // URI 写错之类，构造期就能发现。仍然不阻断启动，理由见类注释
                this.unavailableReason = "驱动初始化失败：" + e.getMessage();
                log.error("[Graph] 初始化失败，图谱功能不可用：{}", e.getMessage());
            }
        } else {
            this.unavailableReason = "envoymart.graph.enabled=false";
        }
        this.driver = created;
        if (driver != null) {
            ping();
            ensureSchema();
        }
    }

    /**
     * 建唯一约束。节点的键是 {@code :Entity(name)}，一切写入都靠 {@code MERGE} 找它。
     * <p>
     * <b>没有约束的 MERGE 是个谎</b>：Neo4j 的 MERGE 只有在存在唯一约束时才真正原子，
     * 否则两个并发事务各自找不到节点、各自创建一个同名的，而两边都不会报错。后果是图<b>静默裂开</b>
     * ——查询走到其中一个节点，另一个节点的边全看不见，表现成「这个商品有时查得到相互作用、
     * 有时查不到」。这正是图谱最不该有的失败模式，因为它看起来像模型不稳定。
     * <p>
     * 顺带解决性能：无索引的 {@code MERGE ... {name}} 走的是整个标签的全量扫描，
     * 每写一条边扫一次全图。加了索引之后是一次查找。
     * <p>
     * 失败不影响可用性：约束建不上（比如已有重复数据）时图谱仍能读写，只是退回上面的状态，
     * 所以这里只记日志不阻断。
     */
    private void ensureSchema() {
        try (Session session = driver.session()) {
            session.run("CREATE CONSTRAINT entity_name_unique IF NOT EXISTS "
                    + "FOR (e:Entity) REQUIRE e.name IS UNIQUE").consume();
        } catch (RuntimeException e) {
            log.warn("[Graph] 唯一约束未能建立，MERGE 将退化为全标签扫描且并发下可能产生重复节点：{}",
                    e.getMessage());
        }
    }

    /**
     * 探活。启动时调一次；之后每次 {@link #isAvailable()} 失败时会重试——
     * 「服务比 Neo4j 先起来」是很常见的顺序，只探一次会把晚启动的 Neo4j 判成永久不可用。
     */
    public void ping() {
        if (driver == null) {
            return;
        }
        try {
            driver.verifyConnectivity();
            if (!available) {
                log.info("[Graph] 知识图谱已连接");
            }
            available = true;
            unavailableReason = null;
        } catch (RuntimeException e) {
            available = false;
            unavailableReason = e.getMessage();
            log.error("[Graph] Neo4j 连不上，图谱检索与图谱接口将返回不可用：{}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        if (!available) {
            ping();
        }
        return available;
    }

    /** 不可用原因，供接口回传给前端。可用时返回 null */
    public String unavailableReason() {
        return isAvailable() ? null : unavailableReason;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private TransactionConfig txConfig() {
        return TransactionConfig.builder().withTimeout(queryTimeout).build();
    }

    // ==================== 写入 ====================

    /**
     * 用一篇文档的三元组<b>整体替换</b>它在此文档上的贡献：先删该文档产生的全部边，再写新的。
     * <p>
     * 幂等靠这个「先删后写」而不是靠 MERGE 去重。重建索引会重跑抽取，
     * 而模型每次给的说法可能有出入（同一条边换个措辞、多一条少一条），
     * 靠 MERGE 会把历次结果越堆越多，图上出现一堆<b>已经不在文档里的旧边</b>，
     * 而且没有任何办法分辨哪些是旧的。
     * <p>
     * 节点不删：同名节点可能同时被别的文档支持。清理由 {@link #dropOrphanEntities()} 收尾。
     */
    public void replaceDocument(String docId, List<GroundedTriple> triples) {
        if (!isAvailable()) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (:Entity)-[r:REL {docId: $docId}]->(:Entity) DELETE r", Map.of("docId", docId));
                for (GroundedTriple t : triples) {
                    tx.run("""
                            MERGE (h:Entity {name: $head})
                            SET h.label = $headLabel, h.kind = $headKind
                            MERGE (t:Entity {name: $tail})
                            SET t.label = $tailLabel, t.kind = $tailKind
                            CREATE (h)-[:REL {
                                relation: $relation, effect: $effect, docId: $docId,
                                chunkId: $chunkId, quoteStart: $quoteStart, quoteEnd: $quoteEnd,
                                quote: $quote
                            }]->(t)
                            """, params(t));
                }
                return null;
            }, txConfig());
        } catch (RuntimeException e) {
            // 写失败不能让它静默过去：文档索引标记成功、图上却什么都没写，
            // 表现是「检索正常但一问相互作用就答不知道」，而日志里一片安静。
            //
            // 原因链写进消息本身：调用方（GraphService → 控制器 → 全局异常处理器）
            // 一路只保留 message，cause 在日志里根本不出现。第一版只写了
            // 「图谱写入失败 docId=KB-0006」，排查时只知道失败、不知道失败在哪一步，
            // 只能靠手工在 cypher-shell 里重放 Cypher——而重放是能通过的。
            throw new IllegalStateException("图谱写入失败 docId=" + docId + "：" + rootCause(e), e);
        }
        log.info("[Graph] 文档 {} 图谱重建：写入 {} 条关系", docId, triples.size());
    }

    /**
     * 取最内层异常的描述。Neo4j 的异常经常套三到四层
     * （{@code Neo4jException} → {@code ClientException} → {@code DatabaseException}），
     * 有用的一句在<b>最里面</b>；只取最外层的 message 常常是
     * 「Transaction failed」这种没有任何信息量的总括。
     */
    private static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    /** 删除已无任何文档支持的孤立实体。整批重建后调用一次 */
    public void dropOrphanEntities() {
        if (!isAvailable()) {
            return;
        }
        try (Session session = driver.session()) {
            long removed = session.executeWrite(tx -> tx.run("""
                    MATCH (n:Entity) WHERE NOT (n)--()
                    DELETE n RETURN count(n) AS removed
                    """).single().get("removed").asLong(), txConfig());
            if (removed > 0) {
                log.info("[Graph] 清理孤立实体 {} 个", removed);
            }
        } catch (RuntimeException e) {
            log.warn("[Graph] 孤立实体清理失败，不影响检索：{}", e.getMessage());
        }
    }

    private Map<String, Object> params(GroundedTriple t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("head", t.headName());
        m.put("headLabel", t.headLabel());
        m.put("headKind", t.headKind().name());
        m.put("tail", t.tailName());
        m.put("tailLabel", t.tailLabel());
        m.put("tailKind", t.tailKind().name());
        m.put("relation", t.relation().name());
        m.put("effect", t.effect());
        m.put("docId", t.docId());
        m.put("chunkId", t.chunkId());
        m.put("quoteStart", t.quoteStart());
        m.put("quoteEnd", t.quoteEnd());
        m.put("quote", t.quote());
        return m;
    }

    // ==================== 读取 ====================

    /**
     * 以某个实体为中心取邻域，供前端画图。
     * <p>
     * 无向匹配：用户从商品页点进图谱时想看到的是「这个东西跟什么有关」，
     * 而关系在存储上是有向的（商品→成分）。按方向匹配会让一半的边看不见。
     */
    public List<GraphEdge> neighborhood(String name, int depth) {
        if (!isAvailable() || name == null || name.isBlank()) {
            return List.of();
        }
        String key = TripleValidator.normalizeName(name);
        int hops = Math.max(1, Math.min(depth, MAX_DEPTH));
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                // 跳数直接拼进 Cypher 而不是当参数：变长路径的长度是语法的一部分，
                // 不能参数化。hops 已在上方夹到 [1,3]，拼进来的是个数字，不是用户输入
                var result = tx.run("""
                        MATCH (c:Entity {name: $name})-[rels:REL*1..%d]-(o:Entity)
                        UNWIND rels AS r
                        WITH DISTINCT r
                        RETURN %s
                        LIMIT 200
                        """.formatted(hops, EDGE_COLUMNS), Map.of("name", key));
                List<GraphEdge> edges = new ArrayList<>();
                while (result.hasNext()) {
                    edges.add(toEdge(result.next()));
                }
                return edges;
            }, txConfig());
        } catch (RuntimeException e) {
            log.warn("[Graph] 邻域查询失败 name={}：{}", name, e.getMessage());
            return List.of();
        }
    }

    /** 实体检索，供搜索框与「图谱里到底有没有这个词」的排查使用 */
    public List<GraphNode> searchEntities(String keyword, int limit) {
        if (!isAvailable()) {
            return List.of();
        }
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (n:Entity)
                        WHERE $kw = '' OR n.name CONTAINS $kw OR toLower(n.label) CONTAINS $kw
                        RETURN n.name AS name, n.label AS label, n.kind AS kind
                        ORDER BY size(n.label)
                        LIMIT $limit
                        """, Map.of("kw", kw, "limit", Math.max(1, Math.min(limit, 100))));
                List<GraphNode> nodes = new ArrayList<>();
                while (result.hasNext()) {
                    nodes.add(toNode(result.next()));
                }
                return nodes;
            }, txConfig());
        } catch (RuntimeException e) {
            log.warn("[Graph] 实体检索失败 kw={}：{}", keyword, e.getMessage());
            return List.of();
        }
    }

    /**
     * 把用户手上的几样东西展开成「活性物质」集合 ——
     * 商品沿 {@code CONTAINS} / {@code PROVIDES} 走最多三跳。
     * <p>
     * 这一步是相互作用查询的<b>前半段</b>，单独拿出来是因为它本身就是答案的一部分：
     * 用户只说了「鱼油软胶囊」，而风险来自里面的 EPA。把展开结果返回给前端，
     * 演示时能直接看到「我们把你买的那瓶鱼油拆成了 EPA 和 DHA」，
     * 而不是让系统凭空说出一个用户没提过的名词。
     *
     * @param names 用户提到的实体键（SPU 编号、成分名、营养素名混在一起都行）
     * @return 每个可达实体一条记录，{@code chain} 是从用户输入到它的中文名链
     */
    public List<Substance> expandSubstances(List<String> names) {
        if (!isAvailable() || names == null || names.isEmpty()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                // 零跳也保留（*0..3）：用户可能直接说药名或成分名，那它自己就是待检查的物质，
                // 而且零跳命中是「图谱里到底有没有这个东西」的唯一判据
                var result = tx.run("""
                        MATCH path = (start:Entity)-[rels:REL*0..3]->(a:Entity)
                        WHERE start.name IN $names
                          AND ALL(r IN rels WHERE r.relation IN $allowed)
                        RETURN DISTINCT start.name AS rootName, start.label AS rootLabel,
                               a.name AS name, a.label AS label, a.kind AS kind,
                               [n IN nodes(path) | n.label] AS chain
                        """, Map.of("names", names, "allowed", SUBSTANCE_RELATIONS));
                List<Substance> out = new ArrayList<>();
                while (result.hasNext()) {
                    Record r = result.next();
                    out.add(new Substance(r.get("rootName").asString(), r.get("rootLabel").asString(),
                            r.get("name").asString(), r.get("label").asString(),
                            r.get("kind").asString(null), r.get("chain").asList(Value::asString)));
                }
                return out;
            }, txConfig());
        } catch (RuntimeException e) {
            log.warn("[Graph] 物质展开失败 names={}：{}", names, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从给定物质出发找风险关系（相互作用 / 人群禁忌）。
     * <p>
     * 无向匹配：{@code INTERACTS_WITH} 本来就没有方向（谁和谁一起吃有问题是双向的），
     * 而抽取时模型把哪一头写在前全凭语序。按方向匹配会让「鱼油-华法林」和
     * 「华法林-鱼油」变成两种不同的结果，症状是<b>换个问法就查不到</b>。
     */
    public List<GraphEdge> risksOf(List<String> names) {
        if (!isAvailable() || names == null || names.isEmpty()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (a:Entity)-[r:REL]-(b:Entity)
                        WHERE a.name IN $names AND r.relation IN $allowed
                        RETURN %s
                        ORDER BY r.docId
                        """.formatted(EDGE_COLUMNS), Map.of("names", names, "allowed", RISK_RELATIONS));
                List<GraphEdge> edges = new ArrayList<>();
                while (result.hasNext()) {
                    edges.add(toEdge(result.next()));
                }
                return edges;
            }, txConfig());
        } catch (RuntimeException e) {
            log.warn("[Graph] 风险关系查询失败 names={}：{}", names, e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> stats() {
        if (!isAvailable()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", false);
            out.put("reason", unavailableReason);
            return out;
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                long entities = tx.run("MATCH (n:Entity) RETURN count(n) AS c").single().get("c").asLong();
                long relations = tx.run("MATCH ()-[r:REL]->() RETURN count(r) AS c").single().get("c").asLong();
                Map<String, Long> byRelation = new LinkedHashMap<>();
                var rels = tx.run("MATCH ()-[r:REL]->() RETURN r.relation AS relation, count(*) AS c ORDER BY c DESC");
                while (rels.hasNext()) {
                    Record r = rels.next();
                    byRelation.put(r.get("relation").asString(), r.get("c").asLong());
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("available", true);
                out.put("entities", entities);
                out.put("relations", relations);
                out.put("byRelation", byRelation);
                return out;
            }, txConfig());
        } catch (RuntimeException e) {
            log.warn("[Graph] 统计失败：{}", rootCause(e));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", false);
            out.put("reason", rootCause(e));
            return out;
        }
    }

    /**
     * 边的统一返回列。
     * <p>
     * 两端都返回 name 与 label：读写各处的位置参数必须和这里逐一对上，
     * 而 {@code toEdge} 是按列名取的，列名写错在编译期看不出来、运行时才抛。
     * 把它收敛成一个常量，至少保证只有一处需要改。
     */
    private static final String EDGE_COLUMNS = """
            startNode(r).name AS head, startNode(r).label AS headLabel,
            startNode(r).kind AS headKind, r.relation AS relation, r.effect AS effect,
            endNode(r).name AS tail, endNode(r).label AS tailLabel,
            endNode(r).kind AS tailKind, r.docId AS docId, r.chunkId AS chunkId,
            r.quoteStart AS quoteStart, r.quoteEnd AS quoteEnd, r.quote AS quote
            """;

    private GraphEdge toEdge(Record r) {
        return new GraphEdge(
                toNode(r, "head"), r.get("relation").asString(), r.get("effect").asString(null),
                toNode(r, "tail"), r.get("docId").asString(null), null,
                r.get("chunkId").asString(null), r.get("quoteStart").asInt(0), r.get("quoteEnd").asInt(0),
                // counterpart 由相互作用查询按「哪一端是用户问的那样东西」补；
                // 普通邻域查询没有这个视角，留空
                r.get("quote").asString(null), null, List.of());
    }

    private GraphNode toNode(Record r, String prefix) {
        return new GraphNode(r.get(prefix).asString(), r.get(prefix + "Label").asString(null),
                r.get(prefix + "Kind").asString(null));
    }

    /** 按行取节点，列名形如 {@code name/label/kind} */
    private GraphNode toNode(Record r) {
        return new GraphNode(r.get("name").asString(), r.get("label").asString(null),
                r.get("kind").asString(null));
    }

    @Override
    public void destroy() {
        if (driver != null) {
            driver.close();
        }
    }
}
