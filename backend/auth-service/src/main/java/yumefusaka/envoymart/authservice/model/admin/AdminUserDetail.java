package yumefusaka.envoymart.authservice.model.admin;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 用户详情：基本信息 + 地址簿条数。
 * <p>
 * 地址簿只给条数、不给内容。<b>看一个人有几个收货地址</b>是客服判断「这个账号像不像
 * 正常用户」的信号之一，但地址明细是收货人姓名与门牌号——那属于用户隐私，
 * 管理端没有查看的业务理由，运营要看的是「有没有填过」。
 */
@Data
@AllArgsConstructor
public class AdminUserDetail {

    private AdminUserSummary user;
    /** 该用户的收货地址条数 */
    private long addressCount;
}
