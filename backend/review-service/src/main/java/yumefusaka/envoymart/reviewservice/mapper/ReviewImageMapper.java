package yumefusaka.envoymart.reviewservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import yumefusaka.envoymart.reviewservice.entity.ReviewImageEntity;

@Mapper
public interface ReviewImageMapper extends BaseMapper<ReviewImageEntity> {

    /**
     * 有图评价的条数，用于「有图 (12)」这个筛选项上的角标。
     * <p>
     * 按 {@code review_id} 去重：一条评价最多配九张图，但角标说的是"多少条评价有图"。
     * 原先这里把该商品的全部图片行拉回 Java 再 {@code distinct().count()} ——
     * 九张图撑不出大问题，但那是纯展示用的一个角标，不值得为它搬一次数据。
     */
    @Select("select count(distinct i.review_id) from review_image i "
            + "join review r on r.id = i.review_id "
            + "where r.spu_id = #{spuId} and r.status = 'PUBLISHED'")
    Integer countWithImage(@Param("spuId") Long spuId);
}
