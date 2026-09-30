package yumefusaka.envoymart.productservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.common.result.PageResult;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.RequireAdmin;
import yumefusaka.envoymart.productservice.model.admin.AdminSpuDetail;
import yumefusaka.envoymart.productservice.model.admin.AdminSpuQuery;
import yumefusaka.envoymart.productservice.model.admin.AdminSpuSummary;
import yumefusaka.envoymart.productservice.model.admin.SpuUpsertRequest;
import yumefusaka.envoymart.productservice.model.admin.StockAdjustRequest;
import yumefusaka.envoymart.productservice.service.ProductAdminService;

/**
 * 商品的管理接口。
 * <p>
 * <b>路径挂在 {@code /products/admin} 下</b>，而不是单开一个 {@code /admin/products}——
 * 网关的公开规则是按前缀写的（{@code GET /products/**} 免登录），
 * 而 {@code /admin/**} 是一个新的顶层段，要额外配一条路由。挂在原有前缀下，
 * 路由不用动，免登录规则由网关的 {@code /admin} 段判据统一排除（见 {@code JwtGatewayFilter}）。
 * <p>
 * <b>为什么这个类必须走网关</b>：{@link RequireAdmin} 判定的是 {@code X-User-Role}，
 * 而那个头只有网关注入得了（它从签了名的 Token 里取角色，且会先剥掉客户端自带的）。
 * 直连 9002 时带着 {@code X-User-Id} 会被 {@code InternalCallFilter} 拦住（声称身份就得给凭证），
 * 不带则 {@code AdminGuardInterceptor} 直接 401——两条路都进不来。
 */
@RequireAdmin
@RestController
@RequestMapping("/products/admin")
public class ProductAdminController {

    private final ProductAdminService adminService;

    public ProductAdminController(ProductAdminService adminService) {
        this.adminService = adminService;
    }

    /** 管理列表：可按状态、类目、品牌、关键词筛选，含下架商品 */
    @GetMapping("/spus")
    public Result<PageResult<AdminSpuSummary>> list(AdminSpuQuery query) {
        return Result.success(adminService.list(query));
    }

    /** 编辑表单的回显数据：规格、SKU（含停用的）、参数全在里面 */
    @GetMapping("/spus/{id}")
    public Result<AdminSpuDetail> detail(@PathVariable("id") Long id) {
        return Result.success(adminService.detail(id));
    }

    /**
     * 新建商品。<b>SPU、规格、SKU、参数一次提交、一个事务</b>。
     * <p>
     * 不做成「先建商品、再逐个加规格、再加 SKU」的多步接口：那样中途失败会留下一个
     * 规格改了、SKU 没改的商品，而这个状态在界面上看不出是坏的。
     */
    @PostMapping("/spus")
    public Result<Long> create(@Valid @RequestBody SpuUpsertRequest request,
                               @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        return Result.success(adminService.create(request, operatorId));
    }

    @PutMapping("/spus/{id}")
    public Result<Void> update(@PathVariable("id") Long id,
                               @Valid @RequestBody SpuUpsertRequest request,
                               @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        adminService.update(id, request, operatorId);
        return Result.success();
    }

    /**
     * 上下架。
     * <p>
     * 单独一个接口而不是靠 {@code update} 顺带做：列表页上要能直接点开关，
     * 不该为此把整个商品表单提交一遍——那既慢，又会让「只想下架」变成一次
     * 覆盖全部字段的写，把别人刚改的价格冲掉。
     */
    @PutMapping("/spus/{id}/status")
    public Result<Void> changeStatus(@PathVariable("id") Long id,
                                     @RequestParam("status") Integer status) {
        adminService.changeStatus(id, status);
        return Result.success();
    }

    /**
     * 删除商品。已被订单引用过的会被拒绝（要的是下架，不是删除）。
     * <p>
     * 用 {@code DELETE} 而不是 {@code POST /delete}：这是幂等语义的资源删除，
     * 删第二次与删第一次的结果都是「没有这个商品」。
     */
    @DeleteMapping("/spus/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        adminService.delete(id);
        return Result.success();
    }

    /**
     * 调整某个 SKU 的库存，留一条 {@code MANUAL} 类型的流水。
     * <p>
     * 提交的是<b>目标库存</b>而不是增量：表单里显示的就是库存本身，
     * 让前端算差值会在并发编辑时把「我看到的是 10」变成一次错误的加减。
     */
    @PutMapping("/skus/{skuId}/stock")
    public Result<Void> adjustStock(@PathVariable("skuId") Long skuId,
                                    @Valid @RequestBody StockAdjustRequest request,
                                    @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String operatorId) {
        adminService.adjustStock(skuId, request, operatorId);
        return Result.success();
    }
}
