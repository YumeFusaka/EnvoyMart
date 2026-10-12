package yumefusaka.envoymart.productservice.projection;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.VersionType;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter;
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.document.Document;
import yumefusaka.envoymart.productservice.search.ProductIndex;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductProjectionElasticsearchContractTest {
    private MappingElasticsearchConverter converter() throws Exception {
        var mapping = new SimpleElasticsearchMappingContext();
        mapping.setInitialEntitySet(Set.of(ProductIndex.class)); mapping.afterPropertiesSet();
        var converter = new MappingElasticsearchConverter(mapping); converter.afterPropertiesSet();
        return converter;
    }

    @Test void 真实SpringData保存路径传递Long版本和externalGte() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var transport = mock(co.elastic.clients.transport.ElasticsearchTransport.class);
        when(client._transport()).thenReturn(transport);
        when(transport.jsonpMapper()).thenReturn(mock(co.elastic.clients.json.JsonpMapper.class));
        when(client.index(any(IndexRequest.class))).thenReturn(IndexResponse.of(response -> response.index("projection_contract")
                .id("20").version(103).result(Result.Updated).seqNo(1L).primaryTerm(1L)
                .shards(shards -> shards.total(1).successful(1).failed(0))));
        var template = new ElasticsearchTemplate(client, converter());
        template.save(ProductIndex.builder().id(20L).status(-1).totalStock(0).projectionVersion(103L).build(), IndexCoordinates.of("projection_contract"));
        var request = org.mockito.ArgumentCaptor.forClass(IndexRequest.class);
        verify(client).index(request.capture());
        assertThat(request.getValue().version()).isEqualTo(103L);
        assertThat(request.getValue().versionType()).isEqualTo(VersionType.ExternalGte);
    }

    @Test void 真实SpringData读取ES元版本而非依赖source冗余字段() throws Exception {
        var document = Document.parse("{\"id\":20,\"status\":1,\"totalStock\":495}");
        document.setVersion(91L);
        assertThat(converter().read(ProductIndex.class, document).getProjectionVersion()).isEqualTo(91L);
    }
}
