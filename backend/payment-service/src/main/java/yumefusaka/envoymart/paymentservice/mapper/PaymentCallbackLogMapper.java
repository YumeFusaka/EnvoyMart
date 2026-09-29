package yumefusaka.envoymart.paymentservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import yumefusaka.envoymart.paymentservice.entity.PaymentCallbackLogEntity;

@Mapper
public interface PaymentCallbackLogMapper extends BaseMapper<PaymentCallbackLogEntity> {
}
