package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长文档语料 —— 把 {@link RetrievalFixtures} 的 90 篇短文按主题聚合成 9 份长文档。
 * <p>
 * <b>为什么要做这个聚合</b>：原夹具每篇 40~60 字，短于切分窗口，<b>每篇恰好一片</b>，
 * 于是"切分策略"这个变量在它上面完全失效——换任何策略，检索指标都一模一样。
 * 聚合成长文档后，一份文档内会有十几条规则，切分器必须真的做出取舍，
 * 而查询仍然指向"具体哪一条"，<b>"切分有没有把这一条切断"就直接体现在检索结果上</b>。
 * <p>
 * <b>条款 id 保持不变</b>：聚合后的每一条仍用原文档的 id 作为"节 id"，
 * 因此现有的 120 条标注查询与三档分层可以原样复用，不需要重新标注。
 * <p>
 * 分组按 {@code DOCS} 的原始排列顺序切分（夹具本身就是按主题成簇排列的），
 * 不依赖 tags——tags 是给人看的检索线索，不是可靠的分组依据。
 */
final class LongDocFixtures {

    private LongDocFixtures() {
    }

    /** 一份聚合后的长文档，以及它包含的节（原文档 id）。 */
    record Topic(String docId, String title, List<String> sectionIds) {
    }

    /**
     * 主题分组 —— 按 {@code RetrievalFixtures.DOCS} 的排列顺序划分。
     * 数值必须与夹具里的实际排列一致，加起来等于语料总数。
     */
    private static final List<Topic> TOPICS = List.of(
            new Topic("topic_after_sale", "售后服务与退换规则", sectionIds(0, 14)),
            new Topic("topic_logistics", "物流配送与签收规则", sectionIds(14, 12)),
            new Topic("topic_promotion", "营销活动与优惠规则", sectionIds(26, 14)),
            new Topic("topic_payment", "支付、发票与退款规则", sectionIds(40, 10)),
            new Topic("topic_member", "会员等级与积分规则", sectionIds(50, 8)),
            new Topic("topic_account", "账号与信息安全规则", sectionIds(58, 8)),
            new Topic("topic_product", "商品信息与选购指南", sectionIds(66, 10)),
            new Topic("topic_order", "订单与评价规则", sectionIds(76, 8)),
            new Topic("topic_service", "客服与投诉渠道", sectionIds(84, 6))
    );

    /**
     * 聚合后的长文档。每份的结构是 {@code 第 N 条 <原标题>} + 原文，
     * 这正是真实平台规则的写法，也是 {@link StructuralSplitter} 要识别的结构。
     */
    static List<Document> assemble() {
        List<Document> result = new ArrayList<>(TOPICS.size());

        for (Topic topic : TOPICS) {
            StringBuilder body = new StringBuilder();
            body.append("第一章 ").append(topic.title()).append("\n\n");

            int index = 1;
            for (String sectionId : topic.sectionIds()) {
                Document source = findSource(sectionId);
                body.append("第").append(index++).append("条 ").append(source.getTitle()).append("\n")
                        .append(source.getContent()).append("\n\n");
            }

            result.add(Document.builder()
                    .id(topic.docId())
                    .title(topic.title())
                    .content(body.toString().strip())
                    .build());
        }
        return result;
    }

    /** 节 id → 它属于哪份长文档。检索结果按它换算命中。 */
    static Map<String, String> sectionToTopic() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Topic topic : TOPICS) {
            for (String sectionId : topic.sectionIds()) {
                map.put(sectionId, topic.docId());
            }
        }
        return map;
    }

    /**
     * 节 id → 该节的正文（去空白）。
     * <p>
     * 判定"命中"用的是<b>这一段的文本是否完整出现在某个召回的切片里</b>，
     * 而不是切片归属哪篇文档——聚合之后所有节共用同一个 docId，
     * 按文档判断就没有区分度了。
     */
    static Map<String, String> sectionTexts() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Topic topic : TOPICS) {
            for (String sectionId : topic.sectionIds()) {
                Document source = findSource(sectionId);
                map.put(sectionId, normalize(source.getTitle() + source.getContent()));
            }
        }
        return map;
    }

    /** 与 {@code ChunkingQualityTest} 同一口径：换行只是排版，比较时忽略。 */
    static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    // ==================== 辅助 ====================

    /** 取 {@code DOCS} 里从 from 开始、count 个元素的 id。 */
    private static List<String> sectionIds(int from, int count) {
        List<Document> docs = RetrievalFixtures.DOCS;
        List<String> ids = new ArrayList<>(count);
        for (int i = from; i < from + count && i < docs.size(); i++) {
            ids.add(docs.get(i).getId());
        }
        return ids;
    }

    private static Document findSource(String id) {
        return RetrievalFixtures.DOCS.stream()
                .filter(d -> d.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("RetrievalFixtures 里没有这篇文档：" + id));
    }
}
