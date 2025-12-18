package yumefusaka.envoymart.knowledge.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.knowledge.model.KnowledgeDocument;
import yumefusaka.envoymart.knowledge.model.KnowledgeSnippet;
import yumefusaka.envoymart.knowledge.service.KnowledgeService;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private final List<KnowledgeDocument> documents = List.of(
            KnowledgeDocument.builder()
                    .id("promo-1")
                    .title("平台满减规则")
                    .scope("promotion")
                    .content("本周数码会场满 199 减 20，满 299 减 40；学生认证用户可叠加 95 折校园券。")
                    .tags(List.of("活动", "满减", "优惠", "学生"))
                    .build(),
            KnowledgeDocument.builder()
                    .id("after-sale-1")
                    .title("七天无理由与售后规则")
                    .scope("after_sale")
                    .content("除定制类和贴身个护商品外，大部分商品支持七天无理由退货；质量问题支持换新与运费补贴。")
                    .tags(List.of("售后", "退货", "七天无理由", "换新"))
                    .build(),
            KnowledgeDocument.builder()
                    .id("logistics-1")
                    .title("物流说明")
                    .scope("logistics")
                    .content("现货订单通常在 24 小时内出库，华东地区预计 1 到 2 天送达，偏远地区时效以物流更新为准。")
                    .tags(List.of("物流", "时效", "出库", "配送"))
                    .build(),
            KnowledgeDocument.builder()
                    .id("guide-1")
                    .title("百元耳机选购建议")
                    .scope("product_guide")
                    .content("学生党选择百元耳机时，优先看佩戴舒适度、麦克风通话清晰度和续航，通勤场景重视低延迟和抗风噪。")
                    .tags(List.of("耳机", "学生党", "百元", "推荐"))
                    .build()
    );

    @Override
    public List<KnowledgeSnippet> retrieve(String query, int limit) {
        String normalized = StringUtils.hasText(query) ? query.toLowerCase(Locale.ROOT) : "";
        return documents.stream()
                .sorted(Comparator.comparingInt((KnowledgeDocument doc) -> score(doc, normalized)).reversed())
                .limit(limit)
                .map(doc -> KnowledgeSnippet.builder()
                        .title(doc.getTitle())
                        .content(doc.getContent())
                        .scope(doc.getScope())
                        .build())
                .toList();
    }

    private int score(KnowledgeDocument document, String query) {
        if (!StringUtils.hasText(query)) {
            return 0;
        }
        int score = 0;
        if (document.getTitle().toLowerCase(Locale.ROOT).contains(query)) {
            score += 5;
        }
        if (document.getContent().toLowerCase(Locale.ROOT).contains(query)) {
            score += 4;
        }
        for (String tag : document.getTags()) {
            if (query.contains(tag.toLowerCase(Locale.ROOT)) || tag.toLowerCase(Locale.ROOT).contains(query)) {
                score += 3;
            }
        }
        return score;
    }
}
