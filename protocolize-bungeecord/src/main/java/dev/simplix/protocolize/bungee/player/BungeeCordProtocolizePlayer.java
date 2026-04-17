package dev.simplix.protocolize.bungee.player;

import dev.simplix.protocolize.api.*;
import dev.simplix.protocolize.api.inventory.Inventory;
import dev.simplix.protocolize.api.inventory.PlayerInventory;
import dev.simplix.protocolize.api.mapping.ProtocolIdMapping;
import dev.simplix.protocolize.api.packet.AbstractPacket;
import dev.simplix.protocolize.api.packet.RegisteredPacket;
import dev.simplix.protocolize.api.player.ProtocolizePlayer;
import dev.simplix.protocolize.api.providers.MappingProvider;
import dev.simplix.protocolize.api.providers.ProtocolRegistrationProvider;
import dev.simplix.protocolize.bungee.packet.BungeeCordProtocolizePacket;
import dev.simplix.protocolize.bungee.util.ReflectionUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.protocol.DefinedPacket;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
@Getter
@Accessors(fluent = true)
public class BungeeCordProtocolizePlayer implements ProtocolizePlayer {

    private static final ProtocolRegistrationProvider REGISTRATION_PROVIDER = Protocolize.protocolRegistration();
    private static final MappingProvider MAPPING_PROVIDER = Protocolize.mappingProvider();
    private final List<Consumer<PlayerInteract>> interactConsumers = new ArrayList<>();
    private final AtomicInteger windowId = new AtomicInteger(101);
    private final Map<Integer, Inventory> registeredInventories = new ConcurrentHashMap<>();
    private final PlayerInventory proxyInventory = new PlayerInventory(this);
    private final UUID uniqueId;
    private final Location location = new Location(0, 0, 0, 0, 0);

    public BungeeCordProtocolizePlayer(UUID uniqueId) {
        this.uniqueId = uniqueId;
    }

    @Override
    public void sendPacket(Object packet) {
        if (packet instanceof AbstractPacket abstractPacket) {
            BungeeCordProtocolizePacket pack = (BungeeCordProtocolizePacket) REGISTRATION_PROVIDER.createPacket(
                (Class<? extends AbstractPacket>) packet.getClass(),
                Protocol.PLAY, PacketDirection.CLIENTBOUND, protocolVersion());
            if (pack != null) {
                pack.wrapper(abstractPacket);
                packet = pack;
            } else {
                // Fallback: send raw packet bytes directly through the channel
                sendRawPacket(abstractPacket, PacketDirection.CLIENTBOUND);
                return;
            }
        }
        ProxiedPlayer player = player();
        if (player != null) {
            player.unsafe().sendPacket((DefinedPacket) packet);
        }
    }

    @Override
    public void sendPacketToServer(Object packet) {
        if (packet instanceof AbstractPacket abstractPacket) {
            BungeeCordProtocolizePacket pack = (BungeeCordProtocolizePacket) REGISTRATION_PROVIDER.createPacket(
                (Class<? extends AbstractPacket>) packet.getClass(),
                Protocol.PLAY, PacketDirection.SERVERBOUND, protocolVersion());
            if (pack != null) {
                pack.wrapper(abstractPacket);
                packet = pack;
            } else {
                return;
            }
        }
        ProxiedPlayer player = player();
        if (player != null) {
            Server server = player.getServer();
            if (server == null) {
                return;
            }
            server.unsafe().sendPacket((DefinedPacket) packet);
        }
    }

