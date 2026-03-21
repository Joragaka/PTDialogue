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
                            parseJsonToText(e.message()), e.timestamp());
                }
            });
        });
    }

    public static Text parseJsonToText(String jsonText) {
        if (jsonText == null || jsonText.isEmpty()) return Text.literal("");

        // 1) Try Minecraft's built-in TextCodecs (supports font, clickEvent, hoverEvent, all styles)
        try {
            com.google.gson.JsonElement element = JsonParser.parseString(jsonText);
            var result = net.minecraft.text.TextCodecs.CODEC.parse(
                    com.mojang.serialization.JsonOps.INSTANCE, element);
            var opt = result.result();
            if (opt.isPresent()) return opt.get();
        } catch (Exception ignored) {
            // JSON may be malformed/truncated — fall through
        }

        // 2) Try manual parse as fallback (handles truncated JSON)
        try {
            com.google.gson.JsonElement element = JsonParser.parseString(jsonText);
            if (element.isJsonArray()) {
                var array = element.getAsJsonArray();
                if (array.isEmpty()) return Text.literal("");
                MutableText result = null;
                for (var item : array) {
                    Text component = parseComponent(item);
                    if (result == null) result = component.copy(); else result.append(component);
                }
                return result != null ? result : Text.literal("");
            }
            return parseComponent(element);
        } catch (Exception ignored) {
            // fall through to recovery
        }

        // 3) Recovery: try closing the truncated JSON array at the last complete object
        if (jsonText.startsWith("[")) {
            int lastObj = jsonText.lastIndexOf('}');
            if (lastObj >= 0) {
                String candidate = jsonText.substring(0, lastObj + 1) + "]";
                // Try TextCodecs first for the recovered candidate
                try {
                    com.google.gson.JsonElement el = JsonParser.parseString(candidate);
                    var result = net.minecraft.text.TextCodecs.CODEC.parse(
                            com.mojang.serialization.JsonOps.INSTANCE, el);
                    var opt = result.result();
                    if (opt.isPresent()) {
                        MutableText parsed = opt.get().copy();
                        String tail = jsonText.substring(lastObj + 1).replaceAll("^[,\\]\\s]+", "");
                        if (!tail.isEmpty()) parsed.append(extractReadableText(tail));
                        return parsed;
                    }
                } catch (Exception ignored) {}

                // Fallback: manual parse for recovered candidate
                try {
                    com.google.gson.JsonElement el = JsonParser.parseString(candidate);
                    if (el.isJsonArray()) {
                        MutableText result = Text.empty().copy();
                        for (var item : el.getAsJsonArray()) {
                            result.append(parseComponent(item));
                        }
                        String tail = jsonText.substring(lastObj + 1).replaceAll("^[,\\]\\s]+", "");
                        if (!tail.isEmpty()) result.append(extractReadableText(tail));
                        return result;
                    }
                } catch (Exception ignored) {}
            }

            // 4) Extract readable text from the entire truncated JSON string
            Text extracted = extractReadableText(jsonText);
            if (!extracted.getString().isEmpty()) return extracted;
        }

        // 5) Final fallback — show as plain literal
        return Text.literal(jsonText);
    }

    /**
     * Extract readable text from a truncated JSON fragment.
     * Pulls out "text":"..." values and strips all remaining JSON syntax,
     * keeping only non-JSON trailing text (e.g. Cyrillic appended after truncation).
     */
    private static Text extractReadableText(String fragment) {
        MutableText result = Text.empty().copy();

        // Extract all "text":"value" pairs
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"text\"\\s*:\\s*\"([^\"]*?)\"")
                .matcher(fragment);
        int lastMatchEnd = 0;
        while (m.find()) {
            result.append(Text.literal(m.group(1)));
            lastMatchEnd = m.end();
        }

        // Strip ALL JSON syntax characters from the remainder, keep only human-readable text.
        // JSON syntax: { } [ ] " : , and keywords like true/false/null and field names like "bold","color" etc.
        String remainder = fragment.substring(lastMatchEnd);
        // Remove all JSON key-value patterns (e.g. "bold":true, "color":"#5543bc")
        remainder = remainder.replaceAll("\"[a-zA-Z_]+\"\\s*:\\s*(\"[^\"]*\"|true|false|null|\\d+)", "");
        // Remove remaining JSON structural characters and quotes
        remainder = remainder.replaceAll("[{}\\[\\]\",:\\s]+", "");
        // Remove leftover JSON keywords
        remainder = remainder.replaceAll("\\b(true|false|null)\\b", "");
        remainder = remainder.trim();

        if (!remainder.isEmpty()) {
            result.append(Text.literal(remainder));
        }

        // If nothing was extracted at all, return the fragment as-is
        if (result.getString().isEmpty()) return Text.literal(fragment);
        return result;
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


    /**
     * Build the name prefix Text: [Name]:
     * If name looks like JSON (starts with [ or {), parse it via TextCodecs/parseJsonToText.
     * If nameColor == 0, don't apply forced color (let JSON components use their own colors).
     * Otherwise, apply nameColor to the name part.
     *
     * Brackets inherit the full style of the nearest visible glyph:
     * '[' gets the style of the first glyph, ']' gets the style of the last glyph.
     */
    public static MutableText buildNamePrefix(String name, int nameColor) {
        boolean isJson = name != null && (name.trim().startsWith("[") || name.trim().startsWith("{"));
        Text nameContent;
        if (isJson) {
            nameContent = parseJsonToText(name);
        } else {
            nameContent = Text.literal(name != null ? name : "");
        }

        // Build the name part with color applied
        MutableText coloredName;
        if (nameColor != 0) {
            coloredName = Text.empty().copy().append(nameContent).styled(s -> s.withColor(nameColor));
        } else {
            coloredName = Text.empty().copy().append(nameContent);
        }

        // Extract only the color of the first and last visible glyph
        // Brackets inherit ONLY color, not other styles (bold, italic, font, etc.)
        int[] firstColor = {0xFFFFFF};
        int[] lastColor = {0xFFFFFF};
        boolean[] foundFirst = {false};

        coloredName.asOrderedText().accept((index, style, codePoint) -> {
            int color = style.getColor() != null ? style.getColor().getRgb() : 0xFFFFFF;
            if (!foundFirst[0]) {
                firstColor[0] = color;
                foundFirst[0] = true;
            }
            lastColor[0] = color;
            return true;
        });

        // Build: [Name]: with brackets inheriting only the nearest glyph's color
        MutableText result = Text.empty().copy();
        result.append(Text.literal("[").styled(s -> s.withColor(firstColor[0])));
        result.append(coloredName);
        result.append(Text.literal("]").styled(s -> s.withColor(lastColor[0])));
        result.append(Text.literal(": "));
        return result;
    }

    static int parseColor(String colorName) {
        // "0" means no color — let JSON components handle their own colors
        if ("0".equals(colorName)) return 0;

        String hex = (colorName == null) ? "" : (colorName.startsWith("#") ? colorName.substring(1) : colorName);

        if (hex.length() == 6) {
            try { return Integer.parseInt(hex, 16); } catch (NumberFormatException ignored) {}
        }
        if (hex.length() == 3) {
            try {
                char[] c = hex.toCharArray();
                return Integer.parseInt("" + c[0] + c[0] + c[1] + c[1] + c[2] + c[2], 16);
            } catch (NumberFormatException ignored) {}
        }

        return NAMED_COLORS.getOrDefault(colorName == null ? "" : colorName.toLowerCase(), 0xFFFFFF);
    }

}
