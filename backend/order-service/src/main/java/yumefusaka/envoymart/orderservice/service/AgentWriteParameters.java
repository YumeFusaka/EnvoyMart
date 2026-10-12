package yumefusaka.envoymart.orderservice.service;

import yumefusaka.envoymart.orderservice.model.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** 与 Agent 已公开的 DTO 规范一致；协议 requestId 不参与业务参数指纹。 */
public final class AgentWriteParameters {
    private AgentWriteParameters() { }
    public static Map<String,Object> checkout(CheckoutRequest r) {
        return fields("receiverName", text(r.getReceiverName()), "receiverPhone", text(r.getReceiverPhone()),
                "receiverProvince", text(r.getReceiverProvince()), "receiverCity", text(r.getReceiverCity()),
                "receiverDistrict", text(r.getReceiverDistrict()), "receiverDetail", text(r.getReceiverDetail()),
                "remark", text(r.getRemark()), "userCouponId", r.getUserCouponId());
    }
    public static Map<String,Object> afterSale(ApplyAfterSaleRequest r) {
        var map = fields("orderItemId", r.getOrderItemId(), "type", text(r.getType()).toUpperCase(Locale.ROOT),
                "reason", text(r.getReason()), "description", text(r.getDescription()), "qualityIssue", Boolean.TRUE.equals(r.getQualityIssue()));
        if (r.getImages() != null && !r.getImages().isEmpty()) map.put("images", r.getImages());
        return map;
    }
    public static Map<String,Object> ticket(CreateTicketRequest r) {
        return fields("category", text(r.getCategory()).toUpperCase(Locale.ROOT), "title", text(r.getTitle()),
                "content", text(r.getContent()), "orderId", r.getOrderId());
    }
    private static String text(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static Map<String,Object> fields(Object... fields) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0; i<fields.length; i+=2) result.put((String) fields[i], fields[i+1]);
        return result;
    }
}
