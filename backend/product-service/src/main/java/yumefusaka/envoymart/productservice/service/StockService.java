package yumefusaka.envoymart.productservice.service;

import yumefusaka.envoymart.contract.StockChangeRequest;

/**
 * 库存变动。
 * <p>
 * 每次变动都同时写一条流水 —— 这是本服务唯一有能力回答「库存为什么少了」的地方。
 */
public interface StockService {

    /** 扣减。库存不足时抛 {@code IllegalStateException}，SKU 不存在时抛 {@code IllegalArgumentException} */
    void deduct(StockChangeRequest request);

    /** 回补（取消订单、售后退货） */
    void restore(StockChangeRequest request);
}
