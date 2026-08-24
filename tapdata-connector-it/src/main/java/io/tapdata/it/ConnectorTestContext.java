package io.tapdata.it;

import io.tapdata.entity.codec.TapCodecsRegistry;
import io.tapdata.entity.logger.Log;
import io.tapdata.it.verifier.ConnectorVerifier;
import io.tapdata.entity.utils.DataMap;
import io.tapdata.pdk.apis.context.TapConnectionContext;
import io.tapdata.pdk.apis.context.TapConnectorContext;
import io.tapdata.pdk.apis.TapConnector;
import io.tapdata.pdk.apis.functions.ConnectorFunctions;

/**
 * 连接器测试上下文：承载被测 Connector 全部运行时要素（builder 模式）。
 * <p>
 * 由 Connector 侧在 {@link ConnectorIT#createContext()} 中构建：
 * <pre>{@code
 * ConnectorTestContext.builder()
 *     .connector(connector)
 *     .nodeContext(nodeContext)
 *     .connectorFunctions(functions)
 *     .codecRegistry(codecRegistry)
 *     .config(config)
 *     .build();
 * }</pre>
 * 基类会在 {@code @BeforeEach} 中补齐 nodeContext 的 stateMap/connectorCapabilities/tableMap，
 * 并驱动生命周期 init → onStart。
 */
public class ConnectorTestContext {

    /** 被测 Connector 实例（生命周期 init/onStart 由基类负责） */
    private final TapConnector connector;
    /** Connector 任务上下文（connector 级函数调用） */
    private final TapConnectorContext nodeContext;
    /** 连接上下文（discoverSchema/connectionTest/getTableNames 等连接级调用） */
    private final TapConnectionContext connectionContext;
    /** 能力注册表（registerCapabilities 产物，能力检测与调用入口） */
    private final ConnectorFunctions connectorFunctions;
    /** 编解码注册表（写数据时 TapValue → 原生值转换） */
    private final TapCodecsRegistry codecRegistry;
    /** 连接配置 */
    private final DataMap config;
    /** 日志 */
    private final Log log;
    /** 旁路验证器（setUp 中由基类自动装配，子类可覆写 createVerifier 提供专属实现） */
    private ConnectorVerifier verifier;

    // ===================== 数据库特性支持开关 =====================
    // 默认值均保持关系型数据库（MySQL 等）的验证语义不变，
    // NoSQL/schema-free 类 Connector（如 MongoDB）在子类 createContext() 中按需覆写。

    /** createTableV2 是否能报告表已存在（MongoDB 幂等建集合固定返回 tableExists=false） */
    private final boolean createTableReportsTableExists;
    /** schema 发现是否依赖采样数据（schema-free 库空表无法发现字段，需先写入数据再 discoverSchema） */
    private final boolean schemaDiscoveryRequiresSampleData;
    /** schema 断言是否允许额外字段（MongoDB 隐式 _id 字段） */
    private final boolean schemaAllowsExtraFields;
    /** schema 断言是否要求主键标记严格一致（MongoDB 主键为自动生成的 _id，业务字段不被标记为主键） */
    private final boolean schemaPrimaryKeyStrict;
    /** executeCommand 是否支持 SQL 风格 ping 命令（MongoDB 仅支持 execute/executeQuery/count/aggregate） */
    private final boolean executeCommandSupportsPing;
    /** queryFieldMinMaxValue 是否要求目标字段在分区索引中（MongoDB 索引驱动 min/max，无索引字段直接抛错） */
    private final boolean fieldMinMaxRequiresPartitionIndex;

    private ConnectorTestContext(Builder builder) {
        this.connector = builder.connector;
        this.nodeContext = builder.nodeContext;
        this.connectionContext = builder.connectionContext != null ? builder.connectionContext : builder.nodeContext;
        this.connectorFunctions = builder.connectorFunctions;
        this.codecRegistry = builder.codecRegistry;
        this.config = builder.config;
        this.log = builder.log;
        this.verifier = builder.verifier;
        this.createTableReportsTableExists = builder.createTableReportsTableExists;
        this.schemaDiscoveryRequiresSampleData = builder.schemaDiscoveryRequiresSampleData;
        this.schemaAllowsExtraFields = builder.schemaAllowsExtraFields;
        this.schemaPrimaryKeyStrict = builder.schemaPrimaryKeyStrict;
        this.executeCommandSupportsPing = builder.executeCommandSupportsPing;
        this.fieldMinMaxRequiresPartitionIndex = builder.fieldMinMaxRequiresPartitionIndex;
    }

    public static Builder builder() {
        return new Builder();
    }

    public TapConnector getConnector() {
        return connector;
    }

    public TapConnectorContext getNodeContext() {
        return nodeContext;
    }

    public TapConnectionContext getConnectionContext() {
        return connectionContext;
    }

    /** 连接级上下文：nodeContext 是 TapConnectorContext 时可直接用于连接级调用 */
    public TapConnectionContext connectionContextOrNode() {
        return connectionContext != null ? connectionContext : nodeContext;
    }

    public ConnectorFunctions getConnectorFunctions() {
        return connectorFunctions;
    }

    public TapCodecsRegistry getCodecRegistry() {
        return codecRegistry;
    }

    public DataMap getConfig() {
        return config;
    }

    public Log getLog() {
        return log;
    }

    /** 旁路验证器：直连对端数据源（不经过 connector read 能力），数据类用例的验证入口 */
    public ConnectorVerifier getVerifier() {
        return verifier;
    }

    public void setVerifier(ConnectorVerifier verifier) {
        this.verifier = verifier;
    }

