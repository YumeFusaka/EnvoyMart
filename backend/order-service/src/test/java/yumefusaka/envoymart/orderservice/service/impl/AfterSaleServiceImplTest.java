package yumefusaka.envoymart.orderservice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.contract.RefundResponse;
import yumefusaka.envoymart.orderservice.client.PaymentClient;
import yumefusaka.envoymart.orderservice.client.ProductClient;
import yumefusaka.envoymart.orderservice.entity.AfterSaleEntity;
import yumefusaka.envoymart.orderservice.entity.AfterSaleLogEntity;
import yumefusaka.envoymart.orderservice.entity.OrderEntity;
import yumefusaka.envoymart.orderservice.entity.OrderItemEntity;
import yumefusaka.envoymart.orderservice.mapper.AfterSaleLogMapper;
import yumefusaka.envoymart.orderservice.mapper.AfterSaleMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderItemMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderMapper;
import yumefusaka.envoymart.orderservice.mapper.OrderStatusLogMapper;
import yumefusaka.envoymart.orderservice.model.AfterSaleResponse;
import yumefusaka.envoymart.orderservice.model.AfterSaleStatus;
import yumefusaka.envoymart.orderservice.model.AfterSaleType;
import yumefusaka.envoymart.orderservice.model.admin.AdminAfterSaleQuery;
import yumefusaka.envoymart.orderservice.service.AfterSalePolicyEngine;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 售后管理侧的写路径约束。
 * <p>
 * 这里钉的三件事做错了都不会报错，只会到某天才暴露：
 * <ul>
 *   <li><b>审核不记审核人</b> —— 审核是这个系统里唯一由人决定钱去哪的地方，
 *       流水里那一列空了，事后没有任何办法回答「这笔退款是谁批的」。
 *       改成走管理接口之前，这条路一直传的是 null；</li>
 *   <li><b>重试退款不留流水</b> —— 重试本身不改状态，于是它「什么都不写」看起来天经地义。
 *       结果是同一张单被重试过几次只存在于日志里，而排障的人看的是流水；</li>
 *   <li><b>筛选条件互相短路</b> —— {@code or} 的优先级低于 {@code and}，
 *       关键词那组不整体括起来，会把状态与类型条件一起废掉。</li>
 * </ul>
 * 守卫类的判断用 mock 精确摆前置状态比起服务更快，也更容易把边界摆到位。
 */
class AfterSaleServiceImplTest {

    private static final String OPERATOR = "u1003";

    private AfterSaleMapper afterSaleMapper;
    private AfterSaleLogMapper afterSaleLogMapper;
    private PaymentClient paymentClient;
    private AfterSaleServiceImpl service;

    /**
     * {@code LambdaQueryWrapper} 按 getter 反查列名，靠的是 MyBatis-Plus 的实体元数据缓存——
     * 那份缓存平时由 Spring 容器启动时建立，纯单元测试里没有。不建它会抛
     * "can not find lambda cache for this entity"，报的是列名解析失败，看不出真正原因。
     */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(AfterSaleEntity.class, AfterSaleLogEntity.class,
                OrderEntity.class, OrderItemEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        afterSaleMapper = mock(AfterSaleMapper.class);
        afterSaleLogMapper = mock(AfterSaleLogMapper.class);
        paymentClient = mock(PaymentClient.class);
        service = new AfterSaleServiceImpl(afterSaleMapper, afterSaleLogMapper,
                mock(OrderMapper.class), mock(OrderItemMapper.class),
                mock(OrderStatusLogMapper.class),
                mock(AfterSalePolicyEngine.class), paymentClient,
                mock(ProductClient.class));
    }

    // ==================== 审核 ====================

    @Test
    void 审核通过时把审核人写进流水() {
        when(afterSaleMapper.selectById(7L)).thenReturn(afterSale(7L, AfterSaleStatus.APPLIED));
        when(afterSaleMapper.update(any(), any())).thenReturn(1);

        // 用换货类型：它要寄回，审核通过后停在「待寄回」，不会去调支付服务退款
        service.audit(7L, true, null, OPERATOR);

        AfterSaleLogEntity log = capturedLog();
        assertThat(log.getToStatus()).isEqualTo(AfterSaleStatus.APPROVED.name());
        assertThat(log.getOperatorType()).isEqualTo("ADMIN");
        assertThat(log.getOperatorId())
                .as("没有审核人的审核流水，证明不了这笔退款是谁批的")
                .isEqualTo(OPERATOR);
    }

