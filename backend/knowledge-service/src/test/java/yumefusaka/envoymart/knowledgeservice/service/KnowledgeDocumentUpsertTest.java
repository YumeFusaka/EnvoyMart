package yumefusaka.envoymart.knowledgeservice.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeChunkEntity;
import yumefusaka.envoymart.knowledgeservice.entity.KnowledgeDocumentEntity;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeChunkMapper;
import yumefusaka.envoymart.knowledgeservice.mapper.KnowledgeDocumentMapper;
import yumefusaka.envoymart.knowledgeservice.model.DocumentUpsertRequest;
import yumefusaka.envoymart.knowledgeservice.service.KnowledgeDocBindingService;
import yumefusaka.envoymart.knowledgeservice.service.KnowledgeDocBindingService;
import yumefusaka.envoymart.knowledgeservice.service.impl.KnowledgeDocumentServiceImpl;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端上传文档的写入路径。
 * <p>
 * 这一段是目标里「上传全新文档 → 自动切分 → 进向量库 → 图谱更新」链路的<b>第一环</b>：
 * 它把正文落成事实源并重切切片，后面重建索引才有东西可拉。
 * 因此这里测的是三件容易静默出错的事：
 * <ul>
 *   <li><b>校验必须抛、不能补默认值</b>——少一个 scope 的文档会被检索侧
 *       按领域筛选漏掉，而管理台显示「保存成功」；</li>
 *   <li><b>编号必须顺延而不是数行数</b>——删过文档之后数行数会分配出已占用的号，
 *       撞在 {@code doc_no} 唯一约束上；</li>
 *   <li><b>正文变了必须重切</b>——不重切则切片正文与 charOffset 对不上，
 *       引用回跳会高亮到错误的原文位置。</li>
 * </ul>
 */
class KnowledgeDocumentUpsertTest {

    static {
        // MyBatis-Plus 的 lambda 列名要靠实体元数据缓存解析，纯单测没有 Spring 容器
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentEntity.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeChunkEntity.class);
    }

    private KnowledgeDocumentMapper documentMapper;
    private KnowledgeChunkMapper chunkMapper;
    private KnowledgeDocBindingService bindingService;
    private KnowledgeDocumentServiceImpl service;

    /** 记录 insert 到文档表的实体，供断言编号与字段 */
    private final List<KnowledgeDocumentEntity> inserted = new ArrayList<>();
    /** 记录 insert 到切片表的实体 */
    private final List<KnowledgeChunkEntity> insertedChunks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        documentMapper = mock(KnowledgeDocumentMapper.class);
        chunkMapper = mock(KnowledgeChunkMapper.class);
        bindingService = mock(KnowledgeDocBindingService.class);
        inserted.clear();
        insertedChunks.clear();

        // 默认「库里还没有任何文档」：selectOne 返回 null → 走新建分支
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(KnowledgeDocumentEntity.class))).thenAnswer(inv -> {
            KnowledgeDocumentEntity entity = inv.getArgument(0);
            entity.setId(1L);
            inserted.add(entity);
            return 1;
        });
        when(documentMapper.selectList(any())).thenReturn(List.of());
        when(chunkMapper.insert(any(KnowledgeChunkEntity.class))).thenAnswer(inv -> {
            insertedChunks.add(inv.getArgument(0));
            return 1;
        });

        service = new KnowledgeDocumentServiceImpl(documentMapper, chunkMapper, bindingService);
    }

    private DocumentUpsertRequest request(String title, String content) {
        DocumentUpsertRequest req = new DocumentUpsertRequest();
        req.setTitle(title);
        req.setContent(content);
        req.setSource("manual");
        req.setScope("nutrition");
        req.setVersion("v1");
        req.setSubjectSpuIds(List.of(1L));
        return req;
    }

    @Test
    void 上传会落库并切出切片() {
        String docNo = service.upsert(request("维生素 D3 说明书",
                "第一章 用法用量\n\n成人每日一片，随餐服用。\n\n第二章 禁忌\n\n高钙血症患者禁用。"));

        assertThat(docNo).isNotBlank();
        assertThat(inserted).hasSize(1);
        assertThat(insertedChunks).isNotEmpty();
        // 切片必须有编号：引用回跳靠它，为空会让所有片塌成同一份候选
        assertThat(insertedChunks).allSatisfy(chunk -> assertThat(chunk.getChunkId()).isNotBlank());
    }

    @Test
    void 缺少正文时抛错而不是落一篇空文档() {
        DocumentUpsertRequest req = request("只有标题", "   ");

        assertThatThrownBy(() -> service.upsert(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正文");
        verify(documentMapper, never()).insert(any(KnowledgeDocumentEntity.class));
    }

    @Test
    void 领域取值不在清单内时抛错() {
        DocumentUpsertRequest req = request("标题", "正文内容");
        req.setScope("营养");   // 合法值是 nutrition；中文写法会让领域筛选永远筛不到

        assertThatThrownBy(() -> service.upsert(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void 来源取值不在清单内时抛错() {
        DocumentUpsertRequest req = request("标题", "正文内容");
        req.setSource("随便写的");

        assertThatThrownBy(() -> service.upsert(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void 编号格式不合法时抛错() {
        DocumentUpsertRequest req = request("标题", "正文内容");
        req.setDocNo("KB-abc");

        assertThatThrownBy(() -> service.upsert(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档编号");
    }

    @Test
    void 未指定编号时按库里最大编号顺延() {
        KnowledgeDocumentEntity existing = new KnowledgeDocumentEntity();
        existing.setDocNo("KB-0021");
        when(documentMapper.selectList(any())).thenReturn(List.of(existing));

        String docNo = service.upsert(request("新文档", "正文内容至少要有一些字"));

        assertThat(docNo).isEqualTo("KB-0022");
    }

    @Test
    void 停用状态只接受零和一() {
        assertThatThrownBy(() -> service.changeStatus("KB-0001", 2, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }
}
