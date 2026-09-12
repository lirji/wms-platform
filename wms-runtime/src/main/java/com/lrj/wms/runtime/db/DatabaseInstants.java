package com.lrj.wms.runtime.db;

import java.time.Instant;

/** 瞬时字段在 JDBC 边界已经按数据库记录还原；无时区墙钟不能在业务层猜测。 */
public final class DatabaseInstants {
    private DatabaseInstants() { }
    public static Instant require(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        throw new IllegalArgumentException("瞬时字段缺少明确时区，请使用配置过时间语义的数据源");
    }
    /** MyBatis 的 Map 自动映射会绕过 getObject 的驱动开关；对瞬时SQL类型明确读取Timestamp。 */
    public static void configure(org.apache.ibatis.session.Configuration configuration) {
        configuration.getTypeHandlerRegistry().register(Object.class, org.apache.ibatis.type.JdbcType.TIMESTAMP, new InstantMapHandler());
    }
    private static final class InstantMapHandler extends org.apache.ibatis.type.BaseTypeHandler<Object> {
        @Override public void setNonNullParameter(java.sql.PreparedStatement statement,int index,Object parameter,org.apache.ibatis.type.JdbcType type) throws java.sql.SQLException {
            statement.setTimestamp(index,java.sql.Timestamp.from(require(parameter)));
        }
        @Override public Object getNullableResult(java.sql.ResultSet result,String column) throws java.sql.SQLException { return result.getTimestamp(column); }
        @Override public Object getNullableResult(java.sql.ResultSet result,int column) throws java.sql.SQLException { return result.getTimestamp(column); }
        @Override public Object getNullableResult(java.sql.CallableStatement result,int column) throws java.sql.SQLException { return result.getTimestamp(column); }
    }

}