    @Test
    void 驳回时必须给理由() {
        when(afterSaleMapper.selectById(7L)).thenReturn(afterSale(7L, AfterSaleStatus.APPLIED));

        assertThatThrownBy(() -> service.audit(7L, false, "  ", OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("理由");

        // 关键不是「抛了异常」，而是**一行都没改**：理由没落地就驳回，
        // 用户看到的是一次没有原因的拒绝，只能反复申诉
        verify(afterSaleMapper, never()).update(any(), any());
        verify(afterSaleLogMapper, never()).insert(any(AfterSaleLogEntity.class));
    }

    @Test
    void 已处理过的售后单不能再审核() {
        when(afterSaleMapper.selectById(7L)).thenReturn(afterSale(7L, AfterSaleStatus.FINISHED));

        assertThatThrownBy(() -> service.audit(7L, true, null, OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可审核");
    }

    @Test
    void 审核不存在的售后单被拒() {
        when(afterSaleMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.audit(404L, true, null, OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("售后单不存在");
    }

    // ==================== 重试退款 ====================

    @Test
    void 重试退款留一条流水记下是谁点的() {
        when(afterSaleMapper.selectById(7L)).thenReturn(afterSale(7L, AfterSaleStatus.REFUNDING));
        // 支付服务不可达 → refundFromRefunding 内部吞掉异常，售后单停在退款中
        when(afterSaleMapper.update(any(), any())).thenReturn(0);

        service.retryRefund(7L, OPERATOR);

        AfterSaleLogEntity log = capturedLog();
        assertThat(log.getFromStatus()).as("重试不改状态，前后都是退款中")
                .isEqualTo(AfterSaleStatus.REFUNDING.name());
        assertThat(log.getToStatus()).isEqualTo(AfterSaleStatus.REFUNDING.name());
        assertThat(log.getOperatorId())
                .as("同一张单被重试过几次，是排障时最先要看的东西")
                .isEqualTo(OPERATOR);
    }

    @Test
    void 不在退款中的售后单不能重试退款() {
        when(afterSaleMapper.selectById(7L)).thenReturn(afterSale(7L, AfterSaleStatus.APPLIED));

        assertThatThrownBy(() -> service.retryRefund(7L, OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("退款中");
    }

    // ==================== 管理列表的查询条件 ====================

    @Test
    void 关键词与状态筛选必须同时生效() {
        LambdaQueryWrapper<AfterSaleEntity> wrapper = wrapperOf(query("AS2026", "APPLIED", null));
        String sql = wrapper.getSqlSegment();

        assertThat(sql).as("状态条件不能让关键词的 or 短路掉").contains("status");
        // 三个关键词条件必须整体括起来。or 的优先级低于 and，散着写会让
        // `status = ? AND after_sale_no LIKE ? OR order_no LIKE ? OR user_id LIKE ?`
        // 解析成 `(状态 AND 单号) OR 订单号 OR 用户`——于是「按状态筛选 + 关键词搜索」
        // 会返回**别的状态**的售后单，而页面看起来一切正常
        assertThat(hasTopLevelOr(sql))
                .as("关键词那组没被整体括起来，最外层出现了 OR，前面的筛选条件全被短路：%s", sql)
                .isFalse();
    }

    @Test
    void 状态支持逗号分隔的多个值() {
        LambdaQueryWrapper<AfterSaleEntity> wrapper = wrapperOf(query(null, "APPLIED, APPROVED", null));

        assertThat(wrapper.getSqlSegment()).as("工作台要看的是「待我处理的」，那是两三个状态的并集")
                .containsIgnoringCase("in (");
        assertThat(wrapper.getParamNameValuePairs())
                .as("两个状态就该绑两个值：只剩一个说明逗号那串被当成单个状态值了")
                .hasSize(2);
    }

    @Test
    void 排序带唯一兜底列避免翻页漏数据() {
        String sql = wrapperOf(query(null, null, null)).getSqlSegment();

        // 只按 applied_at 排时，同一秒申请的两张单在数据库眼里没有确定的先后，
        // 翻页会同时漏掉一条、重复另一条 —— 而这个现象只在有并发申请时出现，
        // 看起来像随机丢数据
        assertThat(sql).endsWith("ORDER BY applied_at DESC,id DESC");
    }

    @Test
    void 非法的售后类型被拒而不是被忽略() {
        assertThatThrownBy(() -> wrapperOf(query(null, null, "WARRANTY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WARRANTY");
    }

    /**
     * SQL 里有没有出现在**最外层**的 {@code OR}。
     * <p>
     * 直接找 {@code "or"} 是不行的：{@code order_no} 里就有一个。这个判断要走一遍括号深度，
     * 只有不在任何括号里的 {@code OR} 才算数——那就是「它把前面的 and 条件短路了」。
     */
    private static boolean hasTopLevelOr(String sql) {
        int depth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && sql.regionMatches(true, i, "or", 0, 2)
                    && i > 0 && !Character.isLetterOrDigit(sql.charAt(i - 1))
                    && (i + 2 >= sql.length() || !Character.isLetterOrDigit(sql.charAt(i + 2)))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void 管理列表带上申请人() {
        when(afterSaleMapper.selectPage(any(), any())).thenReturn(pageOf(afterSale(7L, AfterSaleStatus.APPLIED)));

        PageResult<AfterSaleResponse> result = service.adminPage(query(null, null, null));

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getUserId())
                .as("工作台上只写「退货退款 待审核」，处理的人不知道是谁提的——"
                        + "而这决定了要不要先打个电话问问")
                .isEqualTo("u1001");
    }

    // ==================== 重试退款 ====================

    @Test
    void 重试退款时状态推不动要抛出去而不是记一笔日志() {
        when(afterSaleMapper.selectById(7L)).thenReturn(afterSale(7L, AfterSaleStatus.REFUNDING));
        when(paymentClient.refundForOrder(any())).thenReturn(Result.success(RefundResponse.builder()
                .refundNo("RF20260930001").amount(9900L).status("SUCCESS").build()));
        // 另一个并发请求先把它推到了「已完成」，条件更新影响 0 行
        when(afterSaleMapper.update(any(), any())).thenReturn(0);

        // 钱这时候已经退出去了。把并发冲突吞成一句 ERROR 日志，运营看到的是
        // 「退款失败」的响应，于是再点一次重试 —— 而真正该做的是刷新看状态
        assertThatThrownBy(() -> service.retryRefund(7L, OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("状态刚刚发生变化");
    }

    @Test
    void 列表筛选的非法状态要报错而不是返回空结果() {
        assertThatThrownBy(() -> wrapperOf(query(null, "NOPE", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("状态");
    }

    // ==================== 夹具 ====================

    /** 走一遍真实的查询构造并把 wrapper 截下来 —— 它是私有的，只能从 adminPage 进去拿 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<AfterSaleEntity> wrapperOf(AdminAfterSaleQuery query) {
        when(afterSaleMapper.selectPage(any(), any())).thenReturn(new Page<>());

        service.adminPage(query);

        ArgumentCaptor<LambdaQueryWrapper<AfterSaleEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(afterSaleMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Page<AfterSaleEntity> pageOf(AfterSaleEntity... entities) {
        Page<AfterSaleEntity> page = new Page<>();
        page.setRecords(List.of(entities));
        page.setTotal(entities.length);
        return page;
    }

    private AdminAfterSaleQuery query(String keyword, String status, String type) {
        AdminAfterSaleQuery query = new AdminAfterSaleQuery();
        query.setKeyword(keyword);
        query.setStatus(status);
        query.setType(type);
        return query;
    }

    private AfterSaleLogEntity capturedLog() {
        ArgumentCaptor<AfterSaleLogEntity> captor = ArgumentCaptor.forClass(AfterSaleLogEntity.class);
        verify(afterSaleLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    private AfterSaleEntity afterSale(Long id, AfterSaleStatus status) {
        AfterSaleEntity entity = new AfterSaleEntity();
        entity.setId(id);
        entity.setAfterSaleNo("AS20260930" + id);
        entity.setOrderId(10L);
        entity.setOrderNo("YS20260930" + id);
        entity.setOrderItemId(100L);
        entity.setUserId("u1001");
        entity.setType(AfterSaleType.EXCHANGE);
        entity.setStatus(status.name());
        entity.setReason("不喜欢");
        entity.setRefundAmount(9900L);
        entity.setAppliedAt(LocalDateTime.now());
        return entity;
    }
}
