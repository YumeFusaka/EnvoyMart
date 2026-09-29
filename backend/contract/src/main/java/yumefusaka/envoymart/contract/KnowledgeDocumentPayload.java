package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识库文档 —— <b>由 knowledge-service 发出，ai-service 消费</b>，用于构建检索索引。
 * <p>
 * 这是知识层的唯一一份文档契约。之所以要它：装载（建库）与消费（建索引）在改造中
 * 拆成了两个服务，而「一篇知识文档长什么样」如果各写一遍，就是本仓库已经出过三次事故的
 * 那个形状——<b>字段名靠人记着对齐，Jackson 对不上时不报错、只给 null</b>。
 * 结果是索引里每篇文档的标题都是 null，引用渲染成「依据：《null》」。
 * <p>
 * <b>切片 id 不在这里定义</b>：它由 {@code agent-core} 的切分器产出，两个服务用的是
 * 同一个实现与同一组参数（{@code StructuralSplitter.standard()}）。引用回跳能成立的全部
 * 前提就是这件事——检索侧与存储侧对同一篇文档切出同一组 chunkId。
 * 任何一边私自改了切分参数，症状都是「检索命中了，但点开引用是 404」，
 * 而两边各自的日志都完全正常。
 */
@Data
@Builder
// 两个构造器都要显式写出来，否则 ai-service 侧 Feign 解码会抛
// "Cannot construct instance ... (no Creators, like default constructor, exist)"。
// `@Builder` 生成的是一个全参构造，Jackson 3 并不会自动把它当成 creator——
// 即便编译时带了 -parameters（本项目的 parent pom 已开）。同一个包里的 ProductDetail
// 早就踩过并留下了这两个注解。这里漏掉时，表现是 ai-service 拒绝启动。
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentPayload {

    /** 文档编号，形如 KB-0005。切片 id 由它派生 */
    private String docNo;
    private String title;
    /** manual / policy / regulation / spec / guide */
    private String source;
    /** nutrition / after_sale / logistics / payment / promotion / food_safety */
    private String scope;
    private String version;
    private List<String> tags;
    /** 正文全文 */
    private String content;
}
