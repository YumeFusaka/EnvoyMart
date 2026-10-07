package yumefusaka.envoymart.knowledgeservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 启动期迁移告警。
 * <p>
 * <b>它存在的理由：迁移脚本的失败方式不能是「把服务拖死」。</b>加唯一约束撞上历史脏数据时，
 * 数据库抛 1062、整个应用起不来，而启动期日志框架还没就绪、现场很难查。所以
 * {@code schema-mysql.sql} 改成「先数重复行，有重复就跳过该约束并在这里留一条」，
 * 让服务照常起来、管理员按这张表排查。
 * <p>
 * <b>它是只读的。</b>没有写入接口 —— 写的人只能是迁移脚本。管理台提供查询，
 * 是为了让「本可以拦住启动的问题」有一个可见的落点，而不是又一条只在日志里的计数。
 */
@Data
@TableName("migration_warning")
public class MigrationWarningEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 产出这条告警的脚本，如 schema-mysql.sql */
    private String script;
    /** 目标对象，如 shop_order.uk_order_request */
    private String target;
    /** 人话说明：有几组重复值、下一步该怎么办 */
    private String detail;
    private LocalDateTime at;
}
