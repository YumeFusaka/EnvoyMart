package yumefusaka.envoymart.productservice.projection;

import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 外部写有界等待；即使远端晚到，单调版本仍阻止覆盖较新投影。 */
@Component
public class ProjectionExternalCalls implements AutoCloseable {
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    public void run(Runnable write) {
        call(() -> { write.run(); return null; });
    }
    public <T> T call(java.util.function.Supplier<T> read) {
        var task = executor.submit(read::get);
        try { return task.get(5, TimeUnit.SECONDS); }
        catch (Exception failure) {
            task.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("外部投影调用失败或超时", failure);
        }
    }
    @jakarta.annotation.PreDestroy
    @Override public void close() {
        executor.shutdownNow();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
