package yumefusaka.envoymart.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import yumefusaka.envoymart.auth.model.entity.UserEntity;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
