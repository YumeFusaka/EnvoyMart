package yumefusaka.envoymart.knowledgeservice.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import yumefusaka.envoymart.contract.GraphBuildFailurePayload;
import yumefusaka.envoymart.knowledgeservice.entity.GraphBuildFailureEntity;
import yumefusaka.envoymart.knowledgeservice.mapper.GraphBuildFailureMapper;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeChunkMapper;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeDocumentMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** T1 图谱拒绝记录必须保留候选、原文定位与批次边界。 */
class GraphServiceFailureRecordTest {

    @BeforeEach
    void 初始化查询元数据() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""),
                GraphBuildFailureEntity.class);
    }

    @Test
    void 筛选精确匹配任一端点并在数据库截断前执行() {
        GraphBuildFailureMapper mapper = mock(GraphBuildFailureMapper.class);
        when(mapper.selectList(any())).thenReturn(java.util.List.of());
        GraphService service = new GraphService(mock(KnowledgeDocumentMapper.class),
                mock(KnowledgeChunkMapper.class), mock(KnowledgeGraphStore.class), mapper);

        service.failures("batch-A", "KB-001", "VALIDATION", "UNANCHORED", "calcium", "supports", 9000);

        var captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).selectList(captor.capture());
        var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GraphBuildFailureEntity>) captor.getValue();
        assertThat(query.getSqlSegment()).contains("batch_id =", "doc_no =", "stage =", "reason_code =",
                "(entity_key =", "OR normalized_head =", "OR normalized_tail =", "relation =", "limit 5000")
                .doesNotContain("LIKE");
        assertThat(query.getParamNameValuePairs().values()).contains("batch-A", "KB-001", "VALIDATION", "UNANCHORED", "calcium", "supports");
    }

    @Test
    void 空白筛选不生成条件且限制最小读取量() {
        GraphBuildFailureMapper mapper = mock(GraphBuildFailureMapper.class);
        when(mapper.selectList(any())).thenReturn(java.util.List.of());
        GraphService service = new GraphService(mock(KnowledgeDocumentMapper.class),
                mock(KnowledgeChunkMapper.class), mock(KnowledgeGraphStore.class), mapper);

        service.failures(" ", null, "", null, " ", " ", 0);

        var captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).doesNotContain(" = ", "LIKE").contains("limit 1");
    }

    @Test
    void 熔断跳过保留批次阶段而不是降级为未知() {
        GraphBuildFailureMapper mapper = mock(GraphBuildFailureMapper.class);
        GraphService service = new GraphService(mock(KnowledgeDocumentMapper.class),
                mock(KnowledgeChunkMapper.class), mock(KnowledgeGraphStore.class), mapper);

        service.recordFailure(new GraphBuildFailurePayload("batch-A", "KB-001", null,
                "batch", "BATCH_SKIPPED", "连续失败达到熔断阈值", true));

        var captor = org.mockito.ArgumentCaptor.forClass(GraphBuildFailureEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getStage()).isEqualTo("BATCH");
        assertThat(captor.getValue().getReasonCode()).isEqualTo("BATCH_SKIPPED");
    }

    @Test
    void 拒绝记录保留可追溯字段并规范化阶段() {
        GraphBuildFailureMapper mapper = mock(GraphBuildFailureMapper.class);
        GraphService service = new GraphService(mock(KnowledgeDocumentMapper.class),
                mock(KnowledgeChunkMapper.class), mock(KnowledgeGraphStore.class), mapper);
        GraphBuildFailurePayload payload = new GraphBuildFailurePayload(
                "batch-7", "KB-001", "SPU7", "write", "quote-missing", "原文未命中", true);
        payload.setRawCandidate("PRODUCT SPU7 - contains -> INGREDIENT calcium");
        payload.setNormalizedHeadKind("PRODUCT");
        payload.setNormalizedHead("SPU7");
        payload.setNormalizedTailKind("INGREDIENT");
        payload.setNormalizedTail("calcium");
        payload.setRelation("contains");
        payload.setQuote("SPU7 含钙");
        payload.setQuoteOffsetStart(12);
        payload.setQuoteOffsetEnd(19);
        payload.setAliasHit(true);
        payload.setChunkId("KB-001_2");

        service.recordFailure(payload);

        var captor = org.mockito.ArgumentCaptor.forClass(GraphBuildFailureEntity.class);
        verify(mapper).insert(captor.capture());
        GraphBuildFailureEntity saved = captor.getValue();
        assertThat(saved.getBatchId()).isEqualTo("batch-7");
        assertThat(saved.getStage()).isEqualTo("WRITE");
        assertThat(saved.getReasonCode()).isEqualTo("QUOTE_MISSING");
        assertThat(saved.getRawCandidate()).contains("SPU7");
        assertThat(saved.getQuote()).isEqualTo("SPU7 含钙");
        assertThat(saved.getQuoteOffsetStart()).isEqualTo(12);
        assertThat(saved.getQuoteOffsetEnd()).isEqualTo(19);
        assertThat(saved.getAliasHit()).isTrue();
        assertThat(saved.getChunkId()).isEqualTo("KB-001_2");
    }
}
