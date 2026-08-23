package com.ecat.integration.EnvQualityControlManagerIntegration.support;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * PG timestamptz(带时区)列 ↔ {@link Instant} 映射 TypeHandler,经 OffsetDateTime 中转。
 *
 * <p><b>应用场景</b>:QCM 数据层换代(FR-04-03)时间列一律 timestamptz、实体统一 {@link Instant}——
 * PG JDBC 42.x+ 拒 timestamptz→java.time 隐式转换,默认 handler 直接抛。实现沿用来源模块
 * env-air-station-manager 的同名 handler(ADM 同模式,QCM 自建不跨模块 import)。
 * 注册:per-mapper {@code typeHandler=} 局部注册(非全局,避免动 ruoyi core 的映射)。</p>
 *
 * @author coffee
 */
@MappedTypes(Instant.class)
public class TimestamptzInstantTypeHandler extends BaseTypeHandler<Instant> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Instant parameter, JdbcType jdbcType)
            throws SQLException {
        // Instant → OffsetDateTime(UTC) → timestamptz;Instant 本身 UTC,offset 恒为 UTC
        ps.setObject(i, OffsetDateTime.ofInstant(parameter, ZoneOffset.UTC));
    }

    @Override
    public Instant getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toInstant(rs.getObject(columnName));
    }

    @Override
    public Instant getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toInstant(rs.getObject(columnIndex));
    }

    @Override
    public Instant getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toInstant(cs.getObject(columnIndex));
    }

    /** 驱动原生时间对象统一转 {@link Instant};null 返 null;未知类型严格模式明确告知(不静默返 null 掩盖)。 */
    private static Instant toInstant(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Instant) {
            return (Instant) obj;
        }
        if (obj instanceof OffsetDateTime) {
            return ((OffsetDateTime) obj).toInstant();
        }
        if (obj instanceof Timestamp) {
            return ((Timestamp) obj).toInstant();
        }
        throw new IllegalStateException(
                "[诊断调试] TimestamptzInstantTypeHandler 遇未知时间类型:" + obj.getClass().getName());
    }
}
