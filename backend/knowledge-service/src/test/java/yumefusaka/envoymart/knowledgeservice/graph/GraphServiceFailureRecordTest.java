package yumefusaka.envoymart.knowledgeservice.graph;

import org.junit.jupiter.api.Test;
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
