package com.joragaka.ptdialogue;

import net.minecraft.util.Identifier;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * C2S payload: client requests the server to re-send a specific icon by path.
 */
public record IconRequestPayload(String path) implements CustomPayload {
    public static final Id<IconRequestPayload> ID = new Id<>(Identifier.of("ptdialogue", "icon_request"));

    public static final PacketCodec<PacketByteBuf, IconRequestPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.path()),
            buf -> new IconRequestPayload(buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

