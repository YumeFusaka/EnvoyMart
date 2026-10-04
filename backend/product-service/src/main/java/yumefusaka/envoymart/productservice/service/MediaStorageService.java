package yumefusaka.envoymart.productservice.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 图片存储 —— 把运营上传的图片落盘，返回一个可被浏览器直接访问的相对路径。
 * <p>
 * <b>为什么返回相对路径而不是完整 URL。</b>完整 URL 会把「这台机器此刻的域名与端口」
 * 冻进数据库。演示环境跑在 {@code 127.0.0.1:5173}，换一台机器、换一个域名，
 * 库里所有图片链接同时失效，而它们指向的文件其实都还在。相对路径由前端按当前站点
 * 拼接，换域名不用改一行数据。
 * <p>
 * <b>为什么存文件而不是存二进制进数据库。</b>图片要给 {@code <img>} 直接加载，
 * 走数据库就得每次读一行 BLOB 再吐出来，还要占掉商品表的行宽与备份体积。
 * 文件系统的静态资源映射就是为这件事准备的，这里不重复造。
 */
public interface MediaStorageService {

    /**
     * 保存一张图片。
     *
     * @return 形如 {@code /media/202610/xxxxxxxx.jpg} 的相对路径
     * @throws IllegalArgumentException 文件为空、类型不被允许或超出大小上限
     */
    String store(MultipartFile file);
}
