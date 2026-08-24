package io.tapdata.it.support;

import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.utils.cache.Entry;
import io.tapdata.entity.utils.cache.Iterator;
import io.tapdata.entity.utils.cache.KVMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 KVMap&lt;TapTable&gt; 实现：供 {@code TapConnectorContext.setTableMap} 使用。
 * <p>
 * 部分 Connector（如 MySQL）在 DDL 操作（alterFieldName/alterFieldAttributes）中
 * 依赖 tableMap 读取字段元数据。基类 {@link io.tapdata.it.ConnectorIT} 在
 * {@code prepareContext} 中默认使用此实现替代 null-returning 的匿名 KVReadOnlyMap。
 * <p>
 * 测试可通过 {@link #put(String, TapTable)} 注册已构建的 TapTable，
 * 也可在调用 DDL 前调用 {@code registerTable(buildTapTable())} 自动注册。
 */
public class TestTableMap implements KVMap<TapTable> {

    private final Map<String, TapTable> map = new ConcurrentHashMap<>();

    @Override
    public void init(String mapKey, Class<TapTable> valueClass) {
        // 内存实现无需初始化
    }

    @Override
    public void put(String key, TapTable value) {
        map.put(key, value);
    }

    @Override
    public TapTable putIfAbsent(String key, TapTable value) {
        return map.putIfAbsent(key, value);
    }

    @Override
    public TapTable remove(String key) {
        return map.remove(key);
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public void reset() {
        map.clear();
    }

    @Override
    public TapTable get(String key) {
        return map.get(key);
    }

    @Override
    public Iterator<Entry<TapTable>> iterator() {
        java.util.Iterator<Map.Entry<String, TapTable>> it = map.entrySet().iterator();
        return new Iterator<Entry<TapTable>>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Entry<TapTable> next() {
                Map.Entry<String, TapTable> e = it.next();
                return new Entry<TapTable>() {
                    @Override
                    public String getKey() {
                        return e.getKey();
                    }

                    @Override
                    public TapTable getValue() {
                        return e.getValue();
                    }
                };
            }
        };
    }
}