package com.joragaka.ptdialogue;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

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

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("dialogue")
                .requires(source -> source.hasPermission(2))
                .then(
                    Commands.argument("targets", EntityArgument.players())
                        .then(
                            Commands.argument("icon", StringArgumentType.string())
                                .then(
                                    Commands.argument("name", StringArgumentType.string())
                                        .then(
                                            Commands.argument("colorname", StringArgumentType.word())
                                                .then(
                                                    Commands.argument("message", StringArgumentType.greedyString())
                                                        .executes(DialogueCommand::executeDialogue)
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private static int executeDialogue(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var targets = EntityArgument.getPlayers(context, "targets");
        String icon      = StringArgumentType.getString(context, "icon");
        String name      = StringArgumentType.getString(context, "name");
        String colorname = StringArgumentType.getString(context, "colorname");
        String message   = StringArgumentType.getString(context, "message");

        for (Player player : targets) {
            if (player instanceof ServerPlayer serverPlayer) {
                String iconToSend = icon;
                if (iconToSend != null) {
                    iconToSend = iconToSend.trim();
                    if (iconToSend.length() >= 2 && iconToSend.startsWith("\"") && iconToSend.endsWith("\"")) {
                        iconToSend = iconToSend.substring(1, iconToSend.length() - 1);
                    }
                }

                String nameToSend = name == null ? "" : name.trim();
                if (nameToSend.length() >= 2 && ((nameToSend.startsWith("\"") && nameToSend.endsWith("\"")) || (nameToSend.startsWith("'") && nameToSend.endsWith("'")))) {
                    nameToSend = nameToSend.substring(1, nameToSend.length() - 1);
                }
                if ("@s".equals(nameToSend)) {
                    try { nameToSend = serverPlayer.getGameProfile().getName(); } catch (Throwable ignored) { nameToSend = ""; }
                }

                String skinUuid = null;
                if ("@s".equals(iconToSend)) {
                    try { skinUuid = serverPlayer.getUUID().toString(); } catch (Throwable ignored) { skinUuid = null; }
                }

                if ("@s".equals(iconToSend)) {
                    try {
                        String targetKey = serverPlayer.getGameProfile().getName().toLowerCase();
                        if (IconSyncManager.hasHead(targetKey)) {
                            IconSyncManager.sendHeadToPlayer(targetKey, serverPlayer);
                        } else {
                            IconSyncManager.ensureHeadCached(serverPlayer.getGameProfile().getName(), context.getSource().getServer());
                        }
                    } catch (Throwable ignored) {}
                }

                ModNetworking.sendToPlayer(new DialoguePayload(iconToSend, nameToSend, colorname, message, skinUuid), serverPlayer);
                HistoryManager.record(serverPlayer, context.getSource().getServer(),
                        iconToSend, nameToSend, parseColor(colorname), message, skinUuid);
            }
        }

        context.getSource().sendSuccess(
            () -> Component.translatable("ptdialogue.command.dialogue.sent", targets.size()),
            false
        );
        return Command.SINGLE_SUCCESS;
    }
}
