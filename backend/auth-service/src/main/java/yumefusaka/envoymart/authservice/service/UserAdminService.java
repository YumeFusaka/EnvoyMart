package yumefusaka.envoymart.authservice.service;

import yumefusaka.envoymart.authservice.model.admin.AdminUserDetail;
import yumefusaka.envoymart.authservice.model.admin.AdminUserQuery;
import yumefusaka.envoymart.authservice.model.admin.AdminUserSummary;
import yumefusaka.envoymart.common.result.PageResult;

/**
 * 用户的管理接口。
 * <p>
 * <b>这里有两个别人没有的约束。</b>
 * <p>
 * 一是<b>禁用与启用不只是改一个字段</b>：它必须同时落到 Redis 的认证态上，否则手里的
 * Token 会继续用到过期（TTL 7 天），等于没禁用。写 Redis 失败要连同数据库一起回滚，
 * 见 {@code AuthStateStore} 的类注释。
 * <p>
 * 二是<b>不能对自己动手</b>：管理员把自己禁用了，或者把自己降成普通用户，这个操作
 * 没有人能撤销——能撤销它的那个权限，正好是刚刚被自己丢掉的那个。这是全项目唯一一处
 * 「操作成功即失去补救能力」的路径，所以在服务层直接拒绝。
 */
public interface UserAdminService {

    /** 用户列表：可按用户名 / 昵称 / 手机号 / 邮箱关键词、角色、状态筛选 */
    PageResult<AdminUserSummary> list(AdminUserQuery query);

    /** 用户详情：基本信息 + 地址簿条数（地址内容不给） */
    AdminUserDetail detail(String userId);

    /**
     * 启用 / 禁用。
     *
     * @param status     {@code 0} 禁用 / {@code 1} 启用，其它值抛 400
     * @param reason     禁用原因，禁用时必填；启用时忽略
     * @param operatorId 操作人，来自网关注入的身份头
     */
    AdminUserSummary changeStatus(String userId, Integer status, String reason, String operatorId);

    /** 调整角色。取值限于 {@code UserRoles.ASSIGNABLE}，在服务层按大写归一后校验 */
    AdminUserSummary changeRole(String userId, String role, String operatorId);
}
