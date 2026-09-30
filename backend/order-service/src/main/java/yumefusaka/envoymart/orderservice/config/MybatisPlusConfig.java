package yumefusaka.envoymart.orderservice.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页拦截器。
 * <p>
 * <b>不加它，{@code selectPage} 不会报任何错，只是静默返回全部数据</b> —— 分页看上去
 * 是「能用的」，返回的 list 有内容、页码也画得出来，直到数据量涨上来把接口拖垮。
 * <p>
 * 放在本服务而不是 common：它需要两个 MyBatis-Plus 依赖（extension 与 jsqlparser），
 * 而 common 被 9 个模块依赖、其中网关根本不连库。为一个 10 行的配置让那个轻量库
 * 背上这些依赖不划算。需要分页的服务各配一份即可（product-service 已有一份同样的）。
 * <p>
 * 订单域此前<b>没有</b>这个文件，因为用户侧「我的订单」不分页 —— 一个人不会有几千单。
 * 管理侧反过来：它面对全库，不分页就是一次全表扫描。所以这个配置是随管理端一起来的。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 指定 DbType 而不是让 MP 去连库探测方言：省一次连接，
        // 且本项目固定用 MySQL 语法（H2 也跑在 MODE=MySQL 下）
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
