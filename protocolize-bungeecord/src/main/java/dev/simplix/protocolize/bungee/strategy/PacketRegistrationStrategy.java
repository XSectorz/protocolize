package dev.simplix.protocolize.bungee.strategy;

/**
 * Date: 21.08.2021
 *
 * @author Exceptionflug
 */
public interface PacketRegistrationStrategy {

    void registerPacket(Object protocols, int protocolVersion, int packetId, Class<?> clazz) throws Exception;

    boolean compatible();

}
