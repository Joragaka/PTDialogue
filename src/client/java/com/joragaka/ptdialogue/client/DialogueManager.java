package com.joragaka.ptdialogue.client;

import net.minecraft.text.Text;

public class DialogueManager {
    private static String currentIcon = null;
    private static String currentName = null;
    private static int currentNameColor = 0xFFFFFF;
    private static Text currentMessage = null;
    private static long startTime = 0;
    private static final long DISPLAY_DURATION = 5000; // 5 seconds in milliseconds
    private static final long FADE_OUT_DURATION = 500; // 0.5 seconds fade out

    /** Cached alpha, recalculated once per frame via {@link #tick()}. */
    private static float cachedAlpha = 0;

    public static void showDialogue(String icon, String name, int nameColor, Text message) {
        currentIcon = icon;
        currentName = name;
        currentNameColor = nameColor;
        currentMessage = message;
        startTime = System.currentTimeMillis();
        cachedAlpha = 1.0f;
    }

    public static String getIcon() {
        return currentIcon;
    }

    public static String getName() {
        return currentName;
    }

    public static int getNameColor() {
        return currentNameColor;
    }

    public static Text getCurrentDialogue() {
        return currentMessage;
    }

    public static float getAlpha() {
        return cachedAlpha;
    }

    /**
     * Call once per frame (before rendering) to update alpha and clear expired dialogues.
     * Avoids recalculating alpha multiple times per frame with side effects.
     */
    public static void tick() {
        if (currentMessage == null) {
            cachedAlpha = 0;
            return;
        }

        long elapsed = System.currentTimeMillis() - startTime;

        if (elapsed > DISPLAY_DURATION) {
            long fadeElapsed = elapsed - DISPLAY_DURATION;
            if (fadeElapsed >= FADE_OUT_DURATION) {
                currentMessage = null;
                currentIcon = null;
                currentName = null;
                cachedAlpha = 0;
            } else {
                cachedAlpha = 1.0f - (fadeElapsed / (float) FADE_OUT_DURATION);
            }
        } else {
            cachedAlpha = 1.0f;
        }
    }

    public static boolean hasDialogue() {
        return currentMessage != null && cachedAlpha > 0;
    }
}
