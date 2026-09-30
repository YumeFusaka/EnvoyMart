package yumefusaka.envoymart.authservice.model.admin;

import lombok.Data;
import yumefusaka.envoymart.authservice.model.UserRoles;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 用户管理列表的查询条件。
 * <p>
 * <b>没有「按密码查」这种东西</b>：口令是 BCrypt 哈希，等值查询永远匹配不上，
 * 而且它本来就不该成为筛选维度。
 */
@Data
public class AdminUserQuery {

    /** 关键词：用户名 / 昵称 / 手机号 / 邮箱四选一命中 */
    private String keyword;
    /** 角色，逗号分隔可多值 */
    private String role;
    /** 0 禁用 / 1 正常。不传即不筛 */
    private Integer status;
    /** 对外页码，<b>从 0 开始</b>——与 {@code PageResult.page}、前端「第 N 页」一致 */
    private Integer page;
    private Integer size;

    /**
     * 解析角色筛选，逗号分隔。
     * <p>
     * 认识不了的取值直接抛 400，不静默丢弃：页面勾了「管理员」却因为拼错而返回全部用户，
     * 看的人会以为系统里就这些人。
     */
    public List<String> roleList() {
        if (role == null || role.isBlank()) {
            return List.of();
        }
        List<String> roles = Arrays.stream(role.split(","))
                .map(r -> r.trim().toUpperCase(Locale.ROOT))
                .filter(r -> !r.isEmpty())
                .distinct()
                .toList();
        for (String r : roles) {
            if (!UserRoles.ASSIGNABLE.contains(r)) {
                throw new IllegalArgumentException("不支持的角色：" + r);
            }
        }
        return roles;
    }

    /**
     * 对外页码，<b>从 0 开始</b>。
     * <p>
     * <b>不要拿它直接构造 MyBatis-Plus 的 {@code Page}</b>：那边 {@code current} 从 1 开始，
     * 且 {@code offset()} 对 {@code current <= 1} 一律返回 0，于是第 0 页与第 1 页查出
     * 同一批数据、之后整体后移一页，最后一页永远取不到。要传给 MP 用 {@link #mpCurrent()}。
     */
    public int zeroBasedPage() {
        return page == null || page < 0 ? 0 : page;
    }

    /** MyBatis-Plus 的页码从 1 开始。这步转换只留这一个出处，免得各调用点各自 +1 */
    public long mpCurrent() {
        return zeroBasedPage() + 1L;
    }

    /** 每页条数上限 100：再大就不是「翻页」而是「导出」了，不该由列表接口承担 */
    public int safeSize() {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
