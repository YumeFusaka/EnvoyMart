package yumefusaka.envoymart.aiservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import yumefusaka.envoymart.aiservice.client.OrderClient;
import yumefusaka.envoymart.aiservice.client.ProductClient;
import yumefusaka.envoymart.aiservice.memory.ChatHistoryStore;
import yumefusaka.envoymart.aiservice.tool.Downstream;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.contract.SkuSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class CommerceCardAssembler {
    private final ProductClient products;
    private final OrderClient orders;

    public CommerceCardAssembler(ProductClient products, OrderClient orders) {
        this.products = products;
        this.orders = orders;
    }

    public List<Map<String, Object>> approvalDetails(List<Map<String, Object>> details) {
        if (details == null || details.isEmpty()) {
            return details;
        }
        Map<Long, SkuSnapshot> snapshots = lookupSkus(details, false);
        return details.stream().map(detail -> enrichApproval(detail, snapshots, false)).toList();
    }

    public List<ChatHistoryStore.StoredMessage> history(String userId,
                                                        List<ChatHistoryStore.StoredMessage> messages) {
        List<Map<String, Object>> details = messages.stream()
                .filter(message -> message.response() != null)
                .flatMap(message -> maps(message.response().get("pendingActionDetails")).stream()).toList();
        Map<Long, SkuSnapshot> snapshots = lookupSkus(details, true);
        Map<Long, OrderResponse> orderSnapshots = new LinkedHashMap<>();
        AtomicBoolean ordersAvailable = new AtomicBoolean(true);
        List<ChatHistoryStore.StoredMessage> result = new ArrayList<>(messages.size());
        for (ChatHistoryStore.StoredMessage message : messages) {
            if (message.response() == null) {
                result.add(message);
                continue;
            }
            Map<String, Object> response = new LinkedHashMap<>(message.response());
            List<Map<String, Object>> approvals = maps(response.get("pendingActionDetails"));
            if (!approvals.isEmpty()) {
                response.put("pendingActionDetails", approvals.stream()
                        .map(detail -> enrichApproval(detail, snapshots, true)).toList());
            }
            List<Map<String, Object>> payments = maps(response.get("pendingPayments"));
            if (!payments.isEmpty()) {
                response.put("pendingPayments", payments.stream()
                        .map(payment -> enrichPayment(userId, payment, orderSnapshots, ordersAvailable)).toList());
            }
            result.add(new ChatHistoryStore.StoredMessage(message.id(), message.role(),
                    message.content(), message.at(), response));
        }
        return List.copyOf(result);
    }

    private Map<Long, SkuSnapshot> lookupSkus(List<Map<String, Object>> details, boolean historical) {
        List<Long> ids = details.stream().filter(detail -> "cart_add".equals(detail.get("tool")))
                .map(detail -> map(detail.get("arguments")))
                .filter(arguments -> !historical || !complete(arguments))
                .map(arguments -> positiveLong(arguments.get("skuId")))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, SkuSnapshot> snapshots = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return snapshots;
        }
        long started = System.currentTimeMillis();
        try {
            List<SkuSnapshot> found = Downstream.read("商品确认资料", () -> products.skus(ids));
            if (found != null) {
                found.stream().filter(Objects::nonNull).filter(sku -> ids.contains(sku.getId()))
                        .forEach(sku -> snapshots.put(sku.getId(), sku));
            }
            log.info("[交易卡片] 商品资料补全 请求={} 返回={} 历史={} 耗时={}ms",
                    ids.size(), snapshots.size(), historical, System.currentTimeMillis() - started);
        } catch (RuntimeException error) {
            log.warn("[交易卡片] 商品资料补全失败 请求={} 历史={} 异常={}",
                    ids.size(), historical, error.getClass().getSimpleName());
        }
        return snapshots;
    }

    private Map<String, Object> enrichApproval(Map<String, Object> detail,
                                               Map<Long, SkuSnapshot> snapshots, boolean historical) {
        if (!"cart_add".equals(detail.get("tool"))) {
            return detail;
        }
        Map<String, Object> arguments = map(detail.get("arguments"));
        if (historical && complete(arguments)) {
            return detail;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(detail);
        enriched.put("confirmable", false);
        arguments = new LinkedHashMap<>(arguments);
        arguments.remove("productName");
        arguments.remove("specification");
        arguments.remove("unitPrice");
        arguments.remove("subtotal");
        enriched.put("arguments", arguments);
        SkuSnapshot sku = snapshots.get(positiveLong(arguments.get("skuId")));
        Long quantity = arguments.get("quantity") == null ? 1L : positiveLong(arguments.get("quantity"));
        if (sku == null || sku.getSpuName() == null || sku.getPrice() == null || quantity == null) {
            enriched.put("displayNotice", "商品资料暂时无法完整获取，请重新查询商品后再确认。");
            return enriched;
        }
        try {
            long subtotal = Math.multiplyExact(sku.getPrice(), quantity);
            arguments.put("productName", sku.getSpuName());
            arguments.put("specification", sku.getSpecText() == null || sku.getSpecText().isBlank()
                    ? "单一规格" : sku.getSpecText());
            arguments.put("quantity", quantity);
            arguments.put("unitPrice", sku.getPrice());
            arguments.put("subtotal", subtotal);
            enriched.put("confirmable", quantity <= 99 && sku.purchasable()
                    && sku.getStock() != null && sku.getStock() >= quantity);
            if (historical) {
                enriched.put("displayNotice", "历史记录未保存商品资料，以下名称、规格与价格按当前目录补全。");
            } else if (quantity > 99) {
                enriched.put("displayNotice", "单次最多加购 99 件，请减少数量后重新确认。");
            } else if (!sku.purchasable() || sku.getStock() == null || sku.getStock() < quantity) {
                enriched.put("displayNotice", "该商品已下架或库存不足，请重新选择商品。");
            }
        } catch (ArithmeticException error) {
            enriched.put("displayNotice", "商品金额无法计算，请检查数量后重新选择。");
        }
        return enriched;
    }

    private Map<String, Object> enrichPayment(String userId, Map<String, Object> payment,
                                              Map<Long, OrderResponse> snapshots,
                                              AtomicBoolean ordersAvailable) {
        if (payment.get("items") instanceof List<?> items && !items.isEmpty()) {
            return payment;
        }
        Long orderId = positiveLong(payment.get("orderId"));
        if (orderId == null) {
            return payment;
        }
        if (!snapshots.containsKey(orderId)) {
            if (!ordersAvailable.get()) {
                return payment;
            }
            try {
                snapshots.put(orderId, Downstream.read("订单商品快照", () -> orders.getOrder(userId, orderId)));
            } catch (RuntimeException error) {
                ordersAvailable.set(false);
                snapshots.put(orderId, null);
                log.warn("[交易卡片] 历史订单明细补全失败，跳过本轮剩余补查 orderId={} 异常={}",
                        orderId, error.getClass().getSimpleName());
            }
        }
        OrderResponse order = snapshots.get(orderId);
        if (order == null || !Objects.equals(payment.get("orderNo"), order.getOrderNo())) {
            return payment;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(payment);
        enriched.put("items", order.getItems() == null ? List.of() : order.getItems());
        return enriched;
    }

    private static boolean complete(Map<String, Object> arguments) {
        return arguments.get("productName") instanceof String name && !name.isBlank()
                && arguments.get("specification") instanceof String
                && arguments.get("unitPrice") instanceof Number
                && arguments.get("subtotal") instanceof Number;
    }

    private static Long positiveLong(Object value) {
        try {
            long number = Long.parseLong(String.valueOf(value));
            return number > 0 ? number : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> values) {
            values.forEach((key, item) -> {
                if (key instanceof String name) result.put(name, item);
            });
        }
        return result;
    }

    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> values ? values.stream().filter(Map.class::isInstance)
                .map(CommerceCardAssembler::map).toList() : List.of();
    }
}
