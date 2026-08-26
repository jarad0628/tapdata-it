package io.tapdata.it.verifier;

import io.tapdata.it.schema.TestFieldSpec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RDBMS 旁路验证器：通过 Connector 内部 JdbcContext 的 HikariDataSource 连接池
 * 直连对端数据库执行 COUNT/SELECT，不经过 Connector 任何 read 能力。
 * <p>
 * 反射链路：Connector 实例 → 成员 {@code JdbcContext} 子类 → 私有字段
 * {@code hikariDataSource}（com.zaxxer.hikari.HikariDataSource）→ JDBC 直连。
 * <p>
 * 表/列标识符默认原样使用（测试表名为安全字符 {@code _tap_it_*}）；
 * 需要 Schema 限定或特殊引用（如 DB2 i 的双引号 {@code "SCHEMA"."TABLE"}）时，
 * 子类覆写 {@link #qualifiedTable(String)}。
 */
public class JdbcVerifier implements ConnectorVerifier {

    private final Object jdbcContext;
    private final Object hikariDataSource;

    /**
     * @param jdbcContext Connector 实例中继承 io.tapdata.common.JdbcContext 的成员对象
     */
    public JdbcVerifier(Object jdbcContext) {
        this.jdbcContext = jdbcContext;
        try {
            Field field = findField(jdbcContext.getClass(), "hikariDataSource");
            field.setAccessible(true);
            this.hikariDataSource = field.get(jdbcContext);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot extract hikariDataSource from JdbcContext " + jdbcContext.getClass(), e);
        }
        if (this.hikariDataSource == null) {
            throw new IllegalStateException("hikariDataSource is null in JdbcContext " + jdbcContext.getClass());
        }
    }

    /** 构造时传入的 JdbcContext 成员（供子类包装复用，跨包可访问） */
    public Object jdbcContext() {
        return jdbcContext;
    }

    /** 当前实现是否对标识符做引用改写（子类覆写 qualifiedTable/qualifiedColumn 时用于调试） */
    public String identifierMode() {
        return "plain";
    }

    @Override
    public long count(String table) throws Exception {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable(table);
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Override
    public List<Map<String, Object>> selectByPk(String table, String pkName, List<Object> pkValues) throws Exception {
        if (pkValues == null || pkValues.isEmpty()) {
            return new ArrayList<>();
        }
        String placeholders = String.join(",", Collections.nCopies(pkValues.size(), "?"));
        String sql = "SELECT * FROM " + qualifiedTable(table) + " WHERE " + qualifiedColumn(pkName) + " IN (" + placeholders + ")";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < pkValues.size(); i++) {
                ps.setObject(i + 1, pkValues.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** 反射获取连接池直连 Connection（不经过 Connector） */
    protected Connection connection() throws Exception {
        Method getConnection = hikariDataSource.getClass().getMethod("getConnection");
        return (Connection) getConnection.invoke(hikariDataSource);
    }

    @Override
    public void createTable(String table, List<TestFieldSpec> fields) throws Exception {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(qualifiedTable(table)).append(" (");
        List<String> pkColumns = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            TestFieldSpec field = fields.get(i);
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(qualifiedColumn(field.getName())).append(' ').append(field.getDataType());
            if (field.isPrimaryKey()) {
                sql.append(" NOT NULL");
                pkColumns.add(qualifiedColumn(field.getName()));
            }
        }
        if (!pkColumns.isEmpty()) {
            sql.append(", PRIMARY KEY (").append(String.join(",", pkColumns)).append(')');
        }
        sql.append(')');
        final String createSql = sql.toString();
        withAutoCommit(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(createSql);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void insert(String table, List<Map<String, Object>> rows) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        // 首行列序作为插入列序（generateRows 按字段声明顺序构建 LinkedHashMap）
        List<String> columns = new ArrayList<>(rows.get(0).keySet());
        String columnList = columns.stream().map(this::qualifiedColumn).collect(Collectors.joining(", "));
        String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + qualifiedTable(table) + " (" + columnList + ") VALUES (" + placeholders + ")";
        final String insertSql = sql;
        withAutoCommit(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (Map<String, Object> row : rows) {
                    for (int i = 0; i < columns.size(); i++) {
                        ps.setObject(i + 1, jdbcValue(row.get(columns.get(i))));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 旁路写操作强制自动提交：引擎连接池常配置 autoCommit=false（事务控制），
     * 不提交时连接归还池会回滚，导致旁路准备的数据对后续 count/select 不可见。
     * 执行后恢复连接原有 autoCommit 状态。
     */
    private void withAutoCommit(SqlConsumer action) throws Exception {
        try (Connection conn = connection()) {
            boolean original = conn.getAutoCommit();
            if (!original) {
                conn.setAutoCommit(true);
            }
            try {
                action.accept(conn);
            } finally {
                if (!original) {
                    conn.setAutoCommit(original);
                }
            }
        }
    }

    @FunctionalInterface
    private interface SqlConsumer {
        void accept(Connection conn) throws Exception;
    }

    /** 生成器产出的 java.time 值转换为 JDBC 兼容类型（LocalDate/LocalDateTime 非所有驱动支持 setObject 直传） */
    private Object jdbcValue(Object value) {
        if (value instanceof LocalDate) {
            return java.sql.Date.valueOf((LocalDate) value);
        }
        if (value instanceof LocalDateTime) {
            return java.sql.Timestamp.valueOf((LocalDateTime) value);
        }
        if (value instanceof LocalTime) {
            return java.sql.Time.valueOf((LocalTime) value);
        }
        return value;
    }

    @Override
    public boolean tableExists(String table) throws Exception {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + qualifiedTable(table))) {
            ps.executeQuery();
            return true;
        } catch (java.sql.SQLException e) {
            // 表不存在（如 MySQL 1146 / DB2 i SQL0204）返回 false；其他 SQL 异常同样视为不可访问
            return false;
        }
    }

    @Override
    public void dropTable(String table) throws Exception {
        final String sql = "DROP TABLE " + qualifiedTable(table);
        withAutoCommit(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public List<Map<String, Object>> tableColumns(String table) throws Exception {
        List<Map<String, Object>> columns = new ArrayList<>();
        try (Connection conn = connection()) {
            // JDBC 标准 API：跨驱动通用；表名中的下划线是单字符通配符，结果按 TABLE_NAME 过滤
            try (ResultSet rs = conn.getMetaData().getColumns(null, schemaPattern(), table, "%")) {
                while (rs.next()) {
                    if (!table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        continue;
                    }
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", rs.getString("COLUMN_NAME"));
                    col.put("type", rs.getString("TYPE_NAME"));
                    col.put("size", rs.getInt("COLUMN_SIZE"));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    /**
     * 列元数据查询的 schema 过滤（DatabaseMetaData.getColumns 的 schemaPattern）。
     * 默认 null（MySQL 等当前库模式）；Schema 即库名的数据源（DB2 i）覆写为库名。
     */
    protected String schemaPattern() {
        return null;
    }

    @Override
    public List<String> listIndexes(String table) throws Exception {
        List<String> indexes = new ArrayList<>();
        try (Connection conn = connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    indexes.add(rs.getString(1));
                }
            }
        }
        return indexes;
    }

    @Override
    public void createIndex(String table, String indexName, String column) throws Exception {
        final String sql = "CREATE UNIQUE INDEX " + indexName + " ON " + qualifiedTable(table)
                + " (" + qualifiedColumn(column) + ")";
        withAutoCommit(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public List<String> listConstraints(String table) throws Exception {
        List<String> constraints = new ArrayList<>();
        try (Connection conn = connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    constraints.add(rs.getString(1));
                }
            }
        }
        return constraints;
    }

    @Override
    public void createConstraint(String table, String constraintName, String column) throws Exception {
        final String sql = "ALTER TABLE " + qualifiedTable(table) + " ADD CONSTRAINT " + constraintName
                + " UNIQUE (" + qualifiedColumn(column) + ")";
        withAutoCommit(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void createForeignKeyConstraint(String table, String constraintName, String column,
                                           String referencesTable, String referencesColumn) throws Exception {
        final String sql = "ALTER TABLE " + qualifiedTable(table) + " ADD CONSTRAINT " + constraintName
                + " FOREIGN KEY (" + qualifiedColumn(column) + ") REFERENCES "
                + qualifiedTable(referencesTable) + " (" + qualifiedColumn(referencesColumn) + ")";
        withAutoCommit(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void dropTablesByPrefix(String prefix) throws Exception {
        if (prefix == null || prefix.isEmpty()) {
            return;
        }
        try (Connection conn = connection()) {
            // JDBC 标准元数据 API 跨驱动通用；getTables 的 pattern 中下划线是单字符通配符，
            // 匹配结果再按 startsWith 精确过滤，避免前缀中的下划线误伤
            List<String> matched = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getTables(null, schemaPattern(), prefix + "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name != null && name.startsWith(prefix)) {
                        matched.add(name);
                    }
                }
            }
            for (String table : matched) {
                try (Statement st = conn.createStatement()) {
                    st.execute("DROP TABLE " + qualifiedTable(table));
                }
            }
        }
    }

    /** 表名限定（默认原样；DB2 i 等 Schema 限定库覆写为 {@code "SCHEMA"."TABLE"}） */
    protected String qualifiedTable(String table) {
        return table;
    }

    /** 列名引用（默认原样；大小写敏感/双引号建表列名的库（如 DB2 i）覆写为 {@code "COLUMN"}） */
    protected String qualifiedColumn(String column) {
        return column;
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name + " not found in " + clazz.getName());
    }
}
