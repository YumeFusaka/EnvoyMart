package yumefusaka.envoymart.common.result;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分页返回体。
 * <p>
 * 只回 {@code records} 是不够的：前端要渲染页码、要知道「还有没有下一页」，
 * 没有 {@code total} 就只能靠「返回条数是否等于 size」去猜最后一页 —— 那种猜法
 * 在总数恰好是 size 的整数倍时会多出一个空页。
 */
@Data
@Builder
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
