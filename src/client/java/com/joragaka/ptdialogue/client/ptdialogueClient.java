package com.joragaka.ptdialogue.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import com.joragaka.ptdialogue.IconSyncPayload;
import com.joragaka.ptdialogue.IconRequestPayload;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class ptdialogueClient implements ClientModInitializer {

    private static KeyBinding openHistoryKey;
    private static final KeyBinding.Category PTLORE_CATEGORY =
            KeyBinding.Category.create(Identifier.of("ptdialogue", "keybinds"));

    @Override
    public void onInitializeClient() {
        // Register packet handler for server-sent dialogue packets
        DialoguePacketHandler.register();

        // Ensure payload types are registered on client (idempotent)
        try {
            PayloadTypeRegistry.playS2C().register(IconSyncPayload.ID, IconSyncPayload.CODEC);
            PayloadTypeRegistry.playC2S().register(IconRequestPayload.ID, IconRequestPayload.CODEC);
        } catch (Throwable ignored) {}

        // Register handler for icon/head sync from server
        IconSyncHandler.register();

        // Register keybinding to open dialogue history screen (default: H)
        openHistoryKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ptdialogue.openHistory",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                PTLORE_CATEGORY
        ));

        // Open history screen on key press
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openHistoryKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new DialogueHistoryScreen());
                }
            }
        });

        // On disconnect: clear local history cache (server already has it saved)
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DialogueHistory.onDisconnect();
        });
    }
}
