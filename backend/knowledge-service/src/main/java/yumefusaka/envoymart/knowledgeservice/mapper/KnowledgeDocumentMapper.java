package yumefusaka.envoymart.knowledgeservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeDocumentEntity;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {

    /**
     * 任一字段有变才写回，返回值告诉调用方「这次到底要不要重建切片与索引」。
     * <p>
     * 用一个方法同时完成「更新」与「判断是否需要重切」，而不是先查出来比对再写回——
     * 后者是读-改-写，两个实例同时启动会因为都读到旧值而各写一次。
     * 这里把判断交给数据库：条件不成立就没有行被影响。
     * <p>
     * where 里逐一列出 set 的全部列，而不是只挑 {@code content}：
     * <ul>
     *   <li>{@code title} 会进切片的位置前缀（{@code 《标题》 > 第三章}）——改标题不改正文，
     *       切片正文其实是变的，只比 content 会漏掉这次重切；</li>
     *   <li>{@code version}、{@code source}、{@code tags} 是引用的元信息，ai-service 建索引时
     *       一并带进 Milvus。只比 content 的话，改了版本号而正文没动就不会落库，
     *       引用上写的还是旧版本——而「引用要能指明是哪一版」正是这张表存在的理由；</li>
     *   <li>{@code COALESCE(...,'')} 是必须的：SQL 里 {@code NULL <> NULL} 求值为 NULL
     *       而不是 TRUE，{@code tags} 从空变成有值时这一条会被静默跳过，行看着没动、值也没写进去。</li>
     * </ul>
     *
     * @return 受影响行数，0 表示各字段与库中完全一致
     */
    @Update("update knowledge_document set title = #{doc.title}, source = #{doc.source}, "
            + "scope = #{doc.scope}, version = #{doc.version}, tags = #{doc.tags}, "
            + "content = #{doc.content}, updated_at = #{doc.updatedAt} "
            + "where doc_no = #{doc.docNo} and ("
            + "COALESCE(title,'') <> COALESCE(#{doc.title},'') "
            + "or COALESCE(source,'') <> COALESCE(#{doc.source},'') "
            + "or COALESCE(scope,'') <> COALESCE(#{doc.scope},'') "
            + "or COALESCE(version,'') <> COALESCE(#{doc.version},'') "
            + "or COALESCE(tags,'') <> COALESCE(#{doc.tags},'') "
            + "or COALESCE(content,'') <> COALESCE(#{doc.content},''))")
    int updateIfChanged(@Param("doc") KnowledgeDocumentEntity doc);
}
