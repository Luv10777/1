package com.wuyao.growth.common.tenant;

import lombok.RequiredArgsConstructor;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * 契约 5 的执行者：每次从连接池拿连接，就把租户号写进这个连接的会话变量，
 * PostgreSQL 的行级安全策略读的就是它。
 *
 * 意义在于：即使某个 Repository 方法忘了写 tenant_id 条件，
 * 数据库也不会把别家的数据吐出来。隔离下沉到了库，而不是靠人记得。
 * 连接还池前必须 RESET，否则会串租户。
 */
@Component
@RequiredArgsConstructor
public class TenantConnectionProvider
        implements MultiTenantConnectionProvider<String>, HibernatePropertiesCustomizer {

    private final transient DataSource dataSource;

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = getAnyConnection();
        try (Statement st = connection.createStatement()) {
            // set_config 是函数调用，参数走字面量拼接前已被限制为数字，避免 SET 无法参数化的问题
            st.execute("SELECT set_config('app.tenant_id', '" + sanitize(tenantIdentifier) + "', false)");
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '', false)");
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return MultiTenantConnectionProvider.class.equals(unwrapType)
                || TenantConnectionProvider.class.equals(unwrapType);
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        return unwrapType.cast(this);
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, this);
    }

    /** 租户号只可能是数字。非数字一律降级成哨兵值，避免任何注入面。 */
    private String sanitize(String tenantIdentifier) {
        if (tenantIdentifier == null) {
            return TenantContext.SYSTEM;
        }
        for (int i = 0; i < tenantIdentifier.length(); i++) {
            if (!Character.isDigit(tenantIdentifier.charAt(i))) {
                return TenantContext.SYSTEM;
            }
        }
        return tenantIdentifier.isEmpty() ? TenantContext.SYSTEM : tenantIdentifier;
    }
}
