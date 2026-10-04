package yumefusaka.envoymart.reviewservice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.OrderItemResponse;
import yumefusaka.envoymart.contract.OrderResponse;
import yumefusaka.envoymart.reviewservice.client.OrderClient;
import yumefusaka.envoymart.reviewservice.client.ProductClient;
import yumefusaka.envoymart.reviewservice.entity.ReviewEntity;
import yumefusaka.envoymart.reviewservice.mapper.ReviewImageMapper;
import yumefusaka.envoymart.reviewservice.mapper.ReviewMapper;
import yumefusaka.envoymart.reviewservice.mapper.ReviewUsefulMapper;
import yumefusaka.envoymart.reviewservice.model.CreateReviewRequest;
import yumefusaka.envoymart.reviewservice.mq.ReviewAggregatePublisher;
import yumefusaka.envoymart.reviewservice.service.ReviewAggregateReader;
import yumefusaka.envoymart.reviewservice.service.ReviewFloodGuard;
import yumefusaka.envoymart.reviewservice.service.ReviewNewAccountGuard;

import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评价创建时「初始状态由谁决定」这一条链路。
 * <p>
 * 三条容易写错、又都不报错的：
 * <ul>
 *   <li><b>老账号必须仍是 PUBLISHED</b> —— 保护期判据一旦写反（或条件取反），
 *       全站评价都会进待审，而功能「还能用」，只是没人再看见新评价；</li>
 *   <li><b>新账号写 PENDING</b> —— 写反了刷评防线形同不存在，且没有任何信号；</li>
 *   <li><b>同商品 IP 超限要拒绝</b> —— 判不了（Redis 挂）与没超限必须都放行，
 *       否则一次 Redis 抖动会让所有人写不了评价。</li>
 * </ul>
 */
class ReviewServiceImplCreateTest {

    private ReviewMapper reviewMapper;
    private OrderClient orderClient;
    private ReviewFloodGuard floodGuard;
    private ReviewNewAccountGuard newAccountGuard;
    private ReviewServiceImpl service;

    @BeforeAll
    static void initMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ReviewEntity.class);
    }

    @BeforeEach
    void setUp() {
        reviewMapper = mock(ReviewMapper.class);
        orderClient = mock(OrderClient.class);
        floodGuard = mock(ReviewFloodGuard.class);
        newAccountGuard = mock(ReviewNewAccountGuard.class);

        // 默认：没超限、判不了（放行）
        when(floodGuard.recordAndCheck(anyLong(), anyString())).thenReturn(OptionalInt.of(1));
        when(reviewMapper.selectCount(any())).thenReturn(0L);
        when(reviewMapper.insert(any(ReviewEntity.class))).thenAnswer(invocation -> {
            ReviewEntity entity = invocation.getArgument(0);
            entity.setId(999L);
            return 1;
        });

        service = new ReviewServiceImpl(
                reviewMapper,
                mock(ReviewImageMapper.class),
                mock(ReviewUsefulMapper.class),
                orderClient,
                mock(ProductClient.class),
                mock(ReviewAggregateReader.class),
                mock(ReviewAggregatePublisher.class),
                floodGuard,
                newAccountGuard);
    }

    @Test
    void 老账号评价直接发布() {
        stubPurchasedOrder(LocalDateTime.of(2026, 8, 1, 10, 0));
        when(newAccountGuard.shouldHoldForReview(anyString(), any())).thenReturn(false);

        service.create("u1", request(), "10.0.0.1");

        assertThat(capturedStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void 新账号评价进待审() {
        stubPurchasedOrder(LocalDateTime.of(2026, 10, 3, 10, 0));
        when(newAccountGuard.shouldHoldForReview(anyString(), any())).thenReturn(true);

        service.create("u1", request(), "10.0.0.1");

        assertThat(capturedStatus()).isEqualTo("PENDING");
    }

    @Test
    void 同商品IP超限时拒绝() {
        stubPurchasedOrder(LocalDateTime.of(2026, 8, 1, 10, 0));
        when(floodGuard.recordAndCheck(anyLong(), anyString()))
                .thenReturn(OptionalInt.of(ReviewFloodGuard.MAX_SAME_SPU_PER_IP + 1));

        assertThatThrownBy(() -> service.create("u1", request(), "10.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("频繁");
        verify(reviewMapper, never()).insert(any(ReviewEntity.class));
    }

    @Test
    void Redis判不了时放行() {
        stubPurchasedOrder(LocalDateTime.of(2026, 8, 1, 10, 0));
        when(floodGuard.recordAndCheck(anyLong(), anyString())).thenReturn(OptionalInt.empty());
        when(newAccountGuard.shouldHoldForReview(anyString(), any())).thenReturn(false);

        service.create("u1", request(), "10.0.0.1");

        assertThat(capturedStatus()).isEqualTo("PUBLISHED");
    }

    private void stubPurchasedOrder(LocalDateTime receivedAt) {
        OrderItemResponse item = OrderItemResponse.builder()
                .id(500L).spuId(1L).skuId(1L).spuName("维生素 D3 软胶囊").build();
        OrderResponse order = OrderResponse.builder()
                .id(300L).status("RECEIVED").receivedAt(receivedAt).items(List.of(item)).build();
        when(orderClient.getOrder(anyString(), anyLong())).thenReturn(Result.success(order));
    }

    private static CreateReviewRequest request() {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrderId(300L);
        request.setOrderItemId(500L);
        request.setRating(5);
        return request;
    }

    private String capturedStatus() {
        ArgumentCaptor<ReviewEntity> captor = ArgumentCaptor.forClass(ReviewEntity.class);
        verify(reviewMapper).insert(captor.capture());
        return captor.getValue().getStatus();
    }
}
