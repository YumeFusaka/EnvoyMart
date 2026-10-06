package yumefusaka.envoymart.knowledgeservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeDocProductEntity;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeDocProductMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品-文档绑定。
 * <p>
 * <b>这张表要解决的是一件此前无人能回答的事</b>：「这篇文档属于哪个商品」、
 * 「这个商品有哪些说明书」。在此之前，绑定只存在于图谱里一条模型抽出来的边中，
 * 于是商品下架时系统不知道该停用哪几篇文档，覆盖率也说不清某个商品到底缺不缺资料。
 * <p>
 * <b>为什么单独一个服务而不是塞进 {@code KnowledgeDocumentService}</b>：
 * 文档的 CRUD 与「文档和商品的关系」是两件事，前者是知识库的事实源，
 * 后者是它到商品域的映射。混在一起会让文档服务多出一个对商品域的依赖，
 * 而它的其余职责（切分、检索语料）完全不知道商品的存在。
 */
@Slf4j
@Service
public class KnowledgeDocBindingService {

    private final KnowledgeDocProductMapper mapper;

    public KnowledgeDocBindingService(KnowledgeDocProductMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 商品上下架时，同步它名下**主体文档**的状态。
     * <p>
     * <b>这是「商品下架了，说明书还在被检索到」的唯一治本点。</b>在此之前，
     * 绑定只存在于图谱一条模型抽出来的边里，商品下架时系统不知道该停谁——
     * 四格里这一格全空。现在归属在关联表里，反查是一次主键查询。
     * <p>
     * <b>只动 role=SUBJECT 的行</b>：一篇政策文档顺带提过某个商品，不该因它下架而消失。
     * <p>
     * <b>上架只恢复 PRODUCT_OFF 的文档。</b>不区分原因的话，运营手动停用过的文档
     * 会在这里被悄悄打开——那是「没有人做过这个决定」的改动。
     *
     * @param on true 商品上架 / false 商品下架
     * @return 状态**真的发生变化**的文档编号。调用方据此只重建这几篇，
     *         而不是不管有没有变化都触发一次全量重建（那是分钟级 + 计费动作）
     */
    @Transactional
    public List<String> syncProductStatus(Long spuId, boolean on, KnowledgeDocumentService documentService) {
        List<String> docs = subjectDocsOf(spuId);
        List<String> changed = new ArrayList<>();
        for (String docNo : docs) {
            int target = on ? 1 : 0;
            int current = documentService.statusOf(docNo);
            if (current == target) {
                continue;
            }
            // 上架时只碰「因商品下架而停用」的：手动停用的保持停用，这是一条业务判据，
            // 不是优化——恢复一份被手动下架的说明书会重新引入运营已经判定为不该出现的内容
            if (on && !KnowledgeDocumentService.DISABLED_BY_PRODUCT_OFF.equals(documentService.disabledByOf(docNo))) {
                continue;
            }
            documentService.changeStatus(docNo, target,
                    on ? null : KnowledgeDocumentService.DISABLED_BY_PRODUCT_OFF);
            changed.add(docNo);
        }
        log.info("[Binding] 商品 {} {}，{} 篇主体文档状态随之变化：{}",
                spuId, on ? "上架" : "下架", changed.size(), changed);
        return changed;
    }

    /**
     * 声明「这些文档的主体是这些商品」。
     * <p>
     * 上传/编辑文档时由 {@code source=manual} 且运营指定了商品触发。
     * <b>整体替换这一篇的 SUBJECT 集合</b>：编辑文档时运营可能改了归属，
     * 不替换的话旧归属会留着，商品下架时连坐一篇已经不相关了的文档。
     * <p>
     * MENTIONED 不在这里管：它由图谱构建期从正文里抽出来，与人工声明是两条来源。
     *
     * @param docNo  文档编号
     * @param spuIds 归属的商品。空表示这篇文档不绑定任何商品（领域文档）
     * @param by     这条声明是谁下的（MANUAL / BACKFILL）
     */
    @Transactional
    public void declareSubjects(String docNo, List<Long> spuIds, KnowledgeDocProductEntity.MatchedBy by) {
        mapper.delete(Wrappers.<KnowledgeDocProductEntity>lambdaQuery()
                .eq(KnowledgeDocProductEntity::getDocNo, docNo)
                .eq(KnowledgeDocProductEntity::getRole, KnowledgeDocProductEntity.Role.SUBJECT.name()));
        if (spuIds == null || spuIds.isEmpty()) {
            log.info("[Binding] 文档 {} 不绑定商品（领域文档或运营未指定）", docNo);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<Long> distinct = spuIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        for (Long spuId : distinct) {
            KnowledgeDocProductEntity row = new KnowledgeDocProductEntity();
            row.setDocNo(docNo);
            row.setSpuId(spuId);
            row.setRole(KnowledgeDocProductEntity.Role.SUBJECT.name());
            row.setMatchedBy(by.name());
            row.setMatchedAt(now);
            mapper.insert(row);
        }
        log.info("[Binding] 文档 {} 的 SUBJECT 商品声明为 {}（来源 {}）", docNo, distinct, by);
    }

    /** 删除一篇文档的全部关联。文档被真正删除时调用（停用不删关联） */
    @Transactional
    public void removeAll(String docNo) {
        mapper.delete(Wrappers.<KnowledgeDocProductEntity>lambdaQuery()
                .eq(KnowledgeDocProductEntity::getDocNo, docNo));
    }

    /**
     * 某个商品的主体文档（说明书）。商品下架时要连坐的就是这些。
     * <p>
     * <b>只取 SUBJECT</b>：一篇政策文档里顺带提过某个商品，不该因它下架而消失。
     */
    public List<String> subjectDocsOf(Long spuId) {
        return mapper.selectList(Wrappers.<KnowledgeDocProductEntity>lambdaQuery()
                        .eq(KnowledgeDocProductEntity::getSpuId, spuId)
                        .eq(KnowledgeDocProductEntity::getRole, KnowledgeDocProductEntity.Role.SUBJECT.name()))
                .stream().map(KnowledgeDocProductEntity::getDocNo).distinct().sorted().toList();
    }

    /** 一篇文档归属的商品 id */
    public List<Long> subjectSpusOf(String docNo) {
        return mapper.selectList(Wrappers.<KnowledgeDocProductEntity>lambdaQuery()
                        .eq(KnowledgeDocProductEntity::getDocNo, docNo)
                        .eq(KnowledgeDocProductEntity::getRole, KnowledgeDocProductEntity.Role.SUBJECT.name()))
                .stream().map(KnowledgeDocProductEntity::getSpuId).distinct().sorted().toList();
    }

    /** 全部关联行。覆盖率检查与孤儿检查要拿它与商品目录、图谱三方比对 */
    public List<KnowledgeDocProductEntity> all() {
        return new ArrayList<>(mapper.selectList(null));
    }
}
