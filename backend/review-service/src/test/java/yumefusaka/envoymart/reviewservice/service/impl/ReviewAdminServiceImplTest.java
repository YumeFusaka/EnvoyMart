package yumefusaka.envoymart.reviewservice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.util.Times;
import yumefusaka.envoymart.reviewservice.config.MybatisPlusConfig;
import yumefusaka.envoymart.reviewservice.entity.ReviewEntity;
import yumefusaka.envoymart.reviewservice.entity.ReviewImageEntity;
import yumefusaka.envoymart.reviewservice.mapper.ReviewImageMapper;
import yumefusaka.envoymart.reviewservice.mapper.ReviewMapper;
import yumefusaka.envoymart.reviewservice.model.admin.AdminReviewQuery;
import yumefusaka.envoymart.reviewservice.model.admin.AdminReviewSummary;
import yumefusaka.envoymart.reviewservice.mq.ReviewAggregatePublisher;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评价管理侧的约束。
 * <p>
 * 这里钉的都是<b>做错了不会报错</b>的事：
 * <ul>
 *   <li><b>隐藏不留痕</b> —— 商家隐藏差评是这个系统里唯一可以「让别人的话消失」的动作。
 *       少写一次原因，事后就永远答不出「这条评价为什么没了」；</li>
 *   <li><b>恢复时不清空隐藏记录</b> —— 状态回到 PUBLISHED 而 hidden_reason 还留着，
 *       「按原因排查」会查出已经被撤销的操作，而页面上一切正常；</li>
 *   <li><b>匿名评价在管理端也被脱敏</b> —— 公开侧抹掉 userId 是对的，管理端照抄就等于
 *       运营永远不知道那条匿名差评是谁写的；</li>
 *   <li><b>分页拦截器没注册</b> —— {@code selectPage} 不报错，只是把全表返回回来。</li>
 * </ul>
 */
class ReviewAdminServiceImplTest {

    private static final String OPERATOR = "u1003";

    private ReviewMapper reviewMapper;
    private ReviewImageMapper reviewImageMapper;
    private ReviewAggregatePublisher aggregatePublisher;
    private ReviewAdminServiceImpl service;

