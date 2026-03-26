package com.joragaka.ptdialogue;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.text.Text;

public class PtDialogue implements ModInitializer {

    @Override
    public void onInitialize() {
        // Register dialogue command for server
        DialogueCommand.register();
        // Register icon/head sync system
        IconSyncManager.register();
        // Register server-side dialogue history manager
        HistoryManager.register();

        // Require PTDialogue mod on the client — disconnect players who don't have it
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.getPlayer();
            server.execute(() -> {
                if (player.isDisconnected()) return;
                if (!ServerPlayNetworking.canSend(player, DialoguePayload.ID)) {
                    player.networkHandler.disconnect(
                            Text.literal("This server requires the PTDialogue mod. Please install it to join.")
                    );
                }
            });
        });
    }
}
