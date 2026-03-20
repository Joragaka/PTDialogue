package com.joragaka.ptdialogue;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C payload: sends a single icon file (PNG) from server to client.
 * Used for syncing custom icons from server's config/ptloreicons/ folder
 * and pre-cached player head textures.
 *
 * @param path     relative file path (e.g. "icon.png", "heads/player.png")
 * @param data     raw PNG bytes
 * @param md5      MD5 hash of the data for integrity verification
 */
public record IconSyncPayload(String path, byte[] data, String md5) implements CustomPayload {

    public static final Id<IconSyncPayload> ID =
            new Id<>(Identifier.of("ptdialogue", "icon_sync"));

    public static final PacketCodec<PacketByteBuf, IconSyncPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeString(payload.path());
                buf.writeByteArray(payload.data());
                buf.writeString(payload.md5());
            },
            buf -> new IconSyncPayload(buf.readString(), buf.readByteArray(), buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

