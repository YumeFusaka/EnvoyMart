package yumefusaka.envoymart.productservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.KnowledgeIndexStatus;
import yumefusaka.envoymart.productservice.client.KnowledgeClient;
import yumefusaka.envoymart.productservice.client.KnowledgeIndexClient;

import java.util.List;

/**
 * 商品上下架 → 说明书同步下架/上架。
 * <p>
 * <b>这是「商品下架了，说明书还在被检索到」的修法。</b>在此之前，商品与说明书之间
 * 只有图谱上一条模型抽出来的边，于是商品下架时系统不知道该停谁——四格里这一格全空。
 * 现在归属记在关联表里，反查是一次主键查询。
 * <p>
 * <b>三步，顺序不能换：</b>
 * <ol>
 *   <li>知识库先改文档状态（事实源）——它决定这一篇还在不在语料里；</li>
 *   <li>再逐篇重建索引——向量与图谱是派生副本，重建时才按新的启用状态取舍。
 *       这一步是<b>异步</b>的：单篇重建要走一次模型抽取（约 120 秒），
 *       远超本调用的读超时，所以 ai-service 发起即返回，这里只确认「已发起」；</li>
 *   <li>整个动作在<b>事务提交之后</b>做。</li>
 * </ol>
 * 顺序反了的话，重建会拿着「还没停用」的语料跑一遍，把旧内容又写回索引，
 * 症状是「下架了但还搜得到，过一会儿才好」。
 * <p>
 * <b>为什么放在提交之后</b>：在事务里调用的话，一旦事务回滚，知识库那边已经停用了，
 * 而商品还是上架的——一个不会报错的不一致。
 */
@Slf4j
@Component
public class KnowledgeLifecycleSync {

    private final KnowledgeClient knowledgeClient;
    private final KnowledgeIndexClient indexClient;

    public KnowledgeLifecycleSync(KnowledgeClient knowledgeClient, KnowledgeIndexClient indexClient) {
        this.knowledgeClient = knowledgeClient;
        this.indexClient = indexClient;
    }

    /**
     * 提交后把上下架同步到知识层。
     *
     * @param on true 上架 / false 下架
     */
    public void afterCommit(Long spuId, boolean on) {
        if (spuId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sync(spuId, on);
                }
            });
        } else {
            sync(spuId, on);
        }
    }

    /**
     * 真正的同步。
     * <p>
     * <b>失败只记日志、不抛出。</b>这一段的调用方是「商品上下架」这个已经成功的事务，
     * 知识层没跟上不该让运营看到「下架失败」——那会让他再点一次，而商品确实是下架了。
     * 代价是可能留下一份没同步的说明书，所以日志必须打到能被人搜到的级别，
     * 且覆盖度检查会把它列出来。
     */
    private void sync(Long spuId, boolean on) {
        List<String> changed;
        try {
            Result<List<String>> result = knowledgeClient.syncProductStatus(spuId, on);
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                log.warn("[Knowledge] 商品 {} {} 的知识层同步失败：{}",
                        spuId, on ? "上架" : "下架", result == null ? "无响应" : result.getMsg());
                return;
            }
            changed = result.getData();
        } catch (Exception e) {
            log.warn("[Knowledge] 商品 {} {} 的知识层同步调用异常，说明书可能仍是旧状态",
                    spuId, on ? "上架" : "下架", e);
            return;
        }
        if (changed == null || changed.isEmpty()) {
            log.info("[Knowledge] 商品 {} {}，没有说明书需要随之变动", spuId, on ? "上架" : "下架");
            return;
        }
        for (String docNo : changed) {
            try {
                Result<KnowledgeIndexStatus> r = indexClient.reindexOne(docNo);
                KnowledgeIndexStatus data = r == null ? null : r.getData();
                if (data == null || !data.isRunning() && data.getError() != null) {
                    // 非 running 且带 error：这一篇的重建确实失败了（模型或图谱侧的问题）
                    log.warn("[Knowledge] 文档 {} 索引重建报错：{}", docNo,
                            data == null ? "无响应" : data.getError());
                } else {
                    // running=true 是正常返回：单篇重建要走一次模型抽取，远超本调用的读超时，
                    // 所以 ai-service 改成了「受理即返回」，且在忙时排队而不是丢弃——
                    // 这里只确认它已被受理，进度由 /ai/admin/knowledge/status 观察
                    log.info("[Knowledge] 文档 {} 索引已随商品 {} 发起重建（异步）", docNo, on ? "上架" : "下架");
                }
            } catch (Exception e) {
                log.warn("[Knowledge] 文档 {} 索引重建发起失败，事实源已改、副本待下次重建追平", docNo, e);
            }
        }
    }
}