package yumefusaka.envoymart.agent.rag;

import lombok.Builder;
import lombok.Data;

/**
 * 文档切片 —— 向量化和检索的基本粒度。
 * <p>
 * 它同时承担两件事：<b>写入时</b>是向量化的输入，<b>读出时</b>是答案的证据。
 * 后者要求它必须携带足够的定位信息 —— 一个不能指回原文的切片，对「可追溯」
 * 这件事毫无价值。
 */
@Data
@Builder(toBuilder = true)
public class DocumentChunk {

    private String chunkId;
    private String docId;
    private String content;
    private int chunkIndex;

    /** 文档向量（Embedding 结果），维度由 `envoymart.embedding.dimemsion` 决定（默认 1024）。float 比 double 省一半空间。 */
    private float[] embedding;

    /**
     * BM25 索引文本；为空时退化为 {@link #content}。
     * <p>
     * 存在的理由：文档级检索要把标题与标签一并纳入关键词匹配（它们常含用户会说的词），
     * 但这些元信息不该出现在返回给模型的内容里。切片级检索由文档结构自带语境，
     * 留空即可。
     */
    private String indexText;

    // ==================== 记忆条目用（知识切片留空，取值见 MemoryItem.Type） ====================

    /**
     * 条目类型。
     * <p>
     * 记忆条目落库时必须一起写进去：读回来的那条路不走内存队列（进程重启后它是空的），
     * 只认向量库里的元数据，漏了它就只能拿默认值顶上——而默认值不会说"我不知道"，
     * 它说的是"这是一条普通事件"，于是按类型分层的保留策略在错误的前提上静默运行。
     */
    private String type;

    /** 条目写入时刻，epoch millis。Milvus 的元数据只收标量，时间以数字形态往返 */
    private Long timestamp;

    // ==================== 溯源用（检索时填充，不参与向量化） ====================

    /**
     * 文档标题。
     * <p>
     * 单独存一份而不是让调用方去查：答案里的引用要显示《文档名》，
     * 而检索结果到生成 prompt 之间没有别的机会拿到它。
     */
    private String title;

    /** 来源标识（manual / faq / policy / spec …）。同样是政策，厂商说明书与平台规则的可信度不同 */
    private String source;

    /**
     * {@link #source} 的取值之一：这条依据<b>不是文档里的某一段原文</b>，
     * 而是由图谱上的关系推出来的（正文里带着支撑它的逐字引文）。
     * <p>
     * 放在这里而不是检索器里，是因为<b>读它的人不止一个</b>：渲染要给用户换个说法
     * （说成「厂商说明书」会让用户以为自己看到的是原文），
     * 拒答门还要据此换一把尺子（见 {@link EvidenceGate}）。
     * 字符串写两遍，迟早只改一处。
     */
    public static final String SOURCE_GRAPH = "graph";

    /** 领域范围（promotion / after_sale / nutrition …），随切片一起下发供前端分类展示 */
    private String scope;

    /** 文档版本。同一份说明书会有多个版本，引用必须指明是哪一版 */
    private String version;

    /**
     * 切片在原文中的位置，形如 {@code 《维生素D3说明书》 > 第二章 > 3.2}。
     * <p>
     * 由结构分层切分时生成，随切片一起走完全程 —— 它是「点引用跳原文」的落点。
     */
    private String position;

    /** 文档内的字符偏移。位置文本给用户看，偏移给程序定位 */
    private Integer charOffset;

    /**
     * 相关性分数，取值 {@code [0,1]}，<b>跨查询可比</b>——拒答门（低于阈值就不答）的输入。
     * <p>
     * <b>只在下游有意义，不参与持久化</b>：它是「这一次查询下这个切片有多相关」，
     * 而不是切片的固有属性。
     * <p>
     * <b>这里刻意不暴露 RRF 融合分。</b>融合分的分值域是 {@code 1/(60+rank)}，
     * 第 1 名与第 5 名只差 1/60 与 1/64 —— 它是为<b>排序</b>设计的，
     * 绝对大小不表达「有多相关」，拿它当阈值等于拿名次当置信度。
     * 排序在融合阶段就已经完成，下游需要的是量纲稳定的相关性信号，
     * 所以这里透传的是两路里唯一的真相关性分：重排分（{@link #reranked} 为真）
     * 或向量余弦相似度（为假）。两路都没有给出分数时为 {@code null}。
     */
    private Double score;

    /**
     * {@link #score} 是否为重排（cross-encoder）分。
     * <p>
     * 必须与方法名一起看：重排分与余弦相似度都能归一到 {@code [0,1]} 且跨查询可比，
     * 但数值分布不同（cross-encoder 打分会更极端）。阈值调参要知道自己调的是哪一把尺子。
     */
    private Boolean reranked;
}
