package io.tapdata.it.support;

import io.tapdata.entity.utils.cache.Entry;
import io.tapdata.entity.utils.cache.Iterator;
import io.tapdata.entity.utils.cache.KVMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 KVMap 实现：供 {@code TapConnectorContext.setStateMap} 使用。
 * <p>
 * 部分 Connector 在 onStart/写记录时依赖 stateMap 存取状态（如自增序列、临时标记），
 * 与引擎中 KVMap 语义一致（键值对内存存储）。
 */
public class TestStateMap implements KVMap<Object> {

    private final Map<String, Object> map = new ConcurrentHashMap<>();

    @Override
    public void init(String mapKey, Class<Object> valueClass) {
        // 内存实现无需初始化
    }

    @Override
    public void put(String key, Object value) {
        map.put(key, value);
    }

    @Override
    public Object putIfAbsent(String key, Object value) {
        return map.putIfAbsent(key, value);
    }

    @Override
    public Object remove(String key) {
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
    public Object get(String key) {
        return map.get(key);
    }

    @Override
    public Iterator<Entry<Object>> iterator() {
        java.util.Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
        return new Iterator<Entry<Object>>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Entry<Object> next() {
                Map.Entry<String, Object> e = it.next();
                return new Entry<Object>() {
                    @Override
                    public String getKey() {
                        return e.getKey();
                    }

                    @Override
                    public Object getValue() {
                        return e.getValue();
                    }
                };
            }
        };
    }
}
