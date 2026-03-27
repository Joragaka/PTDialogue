package com.joragaka.ptdialogue;

import com.joragaka.ptdialogue.client.DialoguePacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class DialoguePayload {

    private final String icon;
    private final String name;
    private final String colorname;
    private final String message;
    private final String skinUuid;

    public DialoguePayload(String icon, String name, String colorname, String message, String skinUuid) {
        this.icon = icon;
        this.name = name;
        this.colorname = colorname;
        this.message = message;
        this.skinUuid = skinUuid;
    }

    public String icon() { return icon; }
    public String name() { return name; }
    public String colorname() { return colorname; }
    public String message() { return message; }
    public String skinUuid() { return skinUuid; }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(icon == null ? "" : icon);
        buf.writeUtf(name == null ? "" : name);
        buf.writeUtf(colorname == null ? "" : colorname);
        buf.writeUtf(message == null ? "" : message);
        buf.writeUtf(skinUuid == null ? "" : skinUuid);
    }

    public static DialoguePayload read(FriendlyByteBuf buf) {
        String icon = buf.readUtf();
        String name = buf.readUtf();
        String colorname = buf.readUtf();
        String message = buf.readUtf();
        String skin = buf.readUtf();
        String skinUuid = (skin == null || skin.isEmpty()) ? null : skin;
        return new DialoguePayload(icon, name, colorname, message, skinUuid);
    }

    public static void handle(DialoguePayload msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DialoguePacketHandler.handleDialogue(msg));
        });
        ctx.get().setPacketHandled(true);
    }
}