    private void sendRawPacket(AbstractPacket abstractPacket, PacketDirection direction) {
        ProxiedPlayer player = player();
        if (player == null) return;

        int version = protocolVersion();
        ProtocolIdMapping mapping = MAPPING_PROVIDER.mapping(
            new RegisteredPacket(direction, abstractPacket.getClass()), version);
        if (mapping == null) {
            log.warn("[Protocolize] No mapping found for {} at protocol {}", abstractPacket.getClass().getSimpleName(), version);
            return;
        }

        ByteBuf packetData = Unpooled.buffer();
        try {
            // Write packet content (without packet ID - BungeeCord's encoder adds framing)
            abstractPacket.write(packetData, direction, version);

            // Create a DefinedPacket wrapper that writes our raw data with the correct packet ID
            final ByteBuf data = packetData.retain();
            final int packetId = mapping.id();

            DefinedPacket rawPacket = new DefinedPacket() {
                @Override
                public void read(ByteBuf buf) {}

                @Override
                public void write(ByteBuf buf) {
                    buf.writeBytes(data);
                    data.release();
                }

                @Override
                public void handle(net.md_5.bungee.protocol.AbstractPacketHandler handler) {}

                @Override
                public String toString() {
                    return "RawProtocolizePacket(id=0x" + Integer.toHexString(packetId) + ")";
                }

                @Override
                public boolean equals(Object o) {
                    return this == o;
                }

                @Override
                public int hashCode() {
                    return packetId;
                }
            };

            // Register temporarily in BungeeCord's protocol map so the encoder can find the ID
            try {
                // Get the DirectionData for CLIENTBOUND GAME protocol
                Object directionData = getDirectionData(player);
                if (directionData != null) {
                    // Get the protocols map (Int2ObjectMap or TIntObjectMap)
                    java.lang.reflect.Field protocolsField = directionData.getClass().getDeclaredField("protocols");
                    protocolsField.setAccessible(true);
                    Object protocols = protocolsField.get(directionData);

                    // Get protocolData for this version
                    Method getMethod = null;
                    for (Method m : protocols.getClass().getMethods()) {
                        if (m.getName().equals("get") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                            getMethod = m;
                            break;
                        }
                    }
                    if (getMethod == null) {
                        getMethod = protocols.getClass().getMethod("get", Object.class);
                    }

                    Object protocolData;
                    if (getMethod.getParameterTypes()[0] == int.class) {
                        protocolData = getMethod.invoke(protocols, version);
                    } else {
                        protocolData = getMethod.invoke(protocols, Integer.valueOf(version));
                    }

                    if (protocolData != null) {
                        // Register in packetMap
                        java.lang.reflect.Field packetMapField = protocolData.getClass().getDeclaredField("packetMap");
                        packetMapField.setAccessible(true);
                        Object packetMap = packetMapField.get(protocolData);

                        Method putMethod = null;
                        for (Method m : packetMap.getClass().getMethods()) {
                            if (!m.getName().equals("put")) continue;
                            Class<?>[] params = m.getParameterTypes();
                            if (params.length == 2 && params[0].isAssignableFrom(Class.class) &&
                                (params[1] == int.class || params[1] == Integer.class)) {
                                putMethod = m;
                                break;
                            }
                        }

                        if (putMethod != null) {
                            if (putMethod.getParameterTypes()[1] == int.class) {
                                putMethod.invoke(packetMap, rawPacket.getClass(), packetId);
                            } else {
                                putMethod.invoke(packetMap, rawPacket.getClass(), Integer.valueOf(packetId));
                            }

                            // Register constructor
                            java.lang.reflect.Field constructorsField = protocolData.getClass().getDeclaredField("packetConstructors");
                            constructorsField.setAccessible(true);
                            Object constructors = constructorsField.get(protocolData);
                            if (constructors instanceof java.util.function.Supplier[]) {
                                ((java.util.function.Supplier[]) constructors)[packetId] = () -> rawPacket;
                            }

                            player.unsafe().sendPacket(rawPacket);
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[Protocolize] Direct registration failed, trying channel write", e);
            }

            // Final fallback: write directly to channel
            ByteBuf fullPacket = Unpooled.buffer();
            writeVarInt(packetId, fullPacket);
            fullPacket.writeBytes(data);
            if (!data.isReadable()) {
                // data already consumed by the DefinedPacket write above
                packetData.resetReaderIndex();
                fullPacket = Unpooled.buffer();
                writeVarInt(packetId, fullPacket);
                abstractPacket.write(fullPacket, direction, version);
            }

            Channel channel = getChannel(player);
            if (channel != null) {
                channel.writeAndFlush(fullPacket);
            } else {
                fullPacket.release();
            }
        } finally {
            packetData.release();
        }
    }

    private Object getDirectionData(ProxiedPlayer player) {
        try {
            Class<?> protocolClass = Class.forName("net.md_5.bungee.protocol.Protocol");
            java.lang.reflect.Field toClientField = protocolClass.getDeclaredField("TO_CLIENT");
            toClientField.setAccessible(true);
            return toClientField.get(net.md_5.bungee.protocol.Protocol.GAME);
        } catch (Exception e) {
            return null;
        }
    }

    private Channel getChannel(ProxiedPlayer player) {
        try {
            Method getChMethod = player.getClass().getDeclaredMethod("getCh");
            getChMethod.setAccessible(true);
            Object channelWrapper = getChMethod.invoke(player);
            Method getHandleMethod = channelWrapper.getClass().getDeclaredMethod("getHandle");
            getHandleMethod.setAccessible(true);
            return (Channel) getHandleMethod.invoke(channelWrapper);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeVarInt(int value, ByteBuf buf) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    @Override
    public int generateWindowId() {
        int out = windowId.incrementAndGet();
        if (out >= 200) {
            out = 101;
            windowId.set(101);
        }
        return out;
    }

    @Override
    public int protocolVersion() {
        return ReflectionUtil.getProtocolVersion(player());
    }

    @Override
    public <T> T handle() {
        return (T) player();
    }

    @Override
    public void onInteract(Consumer<PlayerInteract> interactConsumer) {
        interactConsumers.add(interactConsumer);
    }

    @Override
    public void handleInteract(PlayerInteract interact) {
        interactConsumers.forEach(interactConsumer -> interactConsumer.accept(interact));
    }

    private ProxiedPlayer player() {
        return ProxyServer.getInstance().getPlayer(uniqueId);
    }

}
