package yumefusaka.envoymart.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理接口标记 —— 标注后只有管理员能访问，交由 {@link AdminGuardInterceptor} 判定。
 * <p>
 * <b>默认拒绝</b>：没标的接口完全不受影响；标了的接口，凡是解析不出 ADMIN 角色的一律 403——
 * 新增管理接口时忘记配权限的后果是「谁都进不去」，而不是「谁都能进」。
 * <p>
 * 可标在方法或类上（标在类上等于该类所有接口都需要管理员）。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
