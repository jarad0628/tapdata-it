package io.tapdata.it.verifier;

import io.tapdata.entity.utils.DataMap;
import io.tapdata.pdk.apis.TapConnector;

import java.lang.reflect.Field;

/**
 * 旁路验证器工厂：反射扫描 Connector 实例字段（含继承链），按成员类型自动装配验证器。
 * <ul>
 *   <li>字段类型为 {@code io.tapdata.common.JdbcContext} 子类 → {@link JdbcVerifier}</li>
 *   <li>字段类型为 {@code com.mongodb.client.MongoClient} → {@link MongoVerifier}</li>
 *   <li>均无 → 返回 {@code null}（由子类覆写 createVerifier 提供专属实现）</li>
 * </ul>
 * 全程纯反射字符串类名，tapdata-it 不依赖 sql-core / mongodb-driver。
 */
public final class VerifierFactory {

    private static final String JDBC_CONTEXT = "io.tapdata.common.JdbcContext";
    private static final String MONGO_CLIENT = "com.mongodb.client.MongoClient";

    private VerifierFactory() {
    }

    /**
     * 扫描 connector 实例所有字段，命中已知数据源成员则返回对应验证器。
     *
     * @param connector 已 init 的 connector 实例（成员已完成初始化）
     * @param config    连接配置（MongoDB 需要 database 名）
     */
    public static ConnectorVerifier create(TapConnector connector, DataMap config) {
        if (connector == null) {
            return null;
        }
        for (Class<?> c = connector.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                Object value = fieldValue(connector, field);
                if (value == null) {
                    continue;
                }
                if (isInstance(value, JDBC_CONTEXT)) {
                    return new JdbcVerifier(value);
                }
                if (isInstance(value, MONGO_CLIENT)) {
                    String database = config == null ? null : config.getString("database");
                    if (database == null && config != null) {
                        database = config.getString("db");
                    }
                    return database == null ? null : new MongoVerifier(value, database);
                }
            }
        }
        return null;
    }

    private static Object fieldValue(TapConnector connector, Field field) {
        try {
            field.setAccessible(true);
            return field.get(connector);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static boolean isInstance(Object value, String targetClass) {
        try {
            return Class.forName(targetClass).isInstance(value);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
