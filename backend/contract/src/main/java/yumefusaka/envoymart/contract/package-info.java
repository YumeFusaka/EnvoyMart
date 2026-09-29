/**
 * 服务间契约 —— <b>跨服务传输的 DTO 在这里只定义一次</b>。
 * <p>
 * <h2>为什么要有这个模块</h2>
 * 服务间用 Feign 调用，两边各自定义 DTO 时，<b>字段名对齐完全靠人记</b>：
 * 编译期没有任何提示，改错了只会安静地拿到 null。本项目实际踩过三次：
 * <ul>
 *   <li>AI 服务的 {@code ProductResponse.price} 与商品服务的 {@code ProductSummary.minPrice}
 *       对不上——工具输出永远是「商品名 (null 元)」，而模型照样把它当事实说给用户；</li>
 *   <li>AI 服务的 {@code OrderResponse.recipientName} 与订单服务的 {@code receiverName}
 *       对不上——收件人永远是空；</li>
 *   <li>评价服务的订单行写的是 {@code productId}，订单服务早已改名 {@code spuId}——
 *       结果是<b>评价功能 100% 不可用</b>，且不报任何错。</li>
 * </ul>
 * 三处的共同点：<b>症状离根因很远</b>。空指针、空展示、功能不可用，都不指向「字段名不一致」。
 * <p>
 * <h2>放什么，不放什么</h2>
 * <ul>
 *   <li><b>放</b>：真实跨进程传输的类型。判据是「有没有一个服务把这个类型发给另一个服务」。</li>
 *   <li><b>不放</b>：只在单个服务内部使用的 DTO（购物车项、售后单、支付单……它们各自演进，
 *       没有对齐义务）；也不放领域实体与数据库映射。</li>
 *   <li><b>不放</b>：调用方刻意收窄的视图。{@code payment-service} 与 {@code review-service}
 *       各自只声明订单的一小部分字段，那是<b>有意的防腐层</b>——避免下游悄悄依赖上游的全部内部状态。
 *       但窄视图与完整契约之间靠字段名对齐，因此由 {@code ContractParityTest} 用序列化后的
 *       字段名做一致性校验，把「靠人记」变成「编译不过或测试不过」。</li>
 * </ul>
 * <p>
 * <h2>边界</h2>
 * 本模块是<b>纯 POJO</b>：不依赖 Spring、不依赖 MyBatis、不含任何行为逻辑。
 * 一旦开始往里塞工具方法，它就会变成事实上的「共享业务层」，被所有服务反向依赖而无法演进。
 * 唯一的例外是 {@code jakarta.validation} 注解——请求体的约束规则本身也是契约的一部分，
 * 调用方与被调方应当看到同一份。
 * <p>
 * <h2>每个类都必须带 {@code @NoArgsConstructor} 与 {@code @AllArgsConstructor}</h2>
 * {@code @Builder} 会让 Lombok 生成一个全参构造器，<b>从而消掉隐式的无参构造器</b>，
 * 于是 Jackson 找不到任何可用的 creator，反序列化直接抛
 * {@code Type definition error: [simple type, class ...]}。
 * <p>
 * 它<b>只在真正跨进程接收时才炸</b>——生产者侧序列化用的是 getter，一切正常；
 * 单元测试用 {@code builder()} 造对象，也一切正常。所以「本地全过」与「Feign 一调就废」
 * 能同时成立。本模块为此付过一次代价：{@code ProductSummary} 少了这对注解，
 * 结果 AI 的商品工具每次调用都反序列化失败，而它 catch 住异常返回了一句
 * 「没有找到相关商品」——<b>看起来像搜索没命中，实际是这个类型根本没被解析</b>。
 * 回归测试见 {@code ContractJsonRoundTripTest}，它逐个字段构造再往返一遍，
 * 嵌套的契约类一起覆盖。
 */
package yumefusaka.envoymart.contract;
