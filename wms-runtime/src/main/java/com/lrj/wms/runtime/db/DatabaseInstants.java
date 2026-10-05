package com.lrj.wms.runtime.db;

import java.time.Instant;

/** 瞬时字段在 JDBC 边界已经按数据库记录还原；无时区墙钟不能在业务层猜测。 */
public final class DatabaseInstants {
    private DatabaseInstants() {}

    /** 已还原的瞬时类型才能进入业务规则；不能凭 JVM 默认时区解释数据库墙钟。 */
    public static Instant require(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        throw new IllegalArgumentException("瞬时字段缺少明确时区，请使用配置过时间语义的数据源");
    }

    /**
     * 数据库投影中的可空效期在持久化边界转换，领域规则只接收 Instant。
     * 保留旧投影对 Date 的支持和墙钟拒绝行为，不能趁分层调整扩大接受类型。
     */
    public static Instant instantOf(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        if (value instanceof java.time.LocalDateTime localDateTime) return require(localDateTime);
        throw new IllegalArgumentException("无法识别的效期类型：" + value.getClass().getName());
    }

    /** MyBatis 的 Map 自动映射会绕过 getObject 的驱动开关；对瞬时SQL类型明确读取Timestamp。 */
    public static void configure(org.apache.ibatis.session.Configuration configuration) {
        configuration
                .getTypeHandlerRegistry()
                .register(
                        Object.class,
                        org.apache.ibatis.type.JdbcType.TIMESTAMP,
                        new InstantMapHandler());
    }

    private static final class InstantMapHandler
            extends org.apache.ibatis.type.BaseTypeHandler<Object> {
        /** 写入前沿用瞬时字段校验，不能凭默认时区猜测数据库时间。 */
        @Override
        public void setNonNullParameter(
                java.sql.PreparedStatement statement,
                int index,
                Object parameter,
                org.apache.ibatis.type.JdbcType type)
                throws java.sql.SQLException {
            statement.setTimestamp(index, java.sql.Timestamp.from(require(parameter)));
        }

        /** 在 JDBC 读取边界保留 Timestamp 的瞬时语义，供上层统一转换。 */
        @Override
        public Object getNullableResult(java.sql.ResultSet result, String column)
                throws java.sql.SQLException {
            return result.getTimestamp(column);
        }

        /** 在 JDBC 读取边界保留 Timestamp 的瞬时语义，供上层统一转换。 */
        @Override
        public Object getNullableResult(java.sql.ResultSet result, int column)
                throws java.sql.SQLException {
            return result.getTimestamp(column);
        }

        /** 在 JDBC 读取边界保留 Timestamp 的瞬时语义，供上层统一转换。 */
        @Override
        public Object getNullableResult(java.sql.CallableStatement result, int column)
                throws java.sql.SQLException {
            return result.getTimestamp(column);
        }
    }
}
