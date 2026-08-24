package io.tapdata.it.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapdata.entity.mapping.DefaultExpressionMatchingMap;
import io.tapdata.entity.mapping.TypeExprResult;
import io.tapdata.entity.mapping.type.TapMapping;
import io.tapdata.entity.schema.type.TapType;
import io.tapdata.entity.utils.DataMap;
import io.tapdata.pdk.apis.annotations.TapConnectorClass;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 方言数据类型解析器：根据 Connector 的 spec.json 中 dataTypes 声明，
 * 将 discoverSchema 返回的方言 dataType（如 INT、VARCHAR(255)、Int32）解析为 TapType。
 * <p>
 * 与引擎 wrap 链路一致：引擎根据 spec.json dataTypes 将方言值包装为 TapXxxx 类型
 * （TableFieldTypesGenerator.autoFill 同款解析逻辑），因此集成测试不再维护
 * 方言映射表（TypeMapping），直接以 spec 声明为准。
 * <p>
 * 匹配规则与 {@link DefaultExpressionMatchingMap} 一致：表达式匹配 + 大小写不敏感
 * （如 MySQL {@code int unsigned}、DB2 i {@code FLOAT(4)}、MongoDB {@code Int32} 均可匹配）。
 */
public class TapTypeResolver {

    private final DefaultExpressionMatchingMap dataTypesMap;

    private TapTypeResolver(DefaultExpressionMatchingMap dataTypesMap) {
        this.dataTypesMap = dataTypesMap;
    }

    /**
     * 从 Connector 类的 {@link TapConnectorClass} 注解读取 spec 文件名并加载 dataTypes
     * （如 Db2Connector 上的 {@code @TapConnectorClass("spec_db2.json")}）。
     */
    public static TapTypeResolver from(Class<?> connectorClass) throws IOException {
        TapConnectorClass annotation = connectorClass.getAnnotation(TapConnectorClass.class);
        if (annotation == null) {
            throw new IllegalStateException("No @TapConnectorClass annotation found on " + connectorClass.getName());
        }
        return fromSpec(annotation.value());
    }

    /** 从 classpath（或文件系统）加载 spec.json 的 dataTypes 声明 */
    public static TapTypeResolver fromSpec(String specPath) throws IOException {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(specPath);
        if (in == null) {
            in = new FileInputStream(specPath);
        }
        String json;
        try (InputStream resource = in) {
            json = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode dataTypes = new ObjectMapper().readTree(json).get("dataTypes");
        if (dataTypes == null || !dataTypes.isObject()) {
            throw new IllegalStateException("No dataTypes section found in spec: " + specPath);
        }
        return new TapTypeResolver(DefaultExpressionMatchingMap.map(dataTypes.toString()));
    }

    /**
     * 方言 dataType → TapType（与引擎 TableFieldTypesGeneratorImpl 同款：
     * 表达式匹配 → TapMapping.toTapType 生成精确 TapType）；spec 未声明时返回 null。
     */
    public TapType resolve(String dataType) {
        if (dataType == null) {
            return null;
        }
        TypeExprResult<DataMap> result = dataTypesMap.get(dataType);
        if (result == null) {
            return null;
        }
        TapMapping tapMapping = (TapMapping) result.getValue().get(TapMapping.FIELD_TYPE_MAPPING);
        return tapMapping == null ? null : tapMapping.toTapType(dataType, result.getParams());
    }

    /** 方言 dataType 是否被 spec dataTypes 声明（表达式匹配 + 大小写不敏感） */
    public boolean isDeclared(String dataType) {
        return dataType != null && dataTypesMap.get(dataType) != null;
    }
}
