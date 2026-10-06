package yumefusaka.envoymart.knowledgeservice.model;

import lombok.Data;

import java.util.List;

/**
 * 管理端提交的一篇知识文档。
 * <p>
 * <b>为什么正文是字符串而不是文件</b>：文件是装载的入口，不是事实源。
 * 引用要能指向「哪一版」，而版本、来源、范围这些属性属于文档记录本身
 * （见 {@code knowledge_document} 的建表说明）。上传一个 docx 再解析，
 * 解析结果才是要落库的东西——这一层收的就是那个结果。
 * <p>
 * 校验放在服务层而不是靠 Bean Validation 注解：取值清单（scope / source）
 * 是与检索侧、图谱侧共用的契约，写在这里能让「哪些值合法」只有一个出处。
 */
@Data
public class DocumentUpsertRequest {

    /** 文档编号，形如 KB-0022。留空时由服务端按当前最大编号顺延分配 */
    private String docNo;

    private String title;

    /** manual / policy / regulation / spec / guide */
    private String source;

    /** nutrition / after_sale / logistics / payment / promotion / food_safety */
    private String scope;

    /** 版本号。正文变了就该换一个——引用要能指向「哪一版」 */
    private String version;

    private List<String> tags;

    /** 全文。切分与图谱抽取都以它为准 */
    private String content;
    /**
     * 本文档主体对应的商品 id。**上传商品文档时由运营选择，领域文档留空。**
     * 支持多个：一篇「褪黑素与 GABA 类助眠产品说明书」可以同时是两个商品的说明书。
     * <p>
     * 它是「这篇文档讲的是哪个商品」的一次人工声明，不该让模型去猜——
     * 模型猜错的代价是把 A 商品的资料挂到 B 商品上，而系统看不出任何异常。
     */
    private java.util.List<Long> subjectSpuIds;

}
