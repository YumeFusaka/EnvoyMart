package yumefusaka.envoymart.productservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存流水。每一次变动都留痕：谁扣的、哪张单、变动前后各是多少。
 * <p>
 * 原实现的库存只是商品表上的一列，回补失败时只能打一行日志 —— 没有这张表，
 * 「库存为什么少了」永远查不清，回补失败也没有任何对账依据。
 */
@Data
@TableName("stock_log")
public class StockLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skuId;
    /** DEDUCT 扣减 / RESTORE 回补 / INBOUND 入库 */
    private String changeType;
    private Integer quantity;
    private Integer beforeStock;
    private Integer afterStock;
    /** ORDER / AFTER_SALE / MANUAL */
    private String bizType;
    private String bizId;
    private String remark;
    private LocalDateTime createdAt;
}
