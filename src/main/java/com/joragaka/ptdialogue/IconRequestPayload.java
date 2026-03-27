package com.joragaka.ptdialogue;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class IconRequestPayload {

    private final String path;

    public IconRequestPayload(String path) {
        this.path = path;
    }

    public String path() { return path; }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(path == null ? "" : path);
    }

    public static IconRequestPayload read(FriendlyByteBuf buf) {
        return new IconRequestPayload(buf.readUtf());
    }

    public static void handle(IconRequestPayload msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Server-side: handle icon request from client
            // Currently no-op; could re-send the requested icon
        });
        ctx.get().setPacketHandled(true);
    }
}
