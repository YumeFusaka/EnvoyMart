package yumefusaka.envoymart.productservice.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import yumefusaka.envoymart.productservice.service.MediaStorageService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 图片落盘实现。
 * <p>
 * <b>三项校验一个都不能省，理由各不相同：</b>
 * <ul>
 *   <li><b>扩展名白名单</b>：防止有人传 {@code .jsp/.html/.svg} 进来。SVG 尤其危险 ——
 *       它能内嵌 {@code <script>}，而图片是被 {@code <img>} 直接渲染的，浏览器会执行它；
 *       本项目商品图片历史上用的就是 SVG，但那是<b>我们自己造的静态资源</b>，
 *       与「让运营上传任意 SVG」是两回事。</li>
 *   <li><b>大小上限</b>：既防磁盘被一次请求写满，也防内存里一次性驻留过大文件。</li>
 *   <li><b>随机文件名</b>：用户提供的文件名可能含 {@code ../} 或 {@code \0}。
 *       完全丢弃原名、只用 UUID，是唯一不必去推敲「哪些字符算危险」的做法。</li>
 * </ul>
 */
@Slf4j
@Service
public class MediaStorageServiceImpl implements MediaStorageService {

    /** 允许的图片类型。SVG 刻意不在列，理由见类注释 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");
    /** 扩展名 → 标准 MIME。用它写出 {@code Content-Type}，而不是回显客户端上传时的声明 */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "webp", "image/webp");
    /** 单张图片上限 5MB。商品图给到这个量级足够，再大属于素材没做优化 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyyMM");

    private final Path root;

    public MediaStorageServiceImpl(@Value("${envoymart.media.dir:./data/media}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        log.info("[Media] 图片存储根目录: {}", root);
    }

    @Override
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("图片不能超过 5MB");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (extension == null) {
            throw new IllegalArgumentException("只支持 jpg / png / gif / webp 格式的图片");
        }

        // 按月分目录：单目录几万个文件时，ls 与某些文件系统的目录项查找都会明显变慢，
        // 而按月的粒度既让目录保持小，又不会碎成一天一个
        String month = LocalDate.now().format(MONTH_DIR);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path directory = root.resolve(month).normalize();
        Path target = directory.resolve(filename).normalize();
        // 目录与文件名都由本类生成，但拼完仍校验一次：这条断言防的是「将来有人把 month
        // 改成用户输入」这类改动，写在这里比写在文档里可靠
        if (!target.startsWith(root)) {
            throw new IllegalStateException("非法的存储路径");
        }

        try {
            Files.createDirectories(directory);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 落盘失败必须抛：返回一个指向不存在文件的 URL，比报错难查得多 ——
            // 页面上的表现是「破图」，而没人能把破图与一次磁盘写失败联系起来
            throw new IllegalStateException("图片保存失败，请稍后重试", e);
        }
        String path = "/media/" + month + "/" + filename;
        log.info("[Media] 已保存上传图片 {}（{} 字节）", path, file.getSize());
        return path;
    }

    /** 取小写扩展名；不在白名单里一律返回 null（调用方据此报错） */
    private static String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return null;
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return null;
        }
        String extension = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.contains(extension) ? extension : null;
    }

    /** 供静态资源映射使用：扩展名 → MIME。未知扩展名回落到 {@code application/octet-stream} */
    public static String contentTypeOf(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        return CONTENT_TYPES.getOrDefault(filename.substring(dot + 1).toLowerCase(Locale.ROOT),
                "application/octet-stream");
    }
}
