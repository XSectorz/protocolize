package dev.simplix.protocolize.bungee.strategies;

import dev.simplix.protocolize.api.util.ReflectionUtil;
import dev.simplix.protocolize.bungee.strategy.PacketRegistrationStrategy;
import net.md_5.bungee.api.ProxyServer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Date: 21.08.2021
 *
 * @author Exceptionflug
 */
public final class LegacyBungeeCordPacketRegistrationStrategy implements PacketRegistrationStrategy {

    private final Class<?> protocolDataClass = ReflectionUtil.getClassOrNull("net.md_5.bungee.protocol.Protocol$ProtocolData");
    private final Field protocolDataConstructorsField = ReflectionUtil.fieldOrNull(protocolDataClass, "packetConstructors", true);
    private final Field protocolDataPacketMapField = ReflectionUtil.fieldOrNull(protocolDataClass, "packetMap", true);

    private Method protocolsGetMethod;
    private Method packetMapPutMethod;

    @Override
    public void registerPacket(Object protocols, int protocolVersion, int packetId, Class<?> clazz) throws Exception {
        if (protocolsGetMethod == null) {
            protocolsGetMethod = protocols.getClass().getMethod("get", int.class);
        }
        final Object protocolData = protocolsGetMethod.invoke(protocols, protocolVersion);
        if (protocolData == null) {
            ProxyServer.getInstance().getLogger().finest("[Protocolize | DEBUG] Protocol version " + protocolVersion + " is not supported on this version. Skipping registration for that specific version.");
            return;
        }
        Object packetMap = protocolDataPacketMapField.get(protocolData);
        if (packetMapPutMethod == null) {
            packetMapPutMethod = packetMap.getClass().getMethod("put", Object.class, int.class);
        }
        packetMapPutMethod.invoke(packetMap, clazz, packetId);
        ((Constructor[]) protocolDataConstructorsField.get(protocolData))[packetId] = clazz.getDeclaredConstructor();
    }

    @Override
    public boolean compatible() {
        if (protocolDataClass == null || protocolDataConstructorsField == null || protocolDataPacketMapField == null) {
            return false;
        }
        return protocolDataConstructorsField.getType().equals(Constructor[].class);
    }

}
