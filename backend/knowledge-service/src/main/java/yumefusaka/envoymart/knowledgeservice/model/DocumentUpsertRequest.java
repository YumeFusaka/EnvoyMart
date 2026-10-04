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
}
