package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 索引重建任务的当前状态 —— 由 ai-service 发出，product-service 消费。
 * <p>
 * 单篇重建要调一次模型抽关系，实测约 120 秒，超过调用方的读超时，
 * 所以服务间链路改成「发起即返回、状态另查」。这个契约就是那个「状态」。
 * <p>
 * 不直接复用 ai-service 内部的 {@code KnowledgeIndexer.Status}：
 * 那会让 product-service 依赖一个它不该知道的实现类型，且字段一改就静默反序列化失败。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexStatus {
    /** 是否正在重建。true 表示「已发起、还没跑完」 */
    private boolean running;
    private Instant startedAt;
    /** 跑完的时刻；还在跑时为 null */
    private Instant finishedAt;
    /** 上次失败的原因；成功或还没跑过时为 null */
    private String error;
}