    // ===================== 特性开关 getter =====================

    /** createTableV2 是否能报告表已存在（false 时“重复建表报告已存在”用例跳过） */
    public boolean isCreateTableReportsTableExists() {
        return createTableReportsTableExists;
    }

    /** schema 发现是否依赖采样数据（true 时“发现 schema”用例先写入数据再 discoverSchema） */
    public boolean isSchemaDiscoveryRequiresSampleData() {
        return schemaDiscoveryRequiresSampleData;
    }

    /** schema 断言是否允许额外字段（true 时仅断言 spec 字段存在，不要求字段数严格相等） */
    public boolean isSchemaAllowsExtraFields() {
        return schemaAllowsExtraFields;
    }

    /** schema 断言是否要求主键标记严格一致（false 时仅验证 spec 主键字段存在） */
    public boolean isSchemaPrimaryKeyStrict() {
        return schemaPrimaryKeyStrict;
    }

    /** executeCommand 是否支持 SQL 风格 ping 命令（false 时“executeCommand”用例跳过） */
    public boolean isExecuteCommandSupportsPing() {
        return executeCommandSupportsPing;
    }

    /** queryFieldMinMaxValue 是否要求目标字段在分区索引中（true 时用例先设置含目标字段的分区索引再调用） */
    public boolean isFieldMinMaxRequiresPartitionIndex() {
        return fieldMinMaxRequiresPartitionIndex;
    }

    public static class Builder {
        private TapConnector connector;
        private TapConnectorContext nodeContext;
        private TapConnectionContext connectionContext;
        private ConnectorFunctions connectorFunctions;
        private TapCodecsRegistry codecRegistry;
        private DataMap config;
        private Log log;
        private ConnectorVerifier verifier;
        // 特性开关默认值：与关系型数据库验证语义一致
        private boolean createTableReportsTableExists = true;
        private boolean schemaDiscoveryRequiresSampleData = false;
        private boolean schemaAllowsExtraFields = false;
        private boolean schemaPrimaryKeyStrict = true;
        private boolean executeCommandSupportsPing = true;
        private boolean fieldMinMaxRequiresPartitionIndex = false;

        public Builder connector(TapConnector connector) {
            this.connector = connector;
            return this;
        }

        public Builder nodeContext(TapConnectorContext nodeContext) {
            this.nodeContext = nodeContext;
            return this;
        }

        public Builder connectionContext(TapConnectionContext connectionContext) {
            this.connectionContext = connectionContext;
            return this;
        }

        public Builder connectorFunctions(ConnectorFunctions connectorFunctions) {
            this.connectorFunctions = connectorFunctions;
            return this;
        }

        public Builder codecRegistry(TapCodecsRegistry codecRegistry) {
            this.codecRegistry = codecRegistry;
            return this;
        }

        public Builder config(DataMap config) {
            this.config = config;
            return this;
        }

        public Builder log(Log log) {
            this.log = log;
            return this;
        }

        /** 旁路验证器（可选；不设置时基类在 setUp 中自动装配） */
        public Builder verifier(ConnectorVerifier verifier) {
            this.verifier = verifier;
            return this;
        }

        /** createTableV2 是否能报告表已存在（NoSQL 幂等建表返回 false 时设为 false） */
        public Builder createTableReportsTableExists(boolean createTableReportsTableExists) {
            this.createTableReportsTableExists = createTableReportsTableExists;
            return this;
        }

        /** schema 发现是否依赖采样数据（schema-free 库空表无 schema，设为 true） */
        public Builder schemaDiscoveryRequiresSampleData(boolean schemaDiscoveryRequiresSampleData) {
            this.schemaDiscoveryRequiresSampleData = schemaDiscoveryRequiresSampleData;
            return this;
        }

        /** schema 断言是否允许额外字段（存在隐式主键/自增字段的库设为 true） */
        public Builder schemaAllowsExtraFields(boolean schemaAllowsExtraFields) {
            this.schemaAllowsExtraFields = schemaAllowsExtraFields;
            return this;
        }

        /** schema 断言是否要求主键标记严格一致（主键为库自动生成时设为 false） */
        public Builder schemaPrimaryKeyStrict(boolean schemaPrimaryKeyStrict) {
            this.schemaPrimaryKeyStrict = schemaPrimaryKeyStrict;
            return this;
        }

        /** executeCommand 是否支持 SQL 风格 ping 命令（命令方言不同的库设为 false） */
        public Builder executeCommandSupportsPing(boolean executeCommandSupportsPing) {
            this.executeCommandSupportsPing = executeCommandSupportsPing;
            return this;
        }

        /** queryFieldMinMaxValue 是否要求目标字段在分区索引中（索引驱动 min/max 的库设为 true） */
        public Builder fieldMinMaxRequiresPartitionIndex(boolean fieldMinMaxRequiresPartitionIndex) {
            this.fieldMinMaxRequiresPartitionIndex = fieldMinMaxRequiresPartitionIndex;
            return this;
        }

        public ConnectorTestContext build() {
            if (connector == null) {
                throw new IllegalArgumentException("connector must not be null");
            }
            if (nodeContext == null) {
                throw new IllegalArgumentException("nodeContext must not be null");
            }
            if (connectorFunctions == null) {
                throw new IllegalArgumentException("connectorFunctions must not be null (call connector.registerCapabilities(functions, codecRegistry) first)");
            }
            if (codecRegistry == null) {
                throw new IllegalArgumentException("codecRegistry must not be null (call connector.registerCapabilities(functions, codecRegistry) first)");
            }
            return new ConnectorTestContext(this);
        }
    }
}
