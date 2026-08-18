package yumefusaka.envoymart.orderservice.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 库存扣减事件（当库存变化时发布）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockUpdatedEvent {
    private Long productId;
    private Integer deductedQuantity;
    private Integer remainingStock;
}
