package yumefusaka.envoymart.promotionservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import yumefusaka.envoymart.promotionservice.entity.OperationReceiptEntity;

@Mapper
public interface OperationReceiptMapper extends BaseMapper<OperationReceiptEntity> {
    @Select("select * from promotion_operation_receipt where user_id=#{userId} and operation_id=#{operationId} for update")
    OperationReceiptEntity lockByOperation(@Param("userId") String userId, @Param("operationId") String operationId);
    @Insert("insert into promotion_operation_receipt(user_id,operation_id,tool_name,parameters_hash,global_xid,decision_mark,hash_version,receipt_version,created_at) values(#{userId},#{operationId},#{toolName},#{parametersHash},#{globalXid},#{decisionMark},1,1,current_timestamp)")
    int claim(OperationReceiptEntity entity);
    @Update("update promotion_operation_receipt set response_json=#{responseJson,typeHandler=yumefusaka.envoymart.promotionservice.mapper.ReceiptJsonTypeHandler},completed_at=current_timestamp where user_id=#{userId} and operation_id=#{operationId} and response_json is null")
    int complete(@Param("userId") String userId, @Param("operationId") String operationId, @Param("responseJson") String responseJson);
}
