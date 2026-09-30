package yumefusaka.envoymart.productservice.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 净化器的行为契约。
 * <p>
 * 这些用例是<b>安全断言</b>，不是格式断言：它们钉住的是「这段输入不可能在浏览器里执行」，
 * 而不是「输出长这样」。所以断言写成「不含脚本/事件处理器/javascript:」而不是逐字符比对——
 * 前者在换实现（比如换成 OWASP sanitizer）后仍然成立，后者会变成一份需要跟着改的噪音。
 */
class HtmlSanitizerTest {

    @Test
    void 正常正文应当原样保留() {
        String html = "<h2>产品说明</h2><p>维生素 D3 有助于促进钙的吸收。</p>";

        assertThat(HtmlSanitizer.clean(html)).isEqualTo(html);
    }

    @Test
    void script标签应被剥掉() {
        String cleaned = HtmlSanitizer.clean("<p>正常</p><script>alert(1)</script>");

        assertThat(cleaned).contains("正常").doesNotContain("script").doesNotContain("alert");
    }

    @Test
    void 畸形嵌套不能漏出可执行内容() {
        // 去掉第一个 <script> 后两个碎片拼起来正好是完整标签——只做一次替换的过滤器会在这里漏
        String cleaned = HtmlSanitizer.clean("<scr<script>ipt>alert(1)</scr</script>ipt>");

        // 断言的是「不可执行」，不是「alert 这个词消失」：残留的 alert(1) 是一段**被转义的文本**，
        // 浏览器把它当字面量显示，不执行。真正要钉死的是尖括号没有以标签形式活下来
        assertThat(cleaned).doesNotContain("<").doesNotContain(">");
        assertThat(cleaned).contains("&gt;");
    }

    @Test
    void 事件处理属性应被剥掉() {
        String cleaned = HtmlSanitizer.clean("<p onclick=\"alert(1)\" onmouseover=\"alert(2)\">文字</p>");

        assertThat(cleaned).contains("文字").doesNotContain("onclick").doesNotContain("onmouseover");
        assertThat(cleaned).doesNotContain("alert");
    }

    @Test
    void javascript协议的链接应被剥掉() {
        // 大小写与前后空白都是真实存在的绕过写法，协议判定必须归一化后再比
        assertThat(HtmlSanitizer.clean("<a href=\"javascript:alert(1)\">点我</a>")).doesNotContain("javascript");
        assertThat(HtmlSanitizer.clean("<a href=\"JaVaScRiPt:alert(1)\">点我</a>")).doesNotContainIgnoringCase("javascript");
        assertThat(HtmlSanitizer.clean("<a href=\" java&#115;cript:alert(1)\">点我</a>")).doesNotContain("alert");
    }

    @Test
    void 正常的外链与图片应当保留() {
        String cleaned = HtmlSanitizer.clean(
                "<p><a href=\"https://example.com/policy\">政策</a><img src=\"https://picsum.photos/seed/x/400\" alt=\"图\"></p>");

        assertThat(cleaned).contains("https://example.com/policy").contains("https://picsum.photos/seed/x/400");
    }

    @Test
    void style与class应被剥掉() {
        // 它们不能执行脚本，但能改掉整页的样子——详情正文不该有覆盖站点样式的权力
        String cleaned = HtmlSanitizer.clean("<p style=\"position:fixed;inset:0\" class=\"x\">文字</p>");

        assertThat(cleaned).contains("文字").doesNotContain("style=").doesNotContain("class=");
    }

    @Test
    void 内联元素不应被重新缩进() {
        // pretty print 会在标签间插入换行，渲染出来"400"前后各多一个空格
        String cleaned = HtmlSanitizer.clean("<p>电话<strong>400</strong>转 1</p>");

        assertThat(cleaned).isEqualTo("<p>电话<strong>400</strong>转 1</p>");
    }

    @Test
    void 空值原样返回() {
        assertThat(HtmlSanitizer.clean(null)).isNull();
        assertThat(HtmlSanitizer.clean("   ")).isEqualTo("   ");
    }
}
