package com.joragaka.ptdialogue.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import com.joragaka.ptdialogue.IconSyncPayload;
import com.joragaka.ptdialogue.IconRequestPayload;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class ptdialogueClient implements ClientModInitializer {

    private static KeyBinding openHistoryKey;
    // Debug: отправить одно сообщение в чат при первом обнаружении смены скина
    private static volatile boolean debugSentChatAfterSkinChange = false;
    // Use a plain string category — KeyBinding.Category may not be present in mappings
    private static final String PTLORE_CATEGORY = "key.ptdialogue.category";

    // Tracks whether Escape was held last tick (to detect fresh press)
    private static boolean escWasDown = false;
    // Simple tick counter for periodic skin polling
    private static int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        // Register packet handler for server-sent dialogue packets
        DialoguePacketHandler.register();

        // Register handler for icon/head sync from server
        IconSyncHandler.register();

        // Register HUD rendering — call our renderer without RenderTickCounter mismatch
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            try { DialogueRenderer.renderDialogue(drawContext); } catch (Throwable ignored) {}
        });

        // Register keybinding to open dialogue history screen (default: H)
        openHistoryKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ptdialogue.openHistory",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                PTLORE_CATEGORY
        ));

        // Open history screen on key press; refresh skin on Escape
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Open history screen
            while (openHistoryKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new DialogueHistoryScreen());
                }
            }

            // Refresh local player head on Escape press (fresh press only, not held)
            try {
                long handle = client.getWindow().getHandle();
                boolean escDown = InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_ESCAPE);
                if (escDown && !escWasDown) {
                    refreshLocalPlayerHead(client);
                }
                escWasDown = escDown;
            } catch (Throwable ignored) {}

            // Periodically poll the local player's in-memory skin (every N ticks)
            try {
                tickCounter = (tickCounter + 1) % 10; // every 10 ticks (~0.5s)
                if (tickCounter == 0) {
                    pollLocalPlayerSkin(client);
                }
            } catch (Throwable ignored) {}
        });

        // On disconnect: clear local history cache (server already has it saved)
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DialogueHistory.onDisconnect();
        });
    }

    /** Force-refresh the local player's composited head texture. */
    static void refreshLocalPlayerHead(net.minecraft.client.MinecraftClient client) {
        try {
            if (client == null || client.player == null || client.getNetworkHandler() == null) return;
            String localName = client.player.getGameProfile().getName();
            if (localName == null) return;
            var ple = client.getNetworkHandler().getPlayerListEntry(localName);
            if (ple == null) return;
            // Do NOT force-clear the existing cache first — that causes an immediate fallback
            // to PlayerSkinDrawer (large single-layer icon) while we recompute. Instead, attempt
            // to composite/register the head from the already-loaded PlayerListEntry first so the
            // composited texture is available atomically. If needed, the SkinCache pipeline will
            // update later.
            SkinCache.compositeHeadImmediately(ple, localName);
            SkinCache.tryRegisterHeadFromPlayerListEntry(ple, localName);

            // Additionally, try to extract any Identifier(s) directly from the SkinTextures
            // and ask SkinCache to register them immediately (covers in-memory NativeImageBackedTexture).
            Object st = null;
            try { java.lang.reflect.Method _gm = ple.getClass().getMethod("getSkinTextures"); st = _gm.invoke(ple); } catch (Throwable ignored) {}
            if (st != null) {
                for (var m : st.getClass().getMethods()) {
                    try {
                        if (m.getParameterCount() != 0) continue;
                        Object val = m.invoke(st);
                        if (val == null) continue;
                        if (val instanceof net.minecraft.util.Identifier) {
                            try { SkinCache.tryRegisterHeadFromTexture((net.minecraft.util.Identifier) val, localName); } catch (Throwable ignored) {}
                        } else if (val instanceof String) {
                            String s = (String) val;
                            if (s.startsWith("http")) try { SkinCache.tryRegisterHeadFromDirectUrl(s, localName); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // If the composite/registration didn't happen synchronously, decide whether to force-refresh
            // (delete old texture immediately) or do a preserved async refresh. We force-refresh when
            // the in-memory NativeImage hash or overlay presence differs from last-known values.
            try {
                // New: try to read skin texture location directly from client.player (preferred), then fallback to SkinTextures
                String currentLoc = null;
                try {
                    // Try direct player accessor first (some mappings expose getSkinTextureLocation on AbstractClientPlayer)
                    try {
                        if (client != null && client.player != null) {
                            try {
                                java.lang.reflect.Method pm = client.player.getClass().getMethod("getSkinTextureLocation");
                                Object pv = pm.invoke(client.player);
                                if (pv != null) currentLoc = pv.toString();
                            } catch (NoSuchMethodException ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    // Fallback: probe SkinTextures from PlayerListEntry
                    if (currentLoc == null && st != null) currentLoc = SkinCache.probeSkinTextureLocationFromObject(st);
                } catch (Throwable ignored) {}

                String lastLoc = SkinCache.getLastKnownSkinTextureLocation(localName);
                boolean locChanged = (currentLoc != null && !currentLoc.equals(lastLoc)) || (currentLoc == null && lastLoc != null);

                if (locChanged) {
                    try {
                        // update stored location immediately so repeated calls don't retrigger
                        SkinCache.setLastKnownSkinTextureLocation(localName, currentLoc);
                        SkinCache.forceRefresh(localName);
                        // Debug helper: show one local chat line once when skin changed (safe, compiles)
                        try {
                            if (!debugSentChatAfterSkinChange && client != null && client.player != null) {
                                debugSentChatAfterSkinChange = true;
                                try {
                                    String pname = client.player.getGameProfile().getName();
                                    net.minecraft.text.Text msg = net.minecraft.text.Text.literal("<" + pname + "> I changed my skin!");
                                    client.inGameHud.getChatHud().addMessage(msg);
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                } else {
                    // Otherwise do a gentle async refresh that preserves current texture until new one registers
                    try {
                        if (SkinCache.getHeadTextureId(localName) == null) SkinCache.requestHeadRefreshPreserveCurrent(localName);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** Periodically check whether the local player's in-memory skin changed and refresh head if needed. */
    private static void pollLocalPlayerSkin(net.minecraft.client.MinecraftClient client) {
        try {
            if (client == null || client.player == null || client.getNetworkHandler() == null) return;
            String localName = null;
            try { localName = client.player.getGameProfile().getName(); } catch (Throwable ignored) {}
            if (localName == null) return;
            var ple = client.getNetworkHandler().getPlayerListEntry(localName);
            if (ple == null) return;

            // Probe for an in-memory NativeImage hash inside PlayerListEntry / SkinTextures
            String currentLoc = null;
            try {
                // Try client.player accessor first
                try {
                    if (client != null && client.player != null) {
                        try {
                            java.lang.reflect.Method pm = client.player.getClass().getMethod("getSkinTextureLocation");
                            Object pv = pm.invoke(client.player);
                            if (pv != null) currentLoc = pv.toString();
                        } catch (NoSuchMethodException ignored) {}
                    }
                } catch (Throwable ignored) {}
                // Fallback: probe SkinTextures from PlayerListEntry
                if (currentLoc == null) {
                    Object st = null;
                    try { java.lang.reflect.Method _gm = ple.getClass().getMethod("getSkinTextures"); st = _gm.invoke(ple); } catch (Throwable ignored) {}
                    if (st != null) currentLoc = SkinCache.probeSkinTextureLocationFromObject(st);
                }
            } catch (Throwable ignored) {}

            String lastKnown = SkinCache.getLastKnownSkinTextureLocation(localName);
            if (currentLoc != null && !currentLoc.equals(lastKnown)) {
                // skin texture location changed in memory → update stored location and try immediate registration
                try { SkinCache.setLastKnownSkinTextureLocation(localName, currentLoc); } catch (Throwable ignored) {}
                try { SkinCache.tryRegisterHeadFromPlayerListEntry(ple, localName); } catch (Throwable ignored) {}
                // Also try to find explicit Identifier(s) inside SkinTextures
                try {
                    Object st = null;
                    try { java.lang.reflect.Method _gm = ple.getClass().getMethod("getSkinTextures"); st = _gm.invoke(ple); } catch (Throwable ignored) {}
                    if (st != null) {
                        for (var m : st.getClass().getMethods()) {
                            try {
                                if (m.getParameterCount() != 0) continue;
                                Object val = m.invoke(st);
                                if (val == null) continue;
                                if (val instanceof net.minecraft.util.Identifier) {
                                    try { SkinCache.tryRegisterHeadFromTexture((net.minecraft.util.Identifier) val, localName); } catch (Throwable ignored) {}
                                } else if (val instanceof String) {
                                    String s = (String) val;
                                    if (s.startsWith("http")) try { SkinCache.tryRegisterHeadFromDirectUrl(s, localName); } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }
            // Also detect overlay change (optional) by probing NativeImage overlay presence
            Boolean currentOverlay = null;
            try {
                Object st = null;
                try { java.lang.reflect.Method _gm = ple.getClass().getMethod("getSkinTextures"); st = _gm.invoke(ple); } catch (Throwable ignored) {}
                if (st != null) currentOverlay = SkinCache.probeNativeImageHasOverlayFromObject(st);
            } catch (Throwable ignored) {}

            boolean overlayChanged = false;
            try {
                if (currentOverlay != null) {
                    boolean knownOverlay = SkinCache.headHasOverlay(localName);
                    overlayChanged = currentOverlay.booleanValue() != knownOverlay;
                }
            } catch (Throwable ignored) {}

            if (overlayChanged) {
                // Простая и надёжная логика: удаляем старую иконку и триггерим загрузку новой
                try {
                    SkinCache.forceRefresh(localName);
                    // Debug helper: show one local chat line once when skin changed (safe, compiles)
                    try {
                        if (!debugSentChatAfterSkinChange && client != null && client.player != null) {
                            debugSentChatAfterSkinChange = true;
                            try {
                                String pname = client.player.getGameProfile().getName();
                                net.minecraft.text.Text msg = net.minecraft.text.Text.literal("<" + pname + "> I changed my skin!");
                                client.inGameHud.getChatHud().addMessage(msg);
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}