package yumefusaka.envoymart.orderservice.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.orderservice.service.impl.OrderDomainServiceImpl;

/**
 * 订单超时任务：未支付关单 + 已收货自动完成。
 * <p>
 * 没有关单任务的话，未支付订单会**永久占用库存**：库存在下单时就已经扣掉了，
 * 而订单停在待支付不动 —— 既不会关单，也不会有任何地方报错，只是可卖的货
 * 一天天变少。
 * <p>
 * 没有自动完成任务的话，「已完成」永远没有写入方 —— 订单走到已收货就停住了。
 * 用户不会专门回来点一个「完成」，而交易需要一个终点（售后期满、账目结清）。
 * <p>
 * 单批上限 100：一次扫出几万条会让这一趟跑很久，而下一分钟还要再来一趟。
 * 剩余的下次继续处理，堆积不会变成阻塞。
 */
@Slf4j
@Component
public class OrderCloseScheduler {

    private static final int BATCH_SIZE = 100;

    private final OrderDomainServiceImpl orderDomainService;

    public OrderCloseScheduler(OrderDomainServiceImpl orderDomainService) {
        this.orderDomainService = orderDomainService;
    }

    /**
     * 每分钟扫一次。
     * <p>
     * 用 {@code fixedDelay} 而不是 {@code fixedRate}：前者是"上一趟跑完再等一分钟"，
     * 后者是"每分钟起一趟"—— 后者在任务耗时超过一分钟时会不断堆积，
     * 而关单要回补库存（跨服务调用），慢是可能的。
     * <p>
     * 关单本身是幂等的：状态条件更新让同一条订单被扫到两次时第二次返回 0。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void closeExpiredOrders() {
        try {
            orderDomainService.closeExpiredOrders(BATCH_SIZE);
        } catch (Exception e) {
            // 任务抛出去只会让调度线程记一笔，下一趟照跑；这里显式接住并留全栈，
            // 是为了让「关单一直失败」在日志里看得见，而不是散成一条条调度异常
            log.error("[Order] 超时关单任务执行失败", e);
        }
    }

    /**
     * 收货期满自动完成。与关单同一分钟节拍上跑，但门槛是「天」级的，
     * 跑多勤都只是扫一遍索引，开销可以忽略。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void completeExpiredReceipts() {
        try {
            orderDomainService.completeExpiredReceipts(BATCH_SIZE);
        } catch (Exception e) {
            log.error("[Order] 自动完成任务执行失败", e);
        }
    }
}
