package yumefusaka.envoymart.common.context;

public final class BaseContext {

    private static final ThreadLocal<String> THREAD_LOCAL = new ThreadLocal<>();

    private BaseContext() {
    }

    public static void setCurrentId(String id) {
        THREAD_LOCAL.set(id);
    }

    public static String getCurrentId() {
        return THREAD_LOCAL.get();
    }

    public static void removeCurrentId() {
        THREAD_LOCAL.remove();
    }
}
