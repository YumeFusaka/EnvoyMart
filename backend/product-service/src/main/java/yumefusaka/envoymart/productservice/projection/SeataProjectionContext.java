package yumefusaka.envoymart.productservice.projection;

import org.apache.seata.core.context.RootContext;

import java.util.function.Supplier;

/** 派生发布不能借用生产者XID，否则全局锁会把自己的未决分支视为可读。 */
public final class SeataProjectionContext {
    private SeataProjectionContext() { }
    public static <T> T withoutXid(Supplier<T> action) {
        String original = RootContext.unbind();
        try { return action.get(); }
        finally { if (original != null) RootContext.bind(original); }
    }
}
