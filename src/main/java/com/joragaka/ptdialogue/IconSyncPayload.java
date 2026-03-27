package com.joragaka.ptdialogue;

import com.joragaka.ptdialogue.client.IconSyncHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class IconSyncPayload {

    private final String path;
    private final byte[] data;
    private final String md5;

    public IconSyncPayload(String path, byte[] data, String md5) {
        this.path = path;
        this.data = data;
        this.md5 = md5;
    }

    public String path() { return path; }
    public byte[] data() { return data; }
    public String md5() { return md5; }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(path == null ? "" : path);
        buf.writeByteArray(data == null ? new byte[0] : data);
        buf.writeUtf(md5 == null ? "" : md5);
    }

    public static IconSyncPayload read(FriendlyByteBuf buf) {
        String path = buf.readUtf();
        byte[] data = buf.readByteArray();
        String md5 = buf.readUtf();
        return new IconSyncPayload(path, data, md5);
    }

    public static void handle(IconSyncPayload msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> IconSyncHandler.handleIconSync(msg.path(), msg.data(), msg.md5()));
        });
        ctx.get().setPacketHandled(true);
    }
}
