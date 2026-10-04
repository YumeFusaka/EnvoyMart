package yumefusaka.envoymart.reviewservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.reviewservice.client.OrderClient;

import java.time.LocalDateTime;

/**
 * 新账号评价进待审 —— 刷评治理③。
 * <p>
 * <b>判据是「该用户最早一次收货，距这笔评价对应的订单收货时间不满 N 天」</b>，
 * 而不是「账号注册时间」。理由不是注册时间不好，是<b>它需要一个本项目里不存在的接口</b>：
 * 注册时间在 auth-service 的用户表里，评价侧要拿它就得新增一条跨服务调用与一套鉴权；
 * 而收货时间是订单数据的一部分，而我们<b>本来就拿着这笔订单</b>（requirePurchased 已经在查
 * 订单详情），再多问一句「这个人第一次收货是什么时候」不需要任何新凭证。
 * <p>
 * <b>判的是账号的年龄，不是这一单的新旧</b>：老客户今天下了一单、明天收货再评价，
 * 他与平台的关系已经持续很久，不该因为「这单是刚收的货」就进待审。
 * 而批量注册的小号，其人生第一单就是几天前 —— 无论他此刻评的是哪一单，
 * 账号年龄都在保护期内。
 * <p>
 * <b>它对好账号的表现是零打扰</b>：不进待审、不改流程，只是比对两个时间。
 * <b>对可疑账号也不拒绝</b>：评价照存，只是状态是 PENDING，由管理端复用已有的
 * 隐藏/审核能力处理 —— 直接拒掉会让误判者连评价都写不了，而待审是「先收下再判」。
 */
@Slf4j
@Component
public class ReviewNewAccountGuard {

    /**
     * 保护期。7 天是「刷单 + 评价」这个动作链路的典型时长下沿：
     * 真实买家从下单到收货再回来写评价，通常在一周内完成；
     * 而一个专门刷评的号如果必须等满 7 天才敢写，成本就已经比收益高了。
     */
    static final int PROTECTED_DAYS = 7;

    private final OrderClient orderClient;

    public ReviewNewAccountGuard(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    /**
     * 判定这条评价是否应进待审。
     * <p>
     * <b>判不了时返回 false（放行）</b>：订单服务抖动不该让评价变成待审 ——
     * 那会让一次网络故障看起来像一次风控命中，而且待审是<b>面向人</b>的队列，
     * 不该被机器故障灌满。补一句 WARN 留下痕迹，比假装判定成功要好。
     *
     * @param userId          评价人
     * @param orderReceivedAt 当前这笔订单的收货时间（已在 requirePurchased 里校验过状态）
     * @return true = 应进待审
     */
    public boolean shouldHoldForReview(String userId, LocalDateTime orderReceivedAt) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        LocalDateTime first;
        try {
            Result<LocalDateTime> result = orderClient.firstReceivedAt(userId);
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                log.warn("[Review] 取首次收货时间未成功，本条评价按老账号处理: userId={} code={}",
                        userId, result == null ? null : result.getCode());
                return false;
            }
            first = result.getData();
        } catch (Exception e) {
            log.warn("[Review] 取首次收货时间异常，本条评价按老账号处理: userId={} reason={}",
                    userId, e.getMessage());
            return false;
        }

        if (first == null) {
            // 从未有过收货：这是他的第一单。第一单的评价天然在保护期内
            log.info("[Review] 该用户没有任何已收货订单，本条评价进待审: userId={}", userId);
            return true;
        }
        // 对照基准用「本次评价对应订单的收货时间」而不是 now()：管理端补录、消息补发
        // 的历史评价不该因为「今天是 2026 年」就被判成账号很老。判的是
        // 「这次收货发生时，这个账号的年龄有多大」——账号年龄 = 本次收货 − 最早收货。
        LocalDateTime reference = orderReceivedAt == null ? LocalDateTime.now() : orderReceivedAt;
        boolean withinProtection = first.isAfter(reference.minusDays(PROTECTED_DAYS));
        if (withinProtection) {
            log.info("[Review] 新账号保护期内评价进待审: userId={} 首次收货={} 本次收货={} 账号年龄(天)={}",
                    userId, first, reference, java.time.Duration.between(first, reference).toDays());
        }
        return withinProtection;
    }
}
