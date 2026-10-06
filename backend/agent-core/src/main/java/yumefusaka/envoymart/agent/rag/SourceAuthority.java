package yumefusaka.envoymart.agent.rag;

import java.util.List;

/**
 * 文档来源的权威度 —— 冲突时「该信哪一份」的第一判据。
 * <p>
 * <b>要解决的问题：`source` 字段一直在，却从没被任何一处用过。</b>检索到的证据条目
 * 上写着「来源：manual」「来源：regulation」，而冲突判定只看版本号——
 * 于是「厂商说明书说可以」与「监管规范说不行」在冲突时权重相同，
 * 只能报一句「需人工确认」把判断推回给用户。这是把系统该做的事省掉了。
 * <p>
 * <b>权威度是排序，不是对错。</b>监管规范高于厂商说明书，只说明在两者说法不一致时
 * 应<b>以规范为准并如实注明依据</b>，不说明厂商那份一定是错的——它可能只是适用范围更窄、
 * 或者更新得更晚。所以这里的产出只能用于「谁盖过谁」和「写明凭哪个字段裁的」，
 * 不能用来宣布某一方是错误信息。
 * <p>
 * <b>为什么是固定枚举而不是可配置。</b>这张表的每一档都对应一种<b>文件的法律性质</b>，
 * 它不是业务参数，而是合规常识。做成配置项只会让人以为改个 YAML 就能让厂商说明
 * 盖过监管规范——那不是配置，那是事故。新增来源类型时才回来加一档。
 * <p>
 * <b>同档内部不排序。</b>同为「厂商材料」的 spec 与 manual 谁更可信，取决于具体内容，
 * 不取决于标签。返回同一个分数就是如实表达这件事，下游会退到版本号那一层继续判。
 */
public enum SourceAuthority {

    /** 监管规范、国标、法规 —— 合规底线，冲突时压过其余全部来源 */
    REGULATION("regulation", 100, "监管规范"),

    /** 平台政策、规则 —— 平台自己定的口径，对外承诺的效力高于产品材料 */
    POLICY("policy", 80, "平台政策"),

    /** 技术规格书 */
    SPEC("spec", 60, "技术规格"),

    /** 厂商说明书 —— 最常见的一类，也是「产品自己说」的那一档 */
    MANUAL("manual", 40, "厂商说明书"),

    /** 指南、科普类材料 —— 参考性质 */
    GUIDE("guide", 20, "参考资料");

    private final String code;
    private final int weight;
    private final String label;

    SourceAuthority(String code, int weight, String label) {
        this.code = code;
        this.weight = weight;
        this.label = label;
    }

    /** 与 {@code KnowledgeDocumentServiceImpl.SOURCES} 里的取值一一对应 */
    public String code() {
        return code;
    }

    public int weight() {
        return weight;
    }

    /** 给模型与用户看的中文名。<b>提示词里写中文名而不是 {@code regulation} 这种码</b>，
     * 是因为模型要据此写一句给用户看的裁决说明，直接抄码会输出一句谁都看不懂的英文缩写 */
    public String label() {
        return label;
    }

    /**
     * 解析不明的来源一律当最低档，而不是默认 {@link #MANUAL}。
     * <p>
     * 默认成中间档会让一个笔误的来源（{@code mannual}）看起来和正牌说明书一样可信，
     * 而这种错不会报错、只会在某次冲突里悄悄影响裁决。给最低档则方向是保守的：
     * 它不会被用来盖过任何人，只会更容易被盖过——错了也只是裁决偏保守。
     */
    public static SourceAuthority of(String source) {
        if (source == null) {
            return GUIDE;
        }
        String code = source.strip().toLowerCase(java.util.Locale.ROOT);
        for (SourceAuthority authority : values()) {
            if (authority.code.equals(code)) {
                return authority;
            }
        }
        return GUIDE;
    }

    /**
     * 两方权威度是否<b>足以分出高下</b>。
     * <p>
     * <b>必须留一个「分不出」的档，不能只要不相等就判高下。</b>同为厂商材料的两份文档
     * 权重相同，此时结论取决于内容与版本，不取决于标签；若这里草率地按枚举顺序定胜负，
     * 系统就会在一个它其实没有依据的问题上给出斩钉截铁的答案——比说「定不了」有害。
     *
     * @return 明显高出的那一方；分不出时为空
     */
    public static java.util.Optional<SourceAuthority> dominates(String left, String right) {
        SourceAuthority a = of(left);
        SourceAuthority b = of(right);
        return a.weight > b.weight ? java.util.Optional.of(a) : java.util.Optional.empty();
    }

    /** 全部来源的中文说明，拼进提示词让模型知道这个判据存在 */
    public static String vocab() {
        List<String> parts = java.util.Arrays.stream(values())
                .map(a -> a.label + "(" + a.code + ")")
                .toList();
        return String.join(" > ", parts);
    }
}
