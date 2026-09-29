package yumefusaka.envoymart.authservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_address")
public class UserAddressEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String receiverName;
    private String receiverPhone;

    /** 省市区拆成三列，而不是一个字符串：按区域统计订单量、匹配偏远地区运费规则都要用到 */
    private String province;
    private String city;
    private String district;
    private String detail;

    /** 0 否 / 1 是。同一用户至多一条为 1，由应用层在同一事务内先清后置保证 */
    private Integer isDefault;
    private String tag;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
