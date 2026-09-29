package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物流轨迹。
 * <p>
 * 真实场景里它来自承运商推送 —— 本项目没有对接承运商，因此由发货动作写入首条，
 * 后续节点可以由内部接口补录。<b>它是真实落库的数据，不是按时间推算出来的假轨迹</b>。
 */
@Data
@TableName("order_delivery_trace")
public class OrderDeliveryTraceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long deliveryId;
    private LocalDateTime happenAt;
    private String status;
    private String description;
    private String location;
}
