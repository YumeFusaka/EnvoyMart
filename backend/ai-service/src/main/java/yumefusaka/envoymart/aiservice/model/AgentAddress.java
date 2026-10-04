package yumefusaka.envoymart.aiservice.model;

import lombok.Data;

/**
 * 收货地址 —— Agent 侧只读视图。
 * <p>
 * <b>只读、且只在「用户要下单但没给地址」时使用。</b>地址是用户自己的数据，
 * Agent 读它的唯一正当用途是：把「你默认寄到 XX 地址，确认吗」这句话说出口。
 * 本类型刻意不含任何写字段——Agent 不该有改地址的能力，那是账户设置的活。
 * <p>
 * 与 auth-service 的 {@code UserAddressEntity} 是两份类型，理由同
 * {@link AgentCheckoutRequest}：让 Agent 的工具输出跟着另一个服务的实体演进，
 * 等于把别人的重构变成提示词的变化。
 */
@Data
public class AgentAddress {
    private Long id;
    private String receiverName;
    private String receiverPhone;
    private String province;
    private String city;
    private String district;
    private String detail;
    /** 0 否 / 1 是 */
    private Integer isDefault;
    private String tag;
}
