package com.joragaka.ptdialogue.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import com.joragaka.ptdialogue.DialoguePayload;
import com.joragaka.ptdialogue.HistorySyncPayload;

import java.util.Map;

public class DialoguePacketHandler {

    private static final Map<String, Integer> NAMED_COLORS = Map.ofEntries(
            Map.entry("black",        0x000000),
            Map.entry("dark_blue",    0x0000AA),
            Map.entry("dark_green",   0x00AA00),
            Map.entry("dark_aqua",    0x00AAAA),
            Map.entry("dark_red",     0xAA0000),
            Map.entry("dark_purple",  0xAA00AA),
            Map.entry("gold",         0xFFAA00),
            Map.entry("gray",         0xAAAAAA),
            Map.entry("dark_gray",    0x555555),
            Map.entry("blue",         0x5555FF),
            Map.entry("green",        0x55FF55),
            Map.entry("aqua",         0x55FFFF),
            Map.entry("red",          0xFF5555),
            Map.entry("light_purple", 0xFF55FF),
            Map.entry("yellow",       0xFFFF55),
            Map.entry("white",        0xFFFFFF)
    );

    public static void register() {
        // Получение диалога — показываем на экране (история хранится на сервере)
        ClientPlayNetworking.registerGlobalReceiver(DialoguePayload.ID, (payload, context) -> {
            String icon = payload.icon();
            String name = payload.name();
            String colorname = payload.colorname();
            String message = payload.message();

            SkinCache.preload(icon);

            context.client().execute(() -> {
                int nameColor = parseColor(colorname);
                Text messageText = parseJsonToText(message);
                if (messageText != null) {
                    DialogueManager.showDialogue(icon, name, nameColor, messageText);
                }
            });
        });

        // Получение истории с сервера
        ClientPlayNetworking.registerGlobalReceiver(HistorySyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (payload.fullSync()) {
                    // Полная синхронизация при входе — заменяем историю
                    DialogueHistory.loadFromServer(payload.entries());
                } else if (!payload.entries().isEmpty()) {
                    // Инкрементальная — добавляем одну запись
                    var e = payload.entries().get(0);
                    DialogueHistory.appendFromServer(e.icon(), e.name(), e.color(),
                            Text.literal(e.message()), e.timestamp());
                }
            });
        });
    }

    private static Text parseJsonToText(String jsonText) {
        try {
            JsonElement element = JsonParser.parseString(jsonText);

            if (element.isJsonArray()) {
                var array = element.getAsJsonArray();
                if (array.isEmpty()) return Text.literal("");

                MutableText result = null;
                for (var item : array) {
                    Text component = parseComponent(item);
                    if (result == null) {
                        result = component.copy();
                    } else {
                        result.append(component);
                    }
                }
                return result != null ? result : Text.literal("");
            }

            return parseComponent(element);
        } catch (Exception e) {
            return Text.literal(jsonText);
        }
    }

    private static Text parseComponent(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return Text.literal(element.getAsString());
        }

        if (element.isJsonObject()) {
            var obj = element.getAsJsonObject();
            if (obj.has("text")) {
                MutableText mutable = Text.literal(obj.get("text").getAsString());

                if (obj.has("color")) {
                    int color = parseColor(obj.get("color").getAsString());
                    mutable.styled(s -> s.withColor(color));
                }
                if (obj.has("bold") && obj.get("bold").getAsBoolean())
                    mutable.styled(s -> s.withBold(true));
                if (obj.has("italic") && obj.get("italic").getAsBoolean())
                    mutable.styled(s -> s.withItalic(true));
                if (obj.has("underlined") && obj.get("underlined").getAsBoolean())
                    mutable.styled(s -> s.withUnderline(true));
                if (obj.has("strikethrough") && obj.get("strikethrough").getAsBoolean())
                    mutable.styled(s -> s.withStrikethrough(true));
                if (obj.has("obfuscated") && obj.get("obfuscated").getAsBoolean())
                    mutable.styled(s -> s.withObfuscated(true));
                if (obj.has("extra") && obj.get("extra").isJsonArray()) {
                    for (var extra : obj.get("extra").getAsJsonArray()) {
                        mutable.append(parseComponent(extra));
                    }
                }
                return mutable;
            }
        }

        return Text.literal(element.toString());
    }

    static int parseColor(String colorName) {
        String hex = colorName.startsWith("#") ? colorName.substring(1) : colorName;

        if (hex.length() == 6) {
            try { return Integer.parseInt(hex, 16); } catch (NumberFormatException ignored) {}
        }
        if (hex.length() == 3) {
            try {
                char[] c = hex.toCharArray();
                return Integer.parseInt("" + c[0] + c[0] + c[1] + c[1] + c[2] + c[2], 16);
            } catch (NumberFormatException ignored) {}
        }

        return NAMED_COLORS.getOrDefault(colorName.toLowerCase(), 0xFFFFFF);
    }
}
