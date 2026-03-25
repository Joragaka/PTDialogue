package com.joragaka.ptdialogue;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * S2C payload: sends a single icon file (PNG) from server to client.
 * Used for syncing custom icons from server's config/ptlore/ptdialogue/ (preferred) or
 * config/ptlore/ptdialogue/ (fallback) and pre-cached player head textures.
 *
 * @param path     relative file path (e.g. "icon.png", "heads/player.png")
 * @param data     raw PNG bytes
 * @param md5      MD5 hash of the data for integrity verification
 */
public record IconSyncPayload(String path, byte[] data, String md5) {

    public static final Identifier ID = new Identifier("ptdialogue", "icon_sync");

    public void write(PacketByteBuf buf) {
        buf.writeString(path == null ? "" : path);
        buf.writeByteArray(data == null ? new byte[0] : data);
        buf.writeString(md5 == null ? "" : md5);
    }

    public static IconSyncPayload read(PacketByteBuf buf) {
        String path = buf.readString();
        byte[] data = buf.readByteArray();
        String md5 = buf.readString();
        return new IconSyncPayload(path, data, md5);
    }

    public PacketByteBuf toBuf() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        write(buf);
        return buf;
    }
}
