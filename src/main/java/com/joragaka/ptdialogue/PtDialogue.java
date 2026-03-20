package com.joragaka.ptdialogue;

import net.fabricmc.api.ModInitializer;

public class PtDialogue implements ModInitializer {

    @Override
    public void onInitialize() {
        // Register dialogue command for server
        DialogueCommand.register();
        // Register icon/head sync system
        IconSyncManager.register();
        // Register server-side dialogue history manager
        HistoryManager.register();
    }
}
