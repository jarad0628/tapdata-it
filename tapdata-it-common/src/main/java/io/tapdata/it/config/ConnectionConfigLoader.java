package io.tapdata.it.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapdata.entity.utils.DataMap;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
/**
 * 连接器/引擎集成测试连接配置加载器。
 * <p>
 * 加载优先级（越低优先级越高，可覆盖前者）：
 * <ol>
 *   <li>JSON 文件（classpath 优先，其次文件系统）</li>
 *   <li>外部接口返回的 JSON（通过 {@link #CONFIG_URL_ENV} 或 {@link #CONFIG_URL_PROP} 指定 URL）</li>
 *   <li>系统属性 {@code connector.it.<key>} 或环境变量 {@code CONNECTOR_IT_<KEY>}</li>
 * </ol>
 * <p>
 * 外部接口能力预留：后续可由统一的服务动态创建被依赖数据库，并返回连接信息；
 * 测试启动前设置 {@code CONNECTOR_IT_CONFIG_URL=http://.../connection} 即可将远程配置合并进来。
 */
public class ConnectionConfigLoader {

    /**
     * 默认远程配置 URL 环境变量名。
     */
    public static final String CONFIG_URL_ENV = "CONNECTOR_IT_CONFIG_URL";

    /**
     * 默认远程配置 URL 系统属性名。
     */
    public static final String CONFIG_URL_PROP = "connector.it.config.url";

    /**
     * 系统属性前缀，用于逐项覆盖连接配置。
     */
    public static final String PROPERTY_PREFIX = "connector.it.";

    /**
     * 环境变量前缀，用于逐项覆盖连接配置。
     */
    public static final String ENVIRONMENT_PREFIX = "CONNECTOR_IT_";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConnectionConfigLoader() {
        // 工具类
    }

    /**
     * 加载连接配置，优先从 classpath/文件系统读取 path 指定的 JSON，
     * 再合并 {@link #CONFIG_URL_ENV} / {@link #CONFIG_URL_PROP} 指向的外部接口配置，
     * 最后应用系统属性和环境变量覆盖。
     *
     * @param path JSON 资源路径（classpath 优先，其次文件系统）
     * @return 合并后的连接配置
     * @throws IOException 读取或解析失败
     */
    public static DataMap load(String path) throws IOException {
        return load(path, resolveExternalUrl());
    }

    /**
     * 加载连接配置，可显式指定外部配置 URL（为 null/空时忽略），
     * 外部配置会合并到文件配置之上，再由系统属性/环境变量覆盖。
     *
     * @param path        JSON 资源路径（classpath 优先，其次文件系统）
     * @param externalUrl 可选的外部配置接口地址
     * @return 合并后的连接配置
     * @throws IOException 读取或解析失败
     */
    public static DataMap load(String path, String externalUrl) throws IOException {
        Map<String, Object> config = loadJson(path);

        if (externalUrl != null && !externalUrl.isEmpty()) {
            Map<String, Object> remote = fetchJson(externalUrl);
            if (remote != null) {
                config.putAll(remote);
            }
        }

        DataMap result = DataMap.create();
        result.putAll(config);
        applyOverrides(result);
        return result;
    }

    /**
     * 读取 JSON 资源：先尝试 classpath，再尝试文件系统。
     */
    private static Map<String, Object> loadJson(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            return new LinkedHashMap<>();
        }
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (in == null) {
            in = new FileInputStream(path);
        }
        try (InputStream resource = in) {
            String json = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            if (json.trim().isEmpty()) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            return map == null ? new LinkedHashMap<>() : map;
        }
    }

    /**
     * 从外部接口拉取 JSON 配置。
     * <p>
     * 目前只支持简单的 GET 请求；后续如接口需要鉴权，
     * 可扩展读取 {@code CONNECTOR_IT_CONFIG_HEADERS} 等环境变量设置请求头。
     */
    private static Map<String, Object> fetchJson(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept", "application/json");
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Failed to fetch external config from " + urlString + ", status: " + responseCode);
        }
        try (InputStream in = connection.getInputStream();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Map<String, Object> map = MAPPER.readValue(reader, new TypeReference<Map<String, Object>>() {
            });
            return map == null ? new LinkedHashMap<>() : map;
        }
    }

    /**
     * 解析默认的外部配置 URL：系统属性优先，其次环境变量。
     */
    private static String resolveExternalUrl() {
        String prop = System.getProperty(CONFIG_URL_PROP);
        if (prop != null && !prop.isEmpty()) {
            return prop;
        }
        String env = System.getenv(CONFIG_URL_ENV);
        return env != null && !env.isEmpty() ? env : null;
    }

    /**
     * 按点号路径读取嵌套配置值，如 {@code getString(config, "mysql.host")}；
     * 路径不存在或值为 null 时返回 null。
     */
    @SuppressWarnings("unchecked")
    public static String getString(DataMap config, String path) {
        if (config == null || path == null || path.isEmpty()) {
            return null;
        }
        Object value = config;
        for (String segment : path.split("\\.")) {
            if (!(value instanceof Map)) {
                return null;
            }
            value = ((Map<String, Object>) value).get(segment);
        }
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 应用系统属性和环境变量覆盖（支持嵌套配置）。
     * <p>
     * 对配置中的每个叶子节点（路径以 {@code .} 连接），依次尝试：
     * <ul>
     *   <li>系统属性 {@code connector.it.<path>}（如 {@code connector.it.mysql.host}）</li>
     *   <li>环境变量 {@code CONNECTOR_IT_<PATH>}（路径中的 {@code .} 转 {@code _} 并大写，如 {@code CONNECTOR_IT_MYSQL_HOST}）</li>
     * </ul>
     * 命中则覆盖原值；顶层 key 的行为与旧版一致（如 {@code CONNECTOR_IT_HOST}）。
     */
    @SuppressWarnings("unchecked")
    private static void applyOverrides(DataMap config) {
        applyOverridesRecursive(config, "");
    }

    @SuppressWarnings("unchecked")
    private static void applyOverridesRecursive(Map<String, Object> node, String prefix) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = entry.getKey();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = entry.getValue();
            if (value instanceof Map) {
                applyOverridesRecursive((Map<String, Object>) value, path);
                continue;
            }
            String sysProp = System.getProperty(PROPERTY_PREFIX + path);
            if (sysProp != null) {
                node.put(key, sysProp);
                continue;
            }
            String envKey = ENVIRONMENT_PREFIX + path.toUpperCase().replace('.', '_');
            String env = System.getenv(envKey);
            if (env != null) {
                node.put(key, env);
            }
        }
    }
}
