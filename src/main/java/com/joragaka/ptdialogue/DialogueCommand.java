package com.joragaka.ptdialogue;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;

public class DialogueCommand {

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

    private static int parseColor(String colorname) {
        String lower = colorname.trim().toLowerCase();
        if (NAMED_COLORS.containsKey(lower)) return NAMED_COLORS.get(lower);
        try {
            String hex = lower.startsWith("#") ? lower.substring(1) : lower;
            return (int) Long.parseLong(hex, 16);
        } catch (NumberFormatException ignored) {}
        return 0xFFFFFF;
    }

    public static void register() {
        // Регистрируем payload тип для S2C
        PayloadTypeRegistry.playS2C().register(DialoguePayload.ID, DialoguePayload.CODEC);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /dialogue <targets> <icon> <name> <colorname> <message...>
            dispatcher.register(
                CommandManager.literal("dialogue")
                    .requires(source -> source.getServer() != null)
                    .then(
                        CommandManager.argument("targets", EntityArgumentType.players())
                            .then(
                                CommandManager.argument("icon", StringArgumentType.string())
                                    .then(
                                        CommandManager.argument("name", StringArgumentType.string())
                                            .then(
                                                CommandManager.argument("colorname", StringArgumentType.word())
                                                    .then(
                                                        CommandManager.argument("message", StringArgumentType.greedyString())
                                                            .executes(DialogueCommand::executeDialogue)
                                                    )
                                            )
                                    )
                            )
                    )
            );
        });
    }

    private static int executeDialogue(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        var targets = EntityArgumentType.getPlayers(context, "targets");
        String icon      = StringArgumentType.getString(context, "icon");
        String name      = StringArgumentType.getString(context, "name");
        String colorname = StringArgumentType.getString(context, "colorname");
        String message   = StringArgumentType.getString(context, "message");

        // Убрана прежняя валидация, теперь имя может содержать кириллицу и быть в кавычках

        for (PlayerEntity player : targets) {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                // Normalize icon (strip surrounding quotes) but do NOT expand @s here.
                String iconToSend = icon;
                if (iconToSend != null) {
                    iconToSend = iconToSend.trim();
                    if (iconToSend.length() >= 2 && iconToSend.startsWith("\"") && iconToSend.endsWith("\"")) {
                        iconToSend = iconToSend.substring(1, iconToSend.length() - 1);
                    }
                }

                // Normalize name: allow quoted names and support @s replacement per-recipient
                String nameToSend = name == null ? "" : name.trim();
                if (nameToSend.length() >= 2 && ((nameToSend.startsWith("\"") && nameToSend.endsWith("\"")) || (nameToSend.startsWith("'") && nameToSend.endsWith("'")))) {
                    nameToSend = nameToSend.substring(1, nameToSend.length() - 1);
                }
                if ("@s".equals(nameToSend)) {
                    try { nameToSend = serverPlayer.getGameProfile().name(); } catch (Throwable ignored) { nameToSend = ""; }
                }

                // Determine skin UUID to send when icon == @s
                String skinUuid = null;
                if ("@s".equals(iconToSend)) {
                    try { skinUuid = serverPlayer.getUuid().toString(); } catch (Throwable ignored) { skinUuid = null; }
                }

                // If icon is @s and the server has a cached head PNG for this player, send it first
                if ("@s".equals(iconToSend)) {
                    try {
                        String targetKey = serverPlayer.getGameProfile().name().toLowerCase();
                        if (IconSyncManager.hasHead(targetKey)) {
                            IconSyncManager.sendHeadToPlayer(targetKey, serverPlayer);
                        } else {
                            // Trigger background caching (non-blocking)
                            IconSyncManager.ensureHeadCached(serverPlayer.getGameProfile().name(), context.getSource().getServer());
                        }
                    } catch (Throwable ignored) {}
                }

                // Send raw icon (may be "@s") and optional skinUuid
                ServerPlayNetworking.send(serverPlayer, new DialoguePayload(iconToSend, nameToSend, colorname, message, skinUuid));
                // Save raw icon into history; include skinUuid so clients can request exact skin
                HistoryManager.record(serverPlayer, context.getSource().getServer(),
                        iconToSend, nameToSend, parseColor(colorname), message, skinUuid);
             }
         }

        context.getSource().sendFeedback(
            () -> Text.translatable("ptdialogue.command.dialogue.sent", targets.size()),
            false
        );
        return Command.SINGLE_SUCCESS;
    }
}
