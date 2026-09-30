package yumefusaka.envoymart.authservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.authservice.model.admin.AdminUserDetail;
import yumefusaka.envoymart.authservice.model.admin.AdminUserQuery;
import yumefusaka.envoymart.authservice.model.admin.AdminUserSummary;
import yumefusaka.envoymart.authservice.model.admin.UserRoleRequest;
import yumefusaka.envoymart.authservice.model.admin.UserStatusRequest;
import yumefusaka.envoymart.authservice.service.UserAdminService;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.RequireAdmin;

/**
 * 用户的管理接口。
 * <p>
 * 路径挂在 {@code /auth/admin} 下，而不是单开 {@code /admin/users}：网关的路由按前缀配
 * （{@code /auth/**} 已有），挂在原有前缀下路由不用动；而「管理接口不是公开资源」
 * 由网关的 {@code /admin} 段判据统一排除（见 {@code JwtGatewayFilter}）。
 * 这是批次 A 定下的约定，批次 C 跟着走，这里也一样。
 * <p>
 * <b>操作人只从 {@link IdentityHeaderInterceptor#USER_ID_HEADER} 取</b>，不接受请求体里带的
 * 「操作人」字段——那个字段任何人都能填。头是网关剥掉客户端传入后重新注入的，才是可信的。
 */
@RequireAdmin
@RestController
@RequestMapping("/auth/admin/users")
public class UserAdminController {

    private final UserAdminService adminService;

    public UserAdminController(UserAdminService adminService) {
        this.adminService = adminService;
    }

    /** 用户列表：可按用户名 / 昵称 / 手机号 / 邮箱关键词、角色、状态筛选 */
    @GetMapping
    public Result<PageResult<AdminUserSummary>> list(AdminUserQuery query) {
        return Result.success(adminService.list(query));
    }

    @GetMapping("/{id}")
    public Result<AdminUserDetail> detail(@PathVariable("id") String id) {
        return Result.success(adminService.detail(id));
    }

    /**
     * 启用 / 禁用。禁用必须带原因。
     * <p>
     * 用 {@code PUT} 而不是 {@code POST}：状态是用户上的一个字段，重复提交同一个值
     * 结果完全一样（服务层对「状态没变」直接跳过，不会刷新留痕）。
     */
    @PutMapping("/{id}/status")
    public Result<AdminUserSummary> changeStatus(@PathVariable("id") String id,
                                                 @Valid @RequestBody UserStatusRequest request,
                                                 @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(adminService.changeStatus(id, request.getStatus(), request.getReason(), operatorId));
    }

    /**
     * 调整角色。
     * <p>
     * <b>权限的授予与回收都是管理端接口</b>：没有「申请成为管理员」这种入口，
     * 谁能管什么完全由这里决定，所以服务层会拒绝「把自己降权」这种不可撤销的操作。
     */
    @PutMapping("/{id}/role")
    public Result<AdminUserSummary> changeRole(@PathVariable("id") String id,
                                               @Valid @RequestBody UserRoleRequest request,
                                               @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(adminService.changeRole(id, request.getRole(), operatorId));
    }
}
