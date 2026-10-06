package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单篇知识索引重建的结果 —— 由 ai-service 发出，product-service 消费。
 * <p>
 * 之所以要这个契约类型，而不是让调用方读一个 Map：
 * 「重建失败了没有」是商品上下架联动里唯一需要判断的事，
 * 而 Map 会在字段名改动时静默给出 null，恰好把「失败」与「没读到」变成同一个值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexResult {
    private String docNo;
    /** 只删不写：文档已停用/删除，把它的向量与图谱边清掉 */
    private boolean deletedOnly;
    private int chunkCount;
    private int totalChunks;
    /** 图谱是否随之更新。false 通常意味着 Neo4j 不可用或抽取失败 */
    private boolean graphUpdated;
    /** 失败原因。非空即表示这一篇没重建成功 */
    private String error;
}