    /**
     * {@code LambdaQueryWrapper} 按 getter 反查列名，靠的是 MyBatis-Plus 的实体元数据缓存 ——
     * 那份缓存平时由 Spring 容器启动时建立，纯单元测试里没有。不建它会抛
     * "can not find lambda cache for this entity"，报的是列名解析失败，看不出真正原因。
     */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ReviewEntity.class);
        TableInfoHelper.initTableInfo(assistant, ReviewImageEntity.class);
    }

    @BeforeEach
    void setUp() {
        reviewMapper = mock(ReviewMapper.class);
        reviewImageMapper = mock(ReviewImageMapper.class);
        aggregatePublisher = mock(ReviewAggregatePublisher.class);
        service = new ReviewAdminServiceImpl(reviewMapper, reviewImageMapper, aggregatePublisher);
    }

    @Test
    void 隐藏评价必须填写原因() {
        when(reviewMapper.selectById(1L)).thenReturn(review(1L, "PUBLISHED"));

        assertThatThrownBy(() -> service.changeStatus(1L, "HIDDEN", "   ", OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原因");
        verify(reviewMapper, never()).update(any(), any());
    }

    @Test
    void 隐藏时写入原因与操作人() {
        when(reviewMapper.selectById(1L))
                .thenReturn(review(1L, "PUBLISHED"), review(1L, "HIDDEN"));

        service.changeStatus(1L, "hidden", "含违禁词", OPERATOR);

        LambdaUpdateWrapper<ReviewEntity> update = capturedUpdate();
        assertThat(update.getSqlSet())
                .contains("status")
                .contains("hidden_reason")
                .contains("hidden_by")
                .contains("hidden_at");
        assertThat(update.getParamNameValuePairs().values())
                .as("状态、原因、操作人都要落库")
                .contains("HIDDEN", "含违禁词", OPERATOR);
    }

    @Test
    void 恢复时清空隐藏记录() {
        when(reviewMapper.selectById(1L))
                .thenReturn(review(1L, "HIDDEN"), review(1L, "PUBLISHED"));

        service.changeStatus(1L, "PUBLISHED", null, OPERATOR);

        LambdaUpdateWrapper<ReviewEntity> update = capturedUpdate();
        assertThat(update.getSqlSet())
                .as("三个隐藏列都要显式置 null，否则恢复后的评价仍带着旧的隐藏原因")
                .contains("hidden_reason")
                .contains("hidden_by")
                .contains("hidden_at");
        // 置 null 必须真的生成在 SQL 里：MyBatis-Plus 对 set(col, null) 不做过滤，
        // 生成的是参数化的 = ?，值为 null。若哪天换成被 if 包住的写法，这里会失败
        long nulls = update.getParamNameValuePairs().values().stream()
                .filter(Objects::isNull).count();
        assertThat(nulls).isEqualTo(3);
    }

    @Test
    void 状态没变时不刷新隐藏记录() {
        when(reviewMapper.selectById(1L)).thenReturn(review(1L, "HIDDEN"));

        service.changeStatus(1L, "HIDDEN", "换个说法", OPERATOR);

        // 重复点击不该产生副作用：否则「谁在什么时候因为什么隐藏的」
        // 会指向最后一个手滑的人，而不是真正做决定的那次
        verify(reviewMapper, never()).update(any(), any());
    }

    @Test
    void 拒绝回复已隐藏的评价() {
        when(reviewMapper.selectById(1L)).thenReturn(review(1L, "HIDDEN"));

        assertThatThrownBy(() -> service.reply(1L, "亲，非常抱歉", OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("隐藏");
        verify(reviewMapper, never()).update(any(), any());
    }

    @Test
    void 撤回回复时三个字段都要清空() {
        when(reviewMapper.selectById(1L)).thenReturn(review(1L, "PUBLISHED"));

        service.reply(1L, "  ", OPERATOR);

        LambdaUpdateWrapper<ReviewEntity> update = capturedUpdate();
        assertThat(update.getSqlSet())
                .contains("reply_content")
                .contains("reply_by")
                .contains("reply_at");
        long nulls = update.getParamNameValuePairs().values().stream()
                .filter(Objects::isNull).count();
        assertThat(nulls)
                .as("撤回要清掉三个字段，只清 content 会留下一个已经说不通的时间与作者")
                .isEqualTo(3);
    }

    @Test
    void 管理端不抹掉匿名评价的用户id() {
        ReviewEntity anonymous = review(1L, "PUBLISHED");
        anonymous.setIsAnonymous(1);
        anonymous.setUserId("u1002");
        Page<ReviewEntity> page = new Page<>(0, 20);
        page.setRecords(List.of(anonymous));
        when(reviewMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<AdminReviewSummary> result = service.list(new AdminReviewQuery());

        assertThat(result.getRecords()).singleElement().satisfies(row -> {
            assertThat(row.getUserId())
                    .as("公开侧抹 userId 是脱敏，管理端照抄就等于查不出是谁在刷差评")
                    .isEqualTo("u1002");
            assertThat(row.getAnonymous()).isTrue();
        });
    }

    @Test
    void 未回复筛选走的是回复内容为空() {
        LambdaQueryWrapper<ReviewEntity> wrapper = capturedListWrapper(queryWithReply(false));

        assertThat(wrapper.getSqlSegment())
                .containsIgnoringCase("reply_content is null");
    }

    @Test
    void 非法状态与越界评分都要报错而不是静默忽略() {
        assertThatThrownBy(() -> capturedListWrapper(queryWithStatus("PUBLISHED,DELETED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("状态");

        AdminReviewQuery ratingQuery = new AdminReviewQuery();
        ratingQuery.setRating(6);
        assertThatThrownBy(() -> capturedListWrapper(ratingQuery))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("评分");
    }

    @Test
    void 列表排序带唯一兜底列() {
        LambdaQueryWrapper<ReviewEntity> wrapper = capturedListWrapper(new AdminReviewQuery());

        // 只按 created_at 排时，同一秒提交的两条评价翻页会漏一条、重一条
        assertThat(wrapper.getSqlSegment()).containsIgnoringCase("order by created_at desc,id desc");
    }

    @Test
    void 传给Mybatis的页码要加一() {
        when(reviewMapper.selectPage(any(), any())).thenReturn(new Page<>());
        service.list(new AdminReviewQuery());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<ReviewEntity>> captor = ArgumentCaptor.forClass(Page.class);
        verify(reviewMapper).selectPage(captor.capture(), any());
        assertThat(captor.getValue().getCurrent())
                .as("MyBatis-Plus 的 offset() 对 current <= 1 一律返回 0：把对外的第 0 页"
                        + "原样传进去，第 0 页与第 1 页会查出同一批数据，之后整体后移一页")
                .isEqualTo(1L);
    }

    @Test
    void 隐藏已发布评价要通知商品侧重算评分() {
        when(reviewMapper.selectById(1L))
                .thenReturn(review(1L, "PUBLISHED"), review(1L, "HIDDEN"));

        service.changeStatus(1L, "HIDDEN", "含违禁词", OPERATOR);

        // 隐藏掉一条一星差评，商品页的均分就该跟着涨。不发这条事件，
        // 商品侧那份冗余会一直带着一条已经被平台删掉的评价算出来的分数
        verify(aggregatePublisher).publishAfterCommit(1001L);
    }

    @Test
    void 与已发布无关的状态变更不发事件() {
        when(reviewMapper.selectById(1L))
                .thenReturn(review(1L, "PENDING"), review(1L, "HIDDEN"));

        service.changeStatus(1L, "HIDDEN", "含联系方式", OPERATOR);

        // PENDING 与 HIDDEN 都不在聚合里，互转对商品评分没有任何影响。
        // 无脑发一次会白白触发下游重算 + 缓存失效 + 索引重建
        verify(aggregatePublisher, never()).publishAfterCommit(any());
    }

    @Test
    void 分页拦截器必须注册() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();

        assertThat(interceptor.getInterceptors())
                .as("没有分页拦截器时 selectPage 不报错，只是静默返回全部数据")
                .anyMatch(inner -> inner instanceof PaginationInnerInterceptor);
    }

    private AdminReviewQuery queryWithReply(boolean hasReply) {
        AdminReviewQuery query = new AdminReviewQuery();
        query.setHasReply(hasReply);
        return query;
    }

    private AdminReviewQuery queryWithStatus(String status) {
        AdminReviewQuery query = new AdminReviewQuery();
        query.setStatus(status);
        return query;
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<ReviewEntity> capturedListWrapper(AdminReviewQuery query) {
        when(reviewMapper.selectPage(any(), any())).thenReturn(new Page<>());
        service.list(query);
        ArgumentCaptor<LambdaQueryWrapper<ReviewEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reviewMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<ReviewEntity> capturedUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<ReviewEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(reviewMapper).update(any(), captor.capture());
        return captor.getValue();
    }

    private static ReviewEntity review(long id, String status) {
        ReviewEntity entity = new ReviewEntity();
        entity.setId(id);
        entity.setSpuId(1001L);
        entity.setSkuId(2001L);
        entity.setOrderId(3001L);
        entity.setOrderItemId(4001L);
        entity.setUserId("u1002");
        entity.setRating(5);
        entity.setIsAnonymous(0);
        entity.setStatus(status);
        entity.setUsefulCount(0);
        entity.setCreatedAt(Times.now());
        return entity;
    }
}
