package yumefusaka.envoymart.common.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页返回体。
 * <p>
 * 只回 {@code records} 是不够的：前端要渲染页码、要知道「还有没有下一页」，
 * 没有 {@code total} 就只能靠「返回条数是否等于 size」去猜最后一页 —— 那种猜法
 * 在总数恰好是 size 的整数倍时会多出一个空页。
 * <p>
 * <b>两个构造器注解不是形式主义</b>：{@code @Builder} 生成的是全参构造，
 * 而 <b>{@code @Data} 在同名注解在场时不会补一个无参构造</b>。只写 {@code @Builder} 的类
 * <b>序列化正常、反序列化抛异常</b>（{@code Cannot construct instance ... no Creators,
 * like default constructor, exist}）—— 因为出参只需要 getter，入参需要构造器。
 * 表现是「接口 200、应答是合法 JSON，消费方却报解不开」，而报错里只有类名、
 * 看不出是缺构造器。{@code contract} 下的 DTO 全都成对写了这两个注解，本类曾经是唯一例外：
 * 直到 ai-service 的商品工具开始接 {@code Result<PageResult<ProductSummary>>}
 * （此前它接的是裸 {@code List}，不需要构造器），整条商品检索才炸在解码这一步上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records;
    /** 符合条件的总条数（不是当前页条数） */
    private long total;
    /** 当前页，从 0 开始 */
    private int page;
    private int size;

    public boolean hasNext() {
        return (long) (page + 1) * size < total;
    }
}
