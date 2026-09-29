package yumefusaka.envoymart.reviewservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import yumefusaka.envoymart.reviewservice.entity.ReviewEntity;
import yumefusaka.envoymart.reviewservice.entity.ReviewImageEntity;

import java.util.List;

@Mapper
public interface ReviewImageMapper extends BaseMapper<ReviewImageEntity> {

    /** 按商品批量取评价图片，避免逐条评价查一次（列表页要展示每条评价的配图） */
    @Select("<script>"
            + "select i.* from review_image i "
            + "join review r on r.id = i.review_id "
            + "where r.spu_id = #{spuId} and r.status = 'PUBLISHED' "
            + "order by i.review_id desc, i.sort"
            + "</script>")
    List<ReviewImageEntity> selectBySpuId(@Param("spuId") Long spuId);

    /** 统计某商品的评分分布与平均分，用于评分聚合 */
    @Select("select coalesce(avg(rating), 0) from review "
            + "where spu_id = #{spuId} and status = 'PUBLISHED'")
    Double averageRating(@Param("spuId") Long spuId);

    @Select("select count(*) from review where spu_id = #{spuId} and status = 'PUBLISHED'")
    Integer countPublished(@Param("spuId") Long spuId);

    /** 供聚合服务复用：一次取某商品的全部已发布评价 */
    @Select("select * from review where spu_id = #{spuId} and status = 'PUBLISHED'")
    List<ReviewEntity> selectPublishedBySpuId(@Param("spuId") Long spuId);
}
