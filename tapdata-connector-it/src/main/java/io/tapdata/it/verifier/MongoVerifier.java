package io.tapdata.it.verifier;

import io.tapdata.it.schema.TestFieldSpec;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MongoDB 旁路验证器：通过 Connector 内部 {@code com.mongodb.client.MongoClient}
 * 直连对端数据库执行 countDocuments/find，不经过 Connector 任何 read 能力。
 * <p>
 * 反射链路：Connector 实例 → 成员 {@code MongoClient} → getDatabase(库名)
 * → getCollection(表名) → countDocuments / find(Filters.in)。
 * 目标库名由连接配置（config["database"]）提供，文档（Document 为 Map 子类）直接返回。
 * <p>
 * 旁路准备：{@link #createTable} 等价 createCollection（MongoDB 无 DDL，仅建空集合），
 * {@link #insert} 通过 Document(Map) 构造 + insertMany 批量直写。
 */
public class MongoVerifier implements ConnectorVerifier {

    private final Object mongoClient;
    private final String database;
    // 一律基于公开接口反射（com.mongodb.client.* 为 driver 导出包）；
    // 直接反射实现类（如 com.mongodb.client.internal.MongoCollectionImpl）会被 Java 9+ 模块系统拒绝
    private static final String MONGO_CLIENT = "com.mongodb.client.MongoClient";
    private static final String MONGO_DATABASE = "com.mongodb.client.MongoDatabase";
    private static final String MONGO_COLLECTION = "com.mongodb.client.MongoCollection";

    /**
     * @param mongoClient Connector 实例中 {@code com.mongodb.client.MongoClient} 成员对象
     * @param database    目标数据库名（来自连接配置）
     */
    public MongoVerifier(Object mongoClient, String database) {
        if (mongoClient == null) {
            throw new IllegalArgumentException("mongoClient is null");
        }
        if (database == null || database.isEmpty()) {
            throw new IllegalArgumentException("database is null or empty, cannot verify without target database");
        }
        this.mongoClient = mongoClient;
        this.database = database;
    }

    @Override
    public long count(String table) throws Exception {
        Object coll = collection(table);
        Class<?> collIface = Class.forName(MONGO_COLLECTION);
        Method count;
        try {
            count = collIface.getMethod("countDocuments");
        } catch (NoSuchMethodException e) {
            // 兼容旧版驱动（3.x 仅有 count()）
            count = collIface.getMethod("count");
        }
        return ((Number) count.invoke(coll)).longValue();
    }

    @Override
    public List<Map<String, Object>> selectByPk(String table, String pkName, List<Object> pkValues) throws Exception {
        if (pkValues == null || pkValues.isEmpty()) {
            return new ArrayList<>();
        }
        Object coll = collection(table);
        // Filters.in(fieldName, values...) → Bson
        Class<?> filters = Class.forName("com.mongodb.client.model.Filters");
        Object filter = filters.getMethod("in", String.class, Object[].class)
                .invoke(null, pkName, pkValues.toArray());
        // collection.find(filter) → FindIterable<Document>，Document 为 Map<String,Object> 子类
        Class<?> bson = Class.forName("org.bson.conversions.Bson");
        Object iterable = Class.forName(MONGO_COLLECTION).getMethod("find", bson).invoke(coll, filter);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object doc : (Iterable<?>) iterable) {
            rows.add((Map<String, Object>) doc);
        }
        return rows;
    }

    /** 反射取 MongoCollection（MongoClient.getDatabase(name).getCollection(table)，方法声明在公开接口上） */
    private Object collection(String table) throws Exception {
        Object db = database();
        return Class.forName(MONGO_DATABASE).getMethod("getCollection", String.class).invoke(db, table);
    }

    /** 反射取 MongoDatabase（getDatabase 声明在公开接口 MongoClient 上） */
    private Object database() throws Exception {
        return Class.forName(MONGO_CLIENT).getMethod("getDatabase", String.class).invoke(mongoClient, database);
    }

    @Override
    public void createTable(String table, List<TestFieldSpec> fields) throws Exception {
        // MongoDB 无 DDL：显式建空集合等价 CREATE TABLE（字段由文档隐式定义）
        Class.forName(MONGO_DATABASE).getMethod("createCollection", String.class).invoke(database(), table);
    }

    @Override
    public boolean tableExists(String table) throws Exception {
        // 集合名精确匹配（listCollectionNames 只含实际存在的集合，避免 countDocuments 隐式建集合的误判）
        Class<?> iterableIface = Class.forName("com.mongodb.client.MongoIterable");
        Object names = Class.forName(MONGO_DATABASE).getMethod("listCollectionNames").invoke(database());
        for (Object name : (Iterable<?>) iterableIface.cast(names)) {
            if (table.equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void dropTable(String table) throws Exception {
        Class.forName(MONGO_COLLECTION).getMethod("drop").invoke(collection(table));
    }

    @Override
    public List<Map<String, Object>> tableColumns(String table) throws Exception {
        // MongoDB 无固定 schema（字段由文档隐式定义），字段级 DDL 用例自动跳过
        return new ArrayList<>();
    }

    @Override
    public List<String> listIndexes(String table) throws Exception {
        List<String> indexes = new ArrayList<>();
        // listIndexes() 返回 ListIndexesIterable<Document>（Iterable），含默认 _id_ 索引
        Object iterable = Class.forName(MONGO_COLLECTION).getMethod("listIndexes").invoke(collection(table));
        for (Object doc : (Iterable<?>) iterable) {
            Map<String, Object> index = (Map<String, Object>) doc;
            Object name = index.get("name");
            if (name != null) {
                indexes.add(name.toString());
            }
        }
        return indexes;
    }

    @Override
    public void createIndex(String table, String indexName, String column) throws Exception {
        // Indexes.ascending(field) → Bson；IndexOptions.name(indexName) 显式指定索引名（默认名不可控）。
        // 注意 ascending 是变参（String...），反射签名必须用数组类型，否则 NoSuchMethodException
        Class<?> indexes = Class.forName("com.mongodb.client.model.Indexes");
        Object keys = indexes.getMethod("ascending", String[].class).invoke(null, (Object) new String[]{column});
        Class<?> options = Class.forName("com.mongodb.client.model.IndexOptions");
        Object indexOptions = options.getConstructor().newInstance();
        options.getMethod("name", String.class).invoke(indexOptions, indexName);
        Class<?> bson = Class.forName("org.bson.conversions.Bson");
        Class.forName(MONGO_COLLECTION).getMethod("createIndex", bson, options).invoke(collection(table), keys, indexOptions);
    }

    @Override
    public List<String> listConstraints(String table) throws Exception {
        // MongoDB 无约束概念（唯一性靠索引），约束类用例自动跳过
        return new ArrayList<>();
    }

    @Override
    public void createConstraint(String table, String constraintName, String column) throws Exception {
        // MongoDB 无约束概念，空操作（约束类用例通过 listConstraints 空结果自动跳过）
    }

    @Override
    public void createForeignKeyConstraint(String table, String constraintName, String column,
                                           String referencesTable, String referencesColumn) throws Exception {
        // MongoDB 无约束概念，空操作（约束类用例通过 listConstraints 空结果自动跳过）
    }

    @Override
    public void insert(String table, List<Map<String, Object>> rows) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Object coll = collection(table);
        // Document(Map<String,Object>) 构造：org.bson.Document 为公开导出类
        Constructor<?> ctor = Class.forName("org.bson.Document").getConstructor(Map.class);
        List<Object> docs = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            docs.add(ctor.newInstance(row));
        }
        // MongoCollection.insertMany(List<? extends Document>) 声明在公开接口上
        Class.forName(MONGO_COLLECTION).getMethod("insertMany", List.class).invoke(coll, docs);
    }
}
