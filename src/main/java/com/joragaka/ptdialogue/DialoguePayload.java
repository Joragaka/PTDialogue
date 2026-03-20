package com.joragaka.ptdialogue;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record DialoguePayload(String icon, String name, String colorname, String message) implements CustomPayload {

    public static final CustomPayload.Id<DialoguePayload> ID = new CustomPayload.Id<>(Identifier.of("ptdialogue", "dialogue"));

    public static final PacketCodec<PacketByteBuf, DialoguePayload> CODEC = PacketCodec.of(
        (payload, buf) -> {
            buf.writeString(payload.icon());
            buf.writeString(payload.name());
            buf.writeString(payload.colorname());
            buf.writeString(payload.message());
        },
        buf -> new DialoguePayload(buf.readString(), buf.readString(), buf.readString(), buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
