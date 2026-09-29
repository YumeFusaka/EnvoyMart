package yumefusaka.envoymart.common.util;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 时间取值 —— 全项目统一从这里拿「现在」。
 * <p>
 * 统一截到秒，因为 MySQL 的 {@code datetime} 默认不保存小数秒。不截的话，
 * 同一个字段会在同一秒里出现两个值：<b>刚插入后返回给调用方的对象</b>带着纳秒
 * （那是内存里 {@code LocalDateTime.now()} 的原值），而<b>同一行重新查出来</b>
 * 已经是整秒。前端拿这两个值做比较、排序或格式化时会莫名其妙。
 * <p>
 * 不做成 MyBatis-Plus 的 {@code MetaObjectHandler} 自动填充：那需要给 common 引入
 * 条件装配的额外复杂度，而 UPDATE 场景下「字段已有旧值就不覆盖」的语义很容易踩坑。
 * 一个显式的方法调用更不容易出错。
 */
public final class Times {

    private Times() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
