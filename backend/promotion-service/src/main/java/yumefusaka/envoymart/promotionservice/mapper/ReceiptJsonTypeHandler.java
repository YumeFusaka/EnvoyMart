package yumefusaka.envoymart.promotionservice.mapper;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.sql.*;
import java.nio.charset.StandardCharsets;

/** H2 JSON 接受 UTF-8 字节才按对象保存；字符串绑定会额外包成 JSON 字符串。 */
public class ReceiptJsonTypeHandler extends BaseTypeHandler<String> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, String value, JdbcType jdbcType) throws SQLException {
        if ("H2".equals(statement.getConnection().getMetaData().getDatabaseProductName())) {
            statement.setBytes(index, value.getBytes(StandardCharsets.UTF_8));
        } else statement.setString(index, value);
    }
    @Override public String getNullableResult(ResultSet rs, String name) throws SQLException { return rs.getString(name); }
    @Override public String getNullableResult(ResultSet rs, int index) throws SQLException { return rs.getString(index); }
    @Override public String getNullableResult(CallableStatement statement, int index) throws SQLException { return statement.getString(index); }
}
