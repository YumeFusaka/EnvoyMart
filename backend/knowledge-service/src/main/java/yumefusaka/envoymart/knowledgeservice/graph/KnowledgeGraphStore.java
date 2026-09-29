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
import yumefusaka.envoymart.contract.GraphEdge;
import yumefusaka.envoymart.contract.GraphNode;
import yumefusaka.envoymart.contract.Substance;

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
    /**
     * 图谱召回单次返回的组成关系上限。
     * <p>
     * 只加在<b>召回</b>路径上，不加在 {@code risksOf} 上：召回多一条少一条只是
     * 候选池大小的差别，而相互作用查询的结果是要直接下结论的，
     * 在那里截断等于悄悄漏掉一条风险。
     */
    private static final int MAX_RECALL_EDGES = 40;

    private final Driver driver;
    private final Duration queryTimeout;
    private final Duration writeTimeout;
    private final boolean enabled;

    private volatile boolean available;
    private volatile String unavailableReason;

    public KnowledgeGraphStore(boolean enabled, String uri, String username, String password,
                               int connectTimeoutMs, int queryTimeoutMs, int writeTimeoutMs) {
        this.enabled = enabled;
        this.queryTimeout = Duration.ofMillis(queryTimeoutMs);
        this.writeTimeout = Duration.ofMillis(writeTimeoutMs);
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
                // 走到这个分支说明此前有过一段不可用期。约束是**构造期**建的那一次，
                // 若那时 Neo4j 还没起来，{@code ensureSchema} 当时只留了一条 warn，
                // 之后再也不会执行——于是整个进程生命周期里 MERGE 都退化成全标签扫描，
                // 并发下还会产出重名节点。恢复连接时补一次，成本一条 DDL
                ensureSchema();
            }
            available = true;
            unavailableReason = null;
        } catch (RuntimeException e) {
            available = false;
            unavailableReason = rootCause(e);
            log.error("[Graph] Neo4j 连不上，图谱检索与图谱接口将返回不可用：{}", unavailableReason);
        }
    }

    public boolean isAvailable() {
        if (!available) {
            ping();
        }
        return available;
    }

    /**
     * 把「这次读失败了」如实反映到可用性标志上。
     * <p>
     * <b>不这么做的话这个标志就是个谎</b>：它只在 {@link #ping()} 里改成 false，
     * 而 ping 只在 false 时才重跑——启动时探活一旦成功，这个 true 就再也不会被推翻。
     * 于是 Neo4j 中途挂掉之后，四个读方法各自 catch 住异常返回空列表，
     * 而接口照样按「图谱可用」渲染，用户看到的是「没有查到风险」——
     * 故障被读成了一个<b>相反的结论</b>，这正是本类开篇说要避免的那件事。
     * <p>
     * 翻转之后下一次 {@link #isAvailable()} 会自动重探并自愈，代价是一次
     * {@code verifyConnectivity}，所以宁可多翻几次也不要粘住。
     */
    private void markUnavailable(String what, RuntimeException e) {
        available = false;
        unavailableReason = rootCause(e);
        log.warn("[Graph] {} 失败，图谱标记为不可用：{}", what, unavailableReason);
    }

    /**
     * 不可用原因，供接口回传给前端。可用时返回 null。
     * <p>
     * 只回一句通用文案：原始消息里有 {@code 127.0.0.1:7687} 这类内网拓扑，
     * 而 {@code /knowledge/graph/stats} 是登录用户就能调的公开接口，
     * 与 {@code GlobalExceptionHandler} 里写下的「不向调用方泄漏内网拓扑」直接冲突。
     * 详情进日志——需要看它的人在服务端，不在浏览器里
     */
    public String unavailableReason() {
        return isAvailable() ? null : "知识图谱暂不可用";
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 读事务：短超时，几毫秒到几秒。理由见 {@link #writeConfig()} */
    private TransactionConfig txConfig() {
        return TransactionConfig.builder().withTimeout(queryTimeout).build();
    }

    /**
     * 写事务：长超时，与读<b>分开</b>。
     * <p>
     * 两者共用一个 5 秒预算，实际上真的出过事：{@link #replaceDocument} 原先在一个事务里
     * 对每条三元组各发两条语句（MERGE 头、MERGE 尾、CREATE 边），11 条的文档就是二十多条
     * 语句全挤在同一个预算里。实测重建索引时 KB-0005 撞上超时，Neo4j 报
     * "The transaction has not completed within the timeout specified at its start"，
     * <b>整篇文档的证据一条都没进图</b>——而调用方只看到「未入库 1 篇」，
     * 索引侧一切正常，检索照常命中，只有问到相互作用时才表现为「不知道」。
     * <p>
     * 这两件事本来就该用不同的预算：读在<b>用户请求路径</b>上，超时越短越好，
     * 宁可快速回一句「图谱暂时不可用」也不能拖住整个检索；写是<b>后台批量任务</b>，
     * 被中途掐断等于白跑一趟，宁可多等。一个常量服务不了两种相反的要求。
     */
    private TransactionConfig writeConfig() {
        return TransactionConfig.builder().withTimeout(writeTimeout).build();
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
        try {
            executeReplace(docId, triples);
        } catch (RuntimeException first) {
            // 用一个**新事务**重试一次。Neo4j 的超时错误自己就写着这句
            // （"Retry your operation in a new transaction"）：被终止的是那个事务，
            // 不是这次写入。本方法是「先删后写」所以天然幂等，重来一遍没有副作用；
            // 而重建索引是后台任务，多花几百毫秒换一篇文档的证据，划算。
            //
            // 这一层是兜底：主因（一个事务里塞二十多条语句）已由 UNWIND 消掉，
            // 但它挡不住网络抖动和服务端 GC 这类偶发。
            log.warn("[Graph] 文档 {} 第 1 次写入失败，重试一次：{}", docId, rootCause(first));
            try {
                executeReplace(docId, triples);
            } catch (RuntimeException second) {
                // 写失败不能让它静默过去：文档索引标记成功、图上却什么都没写，
                // 表现是「检索正常但一问相互作用就答不知道」，而日志里一片安静。
                //
                // 原因链写进消息本身：调用方（GraphService → 控制器 → 全局异常处理器）
                // 一路只保留 message，cause 在日志里根本不出现。第一版只写了
                // 「图谱写入失败 docId=KB-0006」，排查时只知道失败、不知道失败在哪一步，
                // 只能靠手工在 cypher-shell 里重放 Cypher——而重放是能通过的。
                throw new IllegalStateException("图谱写入失败 docId=" + docId + "：" + rootCause(second),
                        second);
            }
        }
        log.info("[Graph] 文档 {} 图谱重建：写入 {} 条关系", docId, triples.size());
    }

    /**
     * 一个事务内完成「删旧边 + 写新边」。
     * <p>
     * 新的边用<b>一条 {@code UNWIND}</b> 写入，不是每条三元组发一轮语句。
     * 原先 11 条三元组的文档要发二十多条语句（每条 4 条：MERGE 头、MERGE 尾、CREATE 边，
     * 再加一次 DELETE），全部串行跑在同一个事务预算里，往返次数直接乘上文档规模——
     * 这正是超时被撞爆的实际原因。UNWIND 把「每条一次往返」压成「整篇一次往返」，
     * 语句数从 O(三元组) 降到 2，也顺带让整批写入在一个原子单位里完成。
     * <p>
     * 每一行仍然单独 MERGE 两端节点：同一篇文档里两个三元组共用一端是常态
     * （都指向同一个成分），MERGE 在事务内能看见前一行刚建的节点，不会重复建。
     */
    private void executeReplace(String docId, List<GroundedTriple> triples) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (:Entity)-[r:REL {docId: $docId}]->(:Entity) DELETE r",
                        Map.of("docId", docId)).consume();
                if (!triples.isEmpty()) {
                    tx.run("""
                            UNWIND $rows AS t
                            MERGE (h:Entity {name: t.head})
                            SET h.label = t.headLabel, h.kind = t.headKind
                            MERGE (e:Entity {name: t.tail})
                            SET e.label = t.tailLabel, e.kind = t.tailKind
                            CREATE (h)-[:REL {
                                relation: t.relation, effect: t.effect, docId: t.docId,
                                chunkId: t.chunkId, quoteStart: t.quoteStart, quoteEnd: t.quoteEnd,
                                quote: t.quote
                            }]->(e)
                            """, Map.of("rows", triples.stream().map(this::params).toList())).consume();
                }
                return null;
            }, writeConfig());
        }
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
            markUnavailable("邻域查询 name=" + name, e);
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
            markUnavailable("实体检索 kw=" + keyword, e);
            return List.of();
        }
    }

    /**
     * 从一段自由文本里认出图上已有的实体 —— 实体链接。
     * <p>
     * <b>词典匹配，不是语义匹配</b>：词典就是图上的全部实体（name 与 label 两套写法），
     * 判据是「规范化后的文本里是否包含规范化后的实体名」。之所以够用，是因为它服务的
     * 是<b>召回</b>而不是判定——多认出一个实体只会多带一条候选依据进检索池，
     * 由重排器决定要不要；漏认的代价只是这次少一条图谱依据。两者都是软的。
     * （{@code interactions} 那条路径上的解析就完全不同：它每次都要下结论，
     * 所以那里只认精确匹配。）
     * <p>
     * <b>长度下限 2</b>：单字实体（{@code 钙}）会在几乎任何句子里命中，
     * 而它带来的是一条与问题无关的依据——图谱依据一旦看着像噪声，
     * 后面的「可溯源」就没有说服力了。
     * <p>
     * <b>规模上限写在明处</b>：每次查询都要把全图实体取回来做包含判断。
     * 十几到几百个实体是毫秒级；到几千个时该换成写入时维护一份内存词典
     * （多一份要与图同步的状态，非必要不引入），再往上才是 AC 自动机那一类。
     *
     * @return 命中的实体键，按名字长度降序——长名更具体，先匹配到长的能避免
     *         {@code 深海鱼油} 被 {@code 鱼油} 抢先
     */
    public List<String> linkEntities(String text) {
        if (!isAvailable() || text == null || text.isBlank()) {
            return List.of();
        }
        String haystack = TripleValidator.normalizeName(text);
        if (haystack.isEmpty()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("MATCH (n:Entity) RETURN n.name AS name, n.label AS label");
                record Candidate(String name, String needle) {
                }
                List<Candidate> candidates = new ArrayList<>();
                while (result.hasNext()) {
                    Record r = result.next();
                    String name = r.get("name").asString();
                    if (matches(haystack, name)) {
                        candidates.add(new Candidate(name, name));
                    }
                    String label = r.get("label").asString(null);
                    if (label != null && !TripleValidator.normalizeName(label).equals(name)
                            && matches(haystack, label)) {
                        candidates.add(new Candidate(name, TripleValidator.normalizeName(label)));
                    }
                }
                // 长的名字更具体，排在前面：同一次召回里先放「深海鱼油」，再放「鱼油」
                candidates.sort((a, b) -> Integer.compare(b.needle().length(), a.needle().length()));
                return candidates.stream().map(Candidate::name).distinct().toList();
            }, txConfig());
        } catch (RuntimeException e) {
            markUnavailable("实体链接 text=" + text, e);
            return List.of();
        }
    }

    /** 实体名要在文本里<b>完整出现</b>，且长度过短的一律不要（见 {@link #linkEntities}） */
    private static boolean matches(String haystack, String candidate) {
        String needle = TripleValidator.normalizeName(candidate);
        return needle.length() >= 2 && haystack.contains(needle);
    }

    /**
     * 把调用方给出的写法解析成图谱的节点键。
     * <p>
     * <b>为什么需要这一步</b>：商品节点的键是 SPU 编号（{@code spu5}），而用户和模型
     * 嘴里说的是「鱼油软胶囊」。不解这一层的话，{@code interactions(鱼油软胶囊)}
     * 会老老实实返回 {@code found=false}——在界面上就是「图谱里没有收录」，
     * 读起来离「没查到风险」只差一步。而这正是本类开篇说要避免的那件事：
     * <b>把「没找到」说成「没问题」</b>。非商品实体不受影响，它们的键本来就是中文名。
     * <p>
     * <b>只认精确匹配，不做子串猜测</b>。用户说「鱼油」时，「鱼油软胶囊」与「深海鱼油」
     * 都是候选，猜错任何一个都会把答案引到另一个东西的成分与风险上——一个错误的
     * 相互作用结论，比「没有收录」有害得多。子串猜测留给调用方：模型手上有
     * {@code product_search}，让它拿到准确商品名/编号再问一次，比这里替它赌一把强。
     *
     * @param inputs 规范化后的输入（{@code EntityNames.normalize} 的产物）
     * @return 输入 → 节点键。解析不到的项<b>不在返回值里</b>，调用方据此报「没有收录」
     */
    public Map<String, String> resolveKeys(List<String> inputs) {
        if (!isAvailable() || inputs == null || inputs.isEmpty()) {
            return Map.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                // 一次查完两种匹配：键命中（成分/药物/营养素，或直接给了 SPU 编号）
                // 与展示名命中（用户说了商品名）。两者都只做相等比较——见上面的理由
                var result = tx.run("""
                        MATCH (n:Entity)
                        WHERE n.name IN $inputs OR toLower(n.label) IN $inputs
                        RETURN n.name AS name, n.label AS label
                        """, Map.of("inputs", inputs));
                List<String> names = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                while (result.hasNext()) {
                    Record r = result.next();
                    names.add(r.get("name").asString());
                    labels.add(r.get("label").asString(null));
                }
                Map<String, String> resolved = new LinkedHashMap<>();
                for (String input : inputs) {
                    // 键优先：同一个写法既是某节点的键、又是另一节点的展示名时，
                    // 键那一侧才是调用方真正指的东西
                    if (names.contains(input)) {
                        resolved.put(input, input);
                        continue;
                    }
                    for (int i = 0; i < labels.size(); i++) {
                        // 比较用的是规范化后的展示名，与建键时同一套规则。
                        // 只比较原始字符串的话，「维生素 D3」这种带空格的标签会漏掉
                        if (input.equals(TripleValidator.normalizeName(labels.get(i)))) {
                            resolved.put(input, names.get(i));
                            break;
                        }
                    }
                }
                return resolved;
            }, txConfig());
        } catch (RuntimeException e) {
            markUnavailable("节点键解析 inputs=" + inputs, e);
            return Map.of();
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
            markUnavailable("物质展开 names=" + names, e);
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
            markUnavailable("风险关系查询 names=" + names, e);
            return List.of();
        }
    }

    /**
     * 从给定实体出发的组成关系（商品 → 成分 → 营养素），<b>按方向匹配</b>。
     * <p>
     * 与 {@link #risksOf} 的无向匹配刚好相反，因为这两类关系的方向性质不同：
     * 相互作用是谁和谁一起吃都成立，而「本品主要成分为深海鱼油」只在商品那一头说得通。
     * 按无向匹配会把「哪些商品含有 EPA」这种反向结果也捞进来——那些是选品信息，
     * 不是用户问「我买的这个是什么」时要看的东西。
     * <p>
     * 这条边本身也是<b>依据</b>：用户问「SPU5 能不能和华法林一起吃」，
     * 说明书里「本品主要成分为深海鱼油」那一条正是答案的开头——它解释了
     * 后面那条风险为什么和用户手上的东西有关。
     */
    public List<GraphEdge> compositionOf(List<String> names) {
        if (!isAvailable() || names == null || names.isEmpty()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (a:Entity)-[r:REL]->(b:Entity)
                        WHERE a.name IN $names AND r.relation IN $allowed
                        RETURN %s
                        ORDER BY r.docId
                        LIMIT $limit
                        """.formatted(EDGE_COLUMNS),
                        Map.of("names", names, "allowed", SUBSTANCE_RELATIONS, "limit", MAX_RECALL_EDGES));
                List<GraphEdge> edges = new ArrayList<>();
                while (result.hasNext()) {
                    edges.add(toEdge(result.next()));
                }
                return edges;
            }, txConfig());
        } catch (RuntimeException e) {
            markUnavailable("组成关系查询 names=" + names, e);
            return List.of();
        }
    }

    public Map<String, Object> stats() {
        if (!isAvailable()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", false);
            out.put("reason", unavailableReason());
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
            markUnavailable("统计查询", e);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", false);
            out.put("reason", unavailableReason());
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
