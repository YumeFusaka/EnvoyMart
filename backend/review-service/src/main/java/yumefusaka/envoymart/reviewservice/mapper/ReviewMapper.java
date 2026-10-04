package yumefusaka.envoymart.reviewservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import yumefusaka.envoymart.reviewservice.entity.ReviewEntity;

import java.util.List;
import java.util.Map;

public interface ReviewMapper extends BaseMapper<ReviewEntity> {

    /**
     * 已发布评价的星级分布：每行是 {@code rating} 与 {@code cnt}。
     * <p>
     * <b>一次 group by 拿到全部，平均分与总数都从它推出来。</b>原先统计是
     * {@code selectList} 把该商品的全部评价拉回内存再数——评价多了之后，
     * 每次有人打开商品详情都要把几百行搬进 JVM 只为算两个数。
     * <p>
     * 更要紧的是它让三个数字（均分、总数、分布）出自同一次查询：
     * 分三次查同一批数据，就给了它们在同一个页面上互相对不上的机会。
     */
    @Select("select rating, count(*) as cnt, max(id) as max_id from review "
            + "where spu_id = #{spuId} and status = 'PUBLISHED' group by rating")
    List<Map<String, Object>> ratingDistribution(@Param("spuId") Long spuId);

    /**
     * 有已发布评价的商品 id 列表。
     * <p>
     * 用于聚合重建：种子数据与直接写库的脚本不会经过 {@code POST /reviews}，
     * 也就不会发事件，商品侧的评分只能靠一次全量对账追上。
     */
    @Select("select distinct spu_id from review where status = 'PUBLISHED'")
    List<Long> selectPublishedSpuIds();
}
