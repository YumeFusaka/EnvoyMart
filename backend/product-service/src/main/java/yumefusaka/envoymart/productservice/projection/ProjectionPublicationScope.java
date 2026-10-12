package yumefusaka.envoymart.productservice.projection;

/** 仅在消费者已锁全部源并核验后打开；发布器不能由普通sync入口绕入。 */
public final class ProjectionPublicationScope implements AutoCloseable {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private final Long previous;
    ProjectionPublicationScope(long spuId) { previous = CURRENT.get(); CURRENT.set(spuId); }
    public static void require(long spuId) {
        if (!Long.valueOf(spuId).equals(CURRENT.get())) throw new IllegalStateException("商品投影发布缺少稳定来源屏障");
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
