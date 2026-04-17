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
import lombok.Getter;
import lombok.experimental.Accessors;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.protocol.DefinedPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Date: 26.08.2021
 *
 * @author Exceptionflug
 */
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
                // Fallback: send raw packet bytes directly
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
                return; // Can't send to server without proper registration
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
        if (mapping == null) return;

        // Write packet data to buffer and send via netty channel
        ByteBuf buf = Unpooled.buffer();
        try {
            // Write packet ID as varint
            writeVarInt(mapping.id(), buf);
            // Write packet content
            abstractPacket.write(buf, direction, version);

            // Get the channel from the player and write directly
            java.lang.reflect.Method getChannelWrapper = player.getClass().getMethod("getCh");
            getChannelWrapper.setAccessible(true);
            Object channelWrapper = getChannelWrapper.invoke(player);
            java.lang.reflect.Method getHandle = channelWrapper.getClass().getMethod("getHandle");
            getHandle.setAccessible(true);
            io.netty.channel.Channel channel = (io.netty.channel.Channel) getHandle.invoke(channelWrapper);
            channel.writeAndFlush(buf.retain());
        } catch (Exception e) {
            // ignore
        } finally {
            buf.release();
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
