package dev.simplix.protocolize.bungee.strategies;

import dev.simplix.protocolize.api.util.ReflectionUtil;
import dev.simplix.protocolize.bungee.strategy.PacketRegistrationStrategy;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Date: 21.08.2021
 *
 * @author Exceptionflug
 */
@Slf4j
public final class BungeeCordPacketRegistrationStrategy implements PacketRegistrationStrategy {

    private final Class<?> protocolDataClass = ReflectionUtil.getClassOrNull("net.md_5.bungee.protocol.Protocol$ProtocolData");
    private final Field protocolDataConstructorsField = ReflectionUtil.fieldOrNull(protocolDataClass, "packetConstructors", true);
    private final Field protocolDataPacketMapField = ReflectionUtil.fieldOrNull(protocolDataClass, "packetMap", true);

    private Method protocolsGetMethod;
    private Method packetMapPutMethod;

    private Method findMethod(Object obj, String name, Class<?>... paramTypes) {
        // Search declared methods first to find the exact signature
        for (Method m : obj.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != paramTypes.length) continue;
            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                if (!params[i].equals(paramTypes[i])) {
                    match = false;
                    break;
                }
            }
            if (match) return m;
        }
        return null;
    }

    @Override
    public void registerPacket(Object protocols, int protocolVersion, int packetId, Class<?> clazz) throws Exception {
        // Resolve get(int) method on protocols map (works for both TIntObjectMap and Int2ObjectMap)
        if (protocolsGetMethod == null) {
            protocolsGetMethod = findMethod(protocols, "get", int.class);
            if (protocolsGetMethod == null) {
                // Fallback: try Object key
                protocolsGetMethod = protocols.getClass().getMethod("get", Object.class);
            }
        }

        Object protocolData;
        if (protocolsGetMethod.getParameterTypes()[0] == int.class) {
            protocolData = protocolsGetMethod.invoke(protocols, protocolVersion);
        } else {
            protocolData = protocolsGetMethod.invoke(protocols, (Integer) protocolVersion);
        }

        if (protocolData == null) {
            log.debug("[Protocolize | DEBUG] Protocol version {} is not supported on this version. Skipping.", protocolVersion);
            return;
        }

        // Resolve put method on packetMap (works for both TObjectIntMap and Object2IntMap)
        Object packetMap = protocolDataPacketMapField.get(protocolData);
        if (packetMapPutMethod == null) {
            // Try primitive int first (Trove TObjectIntMap and fastutil Object2IntMap both have put(K, int))
            packetMapPutMethod = findMethod(packetMap, "put", Object.class, int.class);
            if (packetMapPutMethod == null) {
                // Fallback: Map.put(Object, Object)
                packetMapPutMethod = packetMap.getClass().getMethod("put", Object.class, Object.class);
            }
        }

        if (packetMapPutMethod.getParameterTypes()[1] == int.class) {
            packetMapPutMethod.invoke(packetMap, clazz, packetId);
        } else {
            packetMapPutMethod.invoke(packetMap, clazz, (Integer) packetId);
        }

        ((Supplier[]) protocolDataConstructorsField.get(protocolData))[packetId] = () -> {
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                e.printStackTrace();
            }
            return null;
        };
    }

    @Override
    public boolean compatible() {
        if (protocolDataClass == null || protocolDataConstructorsField == null || protocolDataPacketMapField == null) {
            return false;
        }
        return protocolDataConstructorsField.getType().equals(Supplier[].class);
    }

}
