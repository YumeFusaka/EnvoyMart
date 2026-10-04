package yumefusaka.envoymart.orderservice.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import yumefusaka.envoymart.orderservice.model.TicketAwaitingPayload;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用户侧工单的 SSE 订阅表（进程内）。
 *
 * <p><b>为什么不是按用户只留一条连接</b>：同一个账号在浏览器里可能同时开着多个标签页，
 * 后开的把先开的挤掉，用户会看到「另一个标签页的角标不动了」—— 而这不是他做的任何动作
 * 导致的。所以每个用户持有一个连接列表，推送时逐个发。
 *
 * <p><b>为什么是内存表而不是 Redis 发布订阅</b>：这个进程就是 order-service 自己，
 * 产生推送的写路径（客服回复、用户发消息、关闭、重开）全部发生在本进程内，不存在
 * 「另一个实例改了数据、这个实例的连接收不到」的缺口。多实例部署时要换掉的是这张表
 * 本身（换成 Redis pub/sub），而不是改调用方 —— 因此写路径只依赖 {@link #push}。
 *
 * <p>连接的生命周期完全交给容器：{@code onCompletion / onTimeout / onError} 三个回调
 * 里都必须摘掉，否则一条已经断开的连接会永远留在表里被反复写。SSE 的断开极不容易在
 * 应用层观察到（用户直接关页面时没有挥手），回调是唯一可靠的时机。
 */
@Component
public class TicketStreamHub {

    /** 心跳间隔要小于网关与浏览器各自的空闲超时，否则「安静但活着」的连接会被判定为断开 */
    public static final long HEARTBEAT_MILLIS = 25_000L;

    /**
     * SSE 连接的总寿命。
     * <p>
     * 到点后客户端按浏览器的 EventSource 语义会自动重连 —— 这是可接受的：
     * 重连不是错误恢复，是这条长连接协议本身的一部分。若不设上限，一条被中间设备
     * 悄悄半关的连接会一直挂在表里，服务端以为它在、客户端以为它在，实际两边都收不到。
     */
    public static final long STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** 注册一条新连接并返回；调用方负责把它交给 Spring MVC 返回给客户端 */
    public SseEmitter register(String userId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        subscribers.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> remove(userId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /**
     * 把最新载荷推给某个用户的全部连接。
     *
     * <p><b>不抛异常</b>：推送发生在客服回复的事务提交之前/之后，任何一条浏览器连接
     * 断掉都不该让「客服的回复」失败 —— 通知是尽力而为的旁路，事实已经落库。
     * 写失败的连接就地摘掉，它下次也不会再被写。
     */
    public void push(String userId, TicketAwaitingPayload payload) {
        List<SseEmitter> emitters = subscribers.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("awaiting").data(payload));
            } catch (IOException | IllegalStateException e) {
                // 对端已离开：摘掉它。这里刻意不记 warn 堆栈 —— 用户关页面是正常动作
                remove(userId, emitter);
            }
        }
    }

    private void remove(String userId, SseEmitter emitter) {
        List<SseEmitter> emitters = subscribers.get(userId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            subscribers.remove(userId);
        }
    }
}
