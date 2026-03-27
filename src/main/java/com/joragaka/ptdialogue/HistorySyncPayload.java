package com.joragaka.ptdialogue;

import com.joragaka.ptdialogue.client.DialoguePacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class HistorySyncPayload {

    private final List<Entry> entries;
    private final boolean fullSync;

    public record Entry(String icon, String name, int color, String message, long timestamp, String skinUuid) {}

    public HistorySyncPayload(List<Entry> entries, boolean fullSync) {
        this.entries = entries;
        this.fullSync = fullSync;
    }

    public List<Entry> entries() { return entries; }
    public boolean fullSync() { return fullSync; }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(fullSync);
        buf.writeInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.icon());
            buf.writeUtf(e.name());
            buf.writeInt(e.color());
            buf.writeUtf(e.message());
            buf.writeLong(e.timestamp());
            buf.writeUtf(e.skinUuid() == null ? "" : e.skinUuid());
        }
    }

    public static HistorySyncPayload read(FriendlyByteBuf buf) {
        boolean full = buf.readBoolean();
        int count = buf.readInt();
        List<Entry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String icon = buf.readUtf();
            String name = buf.readUtf();
            int color = buf.readInt();
            String msg = buf.readUtf();
            long ts = buf.readLong();
            String skinUuid = buf.readUtf();
            if (skinUuid.isEmpty()) skinUuid = null;
            list.add(new Entry(icon, name, color, msg, ts, skinUuid));
        }
        return new HistorySyncPayload(list, full);
    }

    public static void handle(HistorySyncPayload msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DialoguePacketHandler.handleHistorySync(msg));
        });
        ctx.get().setPacketHandled(true);
    }
}
