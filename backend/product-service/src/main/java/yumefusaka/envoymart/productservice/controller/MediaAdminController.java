package yumefusaka.envoymart.productservice.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.RequireAdmin;
import yumefusaka.envoymart.productservice.service.MediaStorageService;

import java.util.Map;

/**
 * 图片上传接口。
 * <p>
 * <b>路径挂在 {@code /products/admin} 下，理由与 {@link ProductAdminController} 相同</b>：
 * 复用既有的 {@code /products/**} 网关路由，不必新增一条；同时被网关的 {@code /admin}
 * 段判据强制要求登录 + ADMIN 角色（见 {@code JwtGatewayFilter} 的 {@code ADMIN_SEGMENT}）。
 * <p>
 * 只提供上传，不提供删除。删除要先回答「这张图被哪些商品引用」，而图片字段是纯文本
 * 相对路径、没有外键，删了就是前台破图。运营换图时直接上传新的、把 URL 换掉即可——
 * 旧文件留在盘上属于可接受的垃圾，误删在售商品的图却是线上事故。
 */
@RequireAdmin
@RestController
@RequestMapping("/products/admin/media")
public class MediaAdminController {

    private final MediaStorageService mediaStorageService;

    public MediaAdminController(MediaStorageService mediaStorageService) {
        this.mediaStorageService = mediaStorageService;
    }

    /**
     * 上传一张商品图片，返回可写入 {@code mainImage} / {@code images} 的相对路径。
     * <p>
     * 返回 JSON 对象而不是裸字符串：上传接口后续要加「宽度、高度、去重命中」这类字段时，
     * 加在对象里是兼容的，裸字符串则只能改成对象、破坏已有调用方。
     */
    @PostMapping("/images")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(Map.of("url", mediaStorageService.store(file)));
    }
}
