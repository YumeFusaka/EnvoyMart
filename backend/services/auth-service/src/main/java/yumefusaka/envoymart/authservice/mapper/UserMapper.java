package yumefusaka.envoymart.authservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import yumefusaka.envoymart.authservice.entity.UserEntity;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
