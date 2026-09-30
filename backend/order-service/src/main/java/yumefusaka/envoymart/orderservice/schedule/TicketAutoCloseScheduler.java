package yumefusaka.envoymart.orderservice.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.orderservice.service.TicketDomainService;

/**
 * 已解决工单的确认超时任务。
 * <p>
 * 没有这个任务的话，「已解决」会变成一个没有出口的状态：用户不确认、也不再回复，
 * 工单就永远挂在那里。客服的列表越翻越长，而其中一半是早已没人管的僵尸单 ——
 * 更糟的是分不清「还在等用户」和「被遗忘了」。
 * <p>
 * 单批上限 100，原因同 {@link OrderCloseScheduler}：一趟扫几万条会跑很久，
 * 而剩余的下次继续，堆积不会变成阻塞。
 */
@Slf4j
@Component
public class TicketAutoCloseScheduler {

    private static final int BATCH_SIZE = 100;

    private final TicketDomainService domainService;

    public TicketAutoCloseScheduler(TicketDomainService domainService) {
        this.domainService = domainService;
    }

    /**
     * 每 10 分钟扫一次。
     * <p>
     * 阈值是天级的（7 天），分钟级的调度精度绰绰有余；比关单任务的频率低，
     * 因为这条链路不占用任何稀缺资源（不像超时未支付订单占着库存）。
     * <p>
     * {@code fixedDelay} 而非 {@code fixedRate}：上一趟跑完再等，避免堆积。
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void autoCloseExpiredTickets() {
        try {
            domainService.autoCloseExpired(BATCH_SIZE);
        } catch (Exception e) {
            // 显式接住并留全栈：任务抛出去只会让调度线程记一笔，下一趟照跑，
            // 而「自动关闭一直失败」就散成了一行行没人看的调度异常
            log.error("[Ticket] 超时关闭已解决工单任务执行失败", e);
        }
    }
}
