package yumefusaka.envoymart.productservice.projection;

/** 当前源尚不能证明全局稳定；不得把等待当成投影成功。 */
public class ProjectionSourceUnresolvedException extends IllegalStateException {
    public ProjectionSourceUnresolvedException(String message) { super(message); }
}
