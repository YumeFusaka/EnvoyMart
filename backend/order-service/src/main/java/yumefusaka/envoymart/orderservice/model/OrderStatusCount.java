package yumefusaka.envoymart.orderservice.model;

import lombok.Data;

/** 订单按状态分组的计数，只用于把「一条 SQL」折算成页签角标（见 {@link OrderTab#countIn}） */
@Data
public class OrderStatusCount {

    private String status;
    private long cnt;
}
