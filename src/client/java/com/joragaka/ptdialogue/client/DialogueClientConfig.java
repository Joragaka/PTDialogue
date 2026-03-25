package com.joragaka.ptdialogue.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side configuration for the dialogue UI.
 * Stored in config/ptdialogue/dialogue_config.json
 */
public class DialogueClientConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Distance in pixels from screen center to dialogue box center. Default 150. */
    private static int distanceFromCenter = 150;

    /**
     * Window scale value stored in config. Default 1.
     */
    private static float windowScale = 1.0f;

    /** Edge padding as fraction of screen width before text wraps. Default 0.20 (20%). */
    private static float edgePadding = 0.3f;

    /** Vertical padding as fraction of screen height used by history screen (top+bottom). Default 0.075 (~7.5%). */
    private static float verticalPaddingFraction = 0.075f;

    private static boolean loaded = false;

    public static int getDistanceFromCenter() {
        ensureLoaded();
        return distanceFromCenter;
    }

    /**
     * Returns the window scale. What you set is what you get.
     */
    public static float getWindowScale() {
        ensureLoaded();
        return windowScale;
    }

    public static float getEdgePadding() {
        ensureLoaded();
        return edgePadding;
    }

    public static float getVerticalPaddingFraction() {
        ensureLoaded();
        return verticalPaddingFraction;
    }

    public static void setDistanceFromCenter(int value) {
        distanceFromCenter = value;
    }

    public static void setWindowScale(float value) {
        windowScale = value;
    }

    public static void setEdgePadding(float value) {
        edgePadding = value;
    }

    public static void setVerticalPaddingFraction(float value) {
        verticalPaddingFraction = value;
    }

    /**
     * Alias for getWindowScale() — kept for compatibility.
     */
    public static float getStoredWindowScale() {
        return getWindowScale();
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
            loaded = true;
        }
    }

    public static void reload() {
        loaded = false;
        load();
    }

    public static void load() {
        Path configFile = getConfigPath();
        loaded = true;
        if (!Files.exists(configFile)) {
            save(); // Create default config
            return;
        }

        try {
            String json = Files.readString(configFile);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            if (obj.has("distanceFromCenter")) {
                distanceFromCenter = obj.get("distanceFromCenter").getAsInt();
            }

            if (obj.has("windowScale")) {
                windowScale = obj.get("windowScale").getAsFloat();
            }

            if (obj.has("edgePadding")) {
                edgePadding = obj.get("edgePadding").getAsFloat();
            }

            if (obj.has("verticalPaddingFraction")) {
                verticalPaddingFraction = obj.get("verticalPaddingFraction").getAsFloat();
            }

        } catch (Exception e) {
            save(); // Recreate with defaults
        }
    }

    public static void save() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("distanceFromCenter", distanceFromCenter);
            obj.addProperty("windowScale", windowScale);
            obj.addProperty("edgePadding", edgePadding);
            obj.addProperty("verticalPaddingFraction", verticalPaddingFraction);

            Path configFile = getConfigPath();
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, GSON.toJson(obj));
        } catch (Exception e) {
        }
    }

    private static Path getConfigPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir()
                .resolve("ptdialogue")
                .resolve("dialogue_config.json");
    }
}
