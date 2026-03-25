package com.joragaka.ptdialogue;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * C2S payload: client requests the server to re-send a specific icon by path.
 */
public record IconRequestPayload(String path) {
    public static final Identifier ID = new Identifier("ptdialogue", "icon_request");

    public void write(PacketByteBuf buf) {
        buf.writeString(path == null ? "" : path);
    }

    public static IconRequestPayload read(PacketByteBuf buf) {
        return new IconRequestPayload(buf.readString());
    }

    public PacketByteBuf toBuf() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        write(buf);
        return buf;
    }
}
