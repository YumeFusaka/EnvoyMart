package yumefusaka.envoymart.productservice.content;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * 商品详情正文的 HTML 白名单净化。
 * <p>
 * <b>为什么必须有它</b>：详情正文由管理员录入，而它在商品页是 {@code v-html} 直出的。
 * 也就是说「谁能写这个字段」等于「谁能在所有访客的浏览器上执行脚本」。当前能写的人只有
 * 管理员（见 {@code ProductAdminController}），但把 XSS 的防线押在「管理员不会作恶」上
 * 是错的——管理员账号会被盗、会被内部人滥用，而这段内容面向的是<b>全体访客</b>。
 * <p>
 * <b>为什么要写一遍而不是靠前端</b>：前端对同一个字段有两个消费方（商品页、以及未来的
 * 任何富文本展示），把净化放在前端等于每个消费方各净化一次，漏一个就是洞。
 * 服务端净化一次，入口收口。
 * <p>
 * <b>写和读都过一遍</b>：
 * <ul>
 *   <li>写入时净化，库里存的就是干净的——这是主要防线；</li>
 *   <li>读取时再净化一次，兜的是「净化上线之前已经写进去的数据」和「有人绕过接口直接改库」。
 *       它不增加正确性成本，因为详情是走缓存的：一次净化随一次缓存重建发生，
 *       不是每个请求都跑。</li>
 * </ul>
 * <p>
 * <b>为什么用 jsoup 而不是自己写正则</b>：净化是「必须一次做对」的典型场景，
 * 而畸形嵌套（{@code <scr<script>ipt>}）、属性里的引号、实体编码这些情况下，
 * 自己写的过滤器会「看起来在工作」但留下可执行的残渣。这里用成熟的白名单实现。
 * 白名单本身仍然是显式的：<b>没列出来的标签与属性一律丢弃</b>，
 * 包括 {@code style}、{@code class}（它们能破坏页面样式）和一切 {@code on*} 事件属性。
 */
public final class HtmlSanitizer {

    /**
     * 允许的标签与属性。
     * <p>
     * 取值贴着现有语料的实际用量（{@code <h2>} + {@code <p>}）再放宽一档：
     * 加粗、列表、引用、图、链接是商品描述里真正会用到的东西。
     * 没有 {@code script / iframe / object / style / form}——它们要么能执行脚本，
     * 要么能加载第三方内容，而商品描述不需要这些能力。
     * <p>
     * 链接与图片只放 {@code http/https}：{@code javascript:} 协议是
     * {@code <a href>} 上最常见的注入写法，靠协议白名单直接挡掉。
     */
    private static final Safelist ALLOWED = new Safelist()
            .addTags("h2", "h3", "h4", "p", "br", "hr",
                    "strong", "b", "em", "i", "u", "s",
                    "ul", "ol", "li", "blockquote",
                    "table", "thead", "tbody", "tr", "th", "td",
                    "img", "a")
            .addAttributes("a", "href", "title")
            .addAttributes("img", "src", "alt", "title")
            .addProtocols("a", "href", "http", "https")
            .addProtocols("img", "src", "http", "https");

    /**
     * 关掉 pretty print。
     * <p>
     * 默认设置会把输出重新缩进——在标签之间插入换行与缩进空白。对纯展示的段落无所谓，
     * 但对内联元素是<b>可见的破坏</b>：{@code <p>电话<strong>400</strong>转 1</p>}
     * 会被拆成三行，浏览器渲染出来「400」前后各多一个空格。
     * 净化应当是「去掉危险的东西」，不该顺带改写内容。
     */
    private static final Document.OutputSettings OUTPUT =
            new Document.OutputSettings().prettyPrint(false);

    private HtmlSanitizer() {
    }

    /**
     * 净化。{@code null} 与空白原样返回——「没有正文」与「正文是空字符串」在上层是同一件事，
     * 没必要在这里换成另一个值让调用方再判一次。
     */
    public static String clean(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return rawHtml;
        }
        return Jsoup.clean(rawHtml, "", ALLOWED, OUTPUT);
    }
}
