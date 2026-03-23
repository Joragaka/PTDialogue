package com.joragaka.ptdialogue;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C packet: server sends the full dialogue history for a player.
 */
public record HistorySyncPayload(List<Entry> entries, boolean fullSync) implements CustomPayload {

    public static final CustomPayload.Id<HistorySyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of("ptdialogue", "history_sync"));

    // Add optional skinUuid to history entries so clients can use exact skin reference
    public record Entry(String icon, String name, int color, String message, long timestamp, String skinUuid) {}

    public static final PacketCodec<PacketByteBuf, HistorySyncPayload> CODEC = PacketCodec.of(
        (payload, buf) -> {
            buf.writeBoolean(payload.fullSync());
            buf.writeInt(payload.entries().size());
            for (Entry e : payload.entries()) {
                buf.writeString(e.icon());
                buf.writeString(e.name());
                buf.writeInt(e.color());
                buf.writeString(e.message());
                buf.writeLong(e.timestamp());
                buf.writeString(e.skinUuid() == null ? "" : e.skinUuid());
            }
        },
        buf -> {
            boolean fullSync = buf.readBoolean();
            int count = buf.readInt();
            List<Entry> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String icon  = buf.readString();
                String name  = buf.readString();
                int    color = buf.readInt();
                String msg   = buf.readString();
                long   ts    = buf.readLong();
                String skinUuid = buf.readString();
                if (skinUuid.isEmpty()) skinUuid = null;
                list.add(new Entry(icon, name, color, msg, ts, skinUuid));
            }
            return new HistorySyncPayload(list, fullSync);
        }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
