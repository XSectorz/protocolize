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

    @Override
    public void registerPacket(Object protocols, int protocolVersion, int packetId, Class<?> clazz) throws Exception {
        // Resolve get(int) on protocols map
        if (protocolsGetMethod == null) {
            for (Method m : protocols.getClass().getMethods()) {
                if (m.getName().equals("get") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                    protocolsGetMethod = m;
                    break;
                }
            }
            if (protocolsGetMethod == null) {
                protocolsGetMethod = protocols.getClass().getMethod("get", Object.class);
            }
        }

        Object protocolData;
        if (protocolsGetMethod.getParameterTypes()[0] == int.class) {
            protocolData = protocolsGetMethod.invoke(protocols, protocolVersion);
        } else {
            protocolData = protocolsGetMethod.invoke(protocols, Integer.valueOf(protocolVersion));
        }

        if (protocolData == null) {
            return;
        }

        // Resolve put on packetMap - search all methods for compatible signature
        Object packetMap = protocolDataPacketMapField.get(protocolData);
        if (packetMapPutMethod == null) {
            for (Method m : packetMap.getClass().getMethods()) {
                if (!m.getName().equals("put")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2) continue;
                // Look for put(Object/Class, int) or put(Object/Class, Integer)
                if (params[0].isAssignableFrom(Class.class) &&
                    (params[1] == int.class || params[1] == Integer.class)) {
                    packetMapPutMethod = m;
                    break;
                }
            }
            // Final fallback: Map.put(Object, Object)
            if (packetMapPutMethod == null) {
                packetMapPutMethod = packetMap.getClass().getMethod("put", Object.class, Object.class);
            }
        }

        // Invoke put with correct parameter types
        Class<?>[] paramTypes = packetMapPutMethod.getParameterTypes();
        if (paramTypes[1] == int.class) {
            packetMapPutMethod.invoke(packetMap, clazz, packetId);
        } else {
            packetMapPutMethod.invoke(packetMap, clazz, Integer.valueOf(packetId));
        }

        // Register constructor
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
