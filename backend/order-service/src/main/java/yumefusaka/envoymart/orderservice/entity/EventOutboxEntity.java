package yumefusaka.envoymart.orderservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事务性发件箱的一行 —— <b>「要发什么」先落库，「真的发出去」是后续的幂等动作</b>。
 * <p>
 * 它存在的理由是「状态变更」与「事件发布」必须原子。两者分处数据库与 broker，
 * 没有分布式事务可用，于是把「发布」拆成两段：事务内只写这张表（与业务行同生共死），
 * 提交后由 {@code OutboxRelay} 投递并标记。任何一段崩溃，重试都能把剩下的补上。
 * <p>
 * {@code payload} 存已经序列化好的 JSON 字符串，而不是一个待转换的对象：
 * 投递器不该依赖「实体类在各服务里的字段是否一致」——跨服务复制的消息契约
 * 会静默丢字段，这一点本项目已经吃过一次亏（U55）。
 */
@Data
@TableName("event_outbox")
public class EventOutboxEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件类型，取路由键：order.paid / order.created … */
    private String eventType;

    /** 分区键，通常是订单号。同一业务记录的事件据此保序 */
    private String aggregateId;

    private String exchangeName;

    private String routingKey;

    /** 已序列化的 JSON 载荷 */
    private String payload;

    private LocalDateTime createdAt;

    /** 为空表示尚未投递 —— 投递器唯一的筛选条件 */
    private LocalDateTime sentAt;

    private Integer attempts;

    /** 最近一次失败原因。留痕是为了「发不出去」这件事可被诊断，而不是只看到重试计数 */
    private String lastError;
}