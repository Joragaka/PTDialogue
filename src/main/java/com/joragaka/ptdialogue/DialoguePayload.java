package com.joragaka.ptdialogue;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * Server->Client payload: dialogue message.
 * Используем ручную сериализацию через PacketByteBuf для Fabric 1.20.1.
 */
public record DialoguePayload(String icon, String name, String colorname, String message, String skinUuid) {

    public static final Identifier ID = new Identifier("ptdialogue", "dialogue");

    public void write(PacketByteBuf buf) {
        buf.writeString(icon == null ? "" : icon);
        buf.writeString(name == null ? "" : name);
        buf.writeString(colorname == null ? "" : colorname);
        buf.writeString(message == null ? "" : message);
        buf.writeString(skinUuid == null ? "" : skinUuid);
    }

    public static DialoguePayload read(PacketByteBuf buf) {
        String icon = buf.readString();
        String name = buf.readString();
        String colorname = buf.readString();
        String message = buf.readString();
        String skin = buf.readString();
        String skinUuid = (skin == null || skin.isEmpty()) ? null : skin;
        return new DialoguePayload(icon, name, colorname, message, skinUuid);
    }

    public PacketByteBuf toBuf() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        write(buf);
        return buf;
    }
}
