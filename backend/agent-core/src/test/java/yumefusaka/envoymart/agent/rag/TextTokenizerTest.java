package yumefusaka.envoymart.agent.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextTokenizerTest {

    @Test
    void 中文按二元组切分() {
        assertThat(TextTokenizer.tokenize("退货")).containsExactly("退货");
        assertThat(TextTokenizer.tokenize("我想退货")).containsExactly("我想", "想退", "退货");
        assertThat(TextTokenizer.tokenize("七天无理由")).containsExactly("七天", "天无", "无理", "理由");
    }

    @Test
    void 英文与数字按边界切分() {
        assertThat(TextTokenizer.tokenize("iPhone 15 Pro")).containsExactly("iphone", "15", "pro");
        assertThat(TextTokenizer.tokenize("hello,world!")).containsExactly("hello", "world");
    }

    @Test
    void 中英文混排各自切分() {
        assertThat(TextTokenizer.tokenize("退款 refund 政策")).containsExactly("退款", "refund", "政策");
    }

    @Test
    void 空白与空串返回空列表() {
        assertThat(TextTokenizer.tokenize(null)).isEmpty();
        assertThat(TextTokenizer.tokenize("")).isEmpty();
        assertThat(TextTokenizer.tokenize("   ，。！")).isEmpty();
    }
}
