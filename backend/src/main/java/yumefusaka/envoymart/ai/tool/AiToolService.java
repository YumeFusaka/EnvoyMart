package yumefusaka.envoymart.ai.tool;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import yumefusaka.envoymart.ai.model.response.ToolCallResponse;
import yumefusaka.envoymart.order.model.response.LogisticsResponse;
import yumefusaka.envoymart.order.model.response.OrderResponse;
import yumefusaka.envoymart.order.service.OrderService;
import yumefusaka.envoymart.product.model.response.ProductResponse;
import yumefusaka.envoymart.product.service.ProductService;

import java.util.ArrayList;
import java.util.List;

@Service
public class AiToolService {

    private final OrderService orderService;
    private final ProductService productService;

    public AiToolService(OrderService orderService, ProductService productService) {
        this.orderService = orderService;
        this.productService = productService;
    }

    public List<ToolCallResponse> collectToolCalls(String userId, String message, Long contextOrderId) {
        List<ToolCallResponse> toolCalls = new ArrayList<>();
        if (mentionsLogistics(message)) {
            LogisticsResponse logistics = orderService.getLogistics(userId, resolveOrderId(userId, contextOrderId));
            String latestStep = logistics.getSteps().get(logistics.getSteps().size() - 1).getDetail();
            toolCalls.add(ToolCallResponse.builder()
                    .tool("logistics_lookup")
                    .input("orderId=" + logistics.getOrderId())
                    .output(latestStep)
                    .build());
        } else if (mentionsOrder(message)) {
            OrderResponse order = orderService.getOrder(userId, resolveOrderId(userId, contextOrderId));
            toolCalls.add(ToolCallResponse.builder()
                    .tool("order_lookup")
                    .input("orderId=" + order.getId())
                    .output("订单状态为 " + order.getStatus() + "，下单时间 " + order.getCreatedAt())
                    .build());
        }

        if (mentionsRecommendation(message)) {
            List<ProductResponse> products = productService.recommendProducts(message, 2);
            if (!products.isEmpty()) {
                toolCalls.add(ToolCallResponse.builder()
                        .tool("product_search")
                        .input(message)
                        .output("已检索到 " + products.size() + " 个相关商品")
                        .build());
            }
        }
        return toolCalls;
    }

    private Long resolveOrderId(String userId, Long contextOrderId) {
        if (contextOrderId != null) {
            return contextOrderId;
        }
        return orderService.getLatestOrder(userId).getId();
    }

    private boolean mentionsLogistics(String message) {
        return containsAny(message, "物流", "快递", "到哪", "派送");
    }

    private boolean mentionsOrder(String message) {
        return containsAny(message, "订单", "下单", "状态");
    }

    private boolean mentionsRecommendation(String message) {
        return containsAny(message, "推荐", "适合", "学生党", "耳机", "买什么");
    }

    private boolean containsAny(String message, String... keywords) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
