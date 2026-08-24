package io.tapdata.it.support;

import io.tapdata.entity.codec.TapCodecsRegistry;
import io.tapdata.entity.codec.filter.TapCodecsFilterManager;

/**
 * 引擎编解码过滤器组装器（测试侧复刻）。
 * <p>
 * 引擎中 Connector 节点（{@code TaskNodePdkConnector} → {@code ConnectorNode}）的写/读数据流
 * 使用基于 connector codecRegistry 的 {@link TapCodecsFilterManager}：
 * <ul>
 *   <li>wrap（源读回 → TapValue）：connector 自定义 ToTapValueCodec 优先，
 *       再按 schema tapType 专用 codec（DATE/DATETIME/TIME/ARRAY/MAP/YEAR/BINARY），
 *       回落 TapDefaultCodecs（String/Number/Boolean/byte[]），最后 TapRawValue 兜底；</li>
 *   <li>unwrap（TapValue → 目标普通值）：connector 自定义 FromTapValueCodec 优先，
 *       回落 TapDefaultCodecs（时间 tapValue 返回 DateTime 等原始对象）。</li>
 * </ul>
 * 本工厂直接复用 connector 在 registerCapabilities 中注册的 codecRegistry（与引擎边界行为一致），
 * 供集成测试断言 wrap/unwrap 转换契约。
 * <p>
 * 注意：iengine 内部节点（HazelcastBaseNode → {@code TapCodecUtil}）另有时间类型 tag 序列化
 * （非法时区 DateTime → 带 TAG 头的 byte[]、Year/Date/Time → tagged byte[]），属于跨节点传输层语义；
 * 集成测试聚焦 Connector 边界数据流，不覆盖该序列化格式。
 */
public final class EngineCodecs {

    private EngineCodecs() {
    }

    /**
     * 组装与引擎 Connector 节点一致的 {@link TapCodecsFilterManager}。
     *
     * @param codecRegistry connector registerCapabilities 产物的 codecRegistry
     *                      （{@code ConnectorTestContext.getCodecRegistry()}）
     */
    public static TapCodecsFilterManager createEngineCodecsFilterManager(TapCodecsRegistry codecRegistry) {
        return TapCodecsFilterManager.create(codecRegistry);
    }
}
