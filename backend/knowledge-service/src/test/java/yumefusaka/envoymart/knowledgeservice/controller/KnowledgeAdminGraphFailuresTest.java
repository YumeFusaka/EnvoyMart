package yumefusaka.envoymart.knowledgeservice.controller;

import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.knowledgeservice.graph.GraphService;

import static org.mockito.Mockito.*;

/** 管理接口的筛选契约不得在适配层丢失实体或关系约束。 */
class KnowledgeAdminGraphFailuresTest {
    @Test
    void 六项筛选完整交给统一查询() {
        GraphService service = mock(GraphService.class);
        KnowledgeAdminController controller = new KnowledgeAdminController(null, service, null, null);
        controller.graphFailures("batch-A", "KB-001", "VALIDATION", "UNANCHORED", "calcium", "supports", 5000);
        verify(service).failures("batch-A", "KB-001", "VALIDATION", "UNANCHORED", "calcium", "supports", 5000);
        verifyNoMoreInteractions(service);
    }
}
