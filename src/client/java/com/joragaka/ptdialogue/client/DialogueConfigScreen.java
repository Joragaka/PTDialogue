package com.joragaka.ptdialogue.client;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Cloth Config screen builder for ptdialogue dialogue settings.
 * Values are applied in real-time so the live preview updates instantly.
 */
public class DialogueConfigScreen {

    public static Screen create(Screen parent) {
        // Ensure config is loaded
        DialogueClientConfig.load();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.translatable("config.ptdialogue.title"))
                .setSavingRunnable(DialogueClientConfig::save);

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        ConfigCategory general = builder.getOrCreateCategory(
                Text.translatable("config.ptdialogue.category.general"));

        // Distance from center
        AbstractConfigListEntry<Integer> distanceEntry = entryBuilder.startIntField(
                        Text.translatable("config.ptdialogue.distanceFromCenter"),
                        DialogueClientConfig.getDistanceFromCenter())
                .setDefaultValue(150)
                .setMin(0)
                .setMax(500)
                .setTooltip(Text.translatable("config.ptdialogue.distanceFromCenter.tooltip"))
                .setSaveConsumer(DialogueClientConfig::setDistanceFromCenter)
                .build();
        general.addEntry(distanceEntry);

        // Window scale
        AbstractConfigListEntry<Float> scaleEntry = entryBuilder.startFloatField(
                        Text.translatable("config.ptdialogue.windowScale"),
                        DialogueClientConfig.getWindowScale())
                .setDefaultValue(1.0f)
                .setMin(0.3f)
                .setMax(3.0f)
                .setTooltip(Text.translatable("config.ptdialogue.windowScale.tooltip"))
                .setSaveConsumer(DialogueClientConfig::setWindowScale)
                .build();
        general.addEntry(scaleEntry);

        // Edge padding
        AbstractConfigListEntry<Float> edgePaddingEntry = entryBuilder.startFloatField(
                        Text.translatable("config.ptdialogue.edgePadding"),
                        DialogueClientConfig.getEdgePadding())
                .setDefaultValue(0.25f)
                .setMin(0.0f)
                .setMax(0.45f)
                .setTooltip(Text.translatable("config.ptdialogue.edgePadding.tooltip"))
                .setSaveConsumer(DialogueClientConfig::setEdgePadding)
                .build();
        general.addEntry(edgePaddingEntry);

        // Vertical padding fraction
        AbstractConfigListEntry<Float> vertPadEntry = entryBuilder.startFloatField(
                        Text.translatable("config.ptdialogue.verticalPaddingFraction"),
                        DialogueClientConfig.getVerticalPaddingFraction())
                .setDefaultValue(0.075f)
                .setMin(0.02f)
                .setMax(0.15f)
                .setTooltip(Text.translatable("config.ptdialogue.verticalPaddingFraction.tooltip"))
                .setSaveConsumer(DialogueClientConfig::setVerticalPaddingFraction)
                .build();
        general.addEntry(vertPadEntry);

        Screen screen = builder.build();

        // Register an afterRender callback to draw the live preview on top of the config screen.
        // We read current entry values directly and pass them to renderPreviewWithOverrides
        // WITHOUT modifying DialogueClientConfig — so the real config stays untouched until Save.
        ScreenEvents.AFTER_INIT.register((client, s, scaledWidth, scaledHeight) -> {
            if (s == screen) {
                ScreenEvents.afterRender(s).register((s2, drawContext, mouseX, mouseY, tickDelta) -> {
                    // Read current (unsaved) entry values for preview only
                    int previewDistance = distanceEntry.getValue();
                    float previewScale = scaleEntry.getValue();
                    float previewEdgePadding = edgePaddingEntry.getValue();

                    // Render preview with overridden values — does NOT touch DialogueClientConfig
                    DialogueRenderer.renderPreviewWithOverrides(
                            drawContext,
                            "Joraga_ka",
                            "Dev",
                            0x9B30FF,
                            Text.literal("Lorem ipsum dolor sit amet, consectetur adipiscing elit."),
                            previewDistance,
                            previewScale,
                            previewEdgePadding
                    );
                });
            }
        });

        return screen;
    }
}
