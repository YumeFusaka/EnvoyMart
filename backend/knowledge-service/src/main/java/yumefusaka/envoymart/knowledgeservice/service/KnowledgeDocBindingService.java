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
