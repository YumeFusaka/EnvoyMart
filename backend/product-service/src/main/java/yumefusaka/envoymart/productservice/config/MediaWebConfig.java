package yumefusaka.envoymart.productservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 上传图片的静态访问映射 —— 把磁盘上的媒体目录挂到 {@code /media/**}。
 * <p>
 * <b>为什么图片要由服务端直接吐，而不是让前端 public 目录承载。</b>
 * 前端 {@code public/} 下的文件是<b>构建期</b>产物的一部分：运营上传一张图之后，
 * 除非重新 {@code vite build}，生产产物里就没有它。而图片上传本来就发生在运行期，
 * 让运行期的写入落进构建期的资源目录，等于要求「每次上传后重新构建前端」——
 * 这不是一个能被演示环境接受的操作。服务端映射与构建无关，上传即可见。
 * <p>
 * <b>这个前缀必须是公开可读的。</b>浏览器加载 {@code <img src="/media/...">} 不会带
 * {@code Authorization} 头，所以它不可能走「先登录再取图」那条路；网关那边也据此
 * 把 {@code GET /media/**} 放进了白名单。图片里没有用户数据，公开它不泄露任何东西。
 */
@Configuration
public class MediaWebConfig implements WebMvcConfigurer {

    private final String mediaDir;

    public MediaWebConfig(@Value("${envoymart.media.dir:./data/media}") String mediaDir) {
        this.mediaDir = mediaDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // file: 前缀的绝对路径必须用 URI 形式（三斜杠）。直接用 Windows 的
        // "E:\..." 会被当成 classpath 相对路径，映射静默失效 —— 症状是接口上传成功、
        // 图片 404，而两边日志都干干净净
        String location = Path.of(mediaDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/media/**")
                .addResourceLocations(location)
                // 上传后文件名是随机的、内容永不变，可以放心让浏览器长期缓存：
                // 换图必然换文件名，不存在「缓存了旧图」的问题
                .setCachePeriod(60 * 60 * 24 * 30);
    }
}
