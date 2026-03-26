package com.joragaka.ptdialogue.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

public class DialogueRenderer {

    // Head size: set to exactly 2.0x text height as requested
    private static final float HEAD_SCALE = 2.0f;

    // Text line height as a fraction of the framebuffer (physical) height when storedScale=1.
    // At 1080p this gives ~27 px per line (27/1080 ≈ 0.025), which matches the old look.
    // The dialogue scales proportionally with the window height, so small windows get a
    // smaller dialogue and large windows get a larger one — independent of GUI scale.
    private static final float TEXT_HEIGHT_FRACTION = 0.025f;

    // Note: called from HUD callback with a single DrawContext parameter
    public static void renderDialogue(DrawContext drawContext) {
        // Update dialogue state once per frame
        DialogueManager.tick();

        if (!DialogueManager.hasDialogue()) {
            return;
        }

        Text dialogue = DialogueManager.getCurrentDialogue();
        float alpha = DialogueManager.getAlpha();
        String icon = DialogueManager.getIcon();
        String name = DialogueManager.getName();
        int nameColor = DialogueManager.getNameColor();

        if (dialogue == null || alpha <= 0) {
            return;
        }

        renderDialogueInternal(drawContext, icon, name, nameColor, dialogue, alpha, -1, -1f, -1f);
    }

    /**
     * Render a preview dialogue with explicit parameters — completely independent of DialogueManager.
     * Used by the config screen for live preview.
     */
    public static void renderPreview(DrawContext drawContext, String icon, String name, int nameColor, Text dialogue) {
        renderDialogueInternal(drawContext, icon, name, nameColor, dialogue, 1.0f, -1, -1f, -1f);
    }

    /**
     * Render a preview dialogue with config overrides — does NOT modify DialogueClientConfig.
     * Used by the config screen for dynamic live preview based on current (unsaved) entry values.
     */
    public static void renderPreviewWithOverrides(DrawContext drawContext, String icon, String name, int nameColor, Text dialogue,
                                                   int distFromCenter, float windowScale, float edgePadding) {
        renderDialogueInternal(drawContext, icon, name, nameColor, dialogue, 1.0f, distFromCenter, windowScale, edgePadding);
    }

    /**
     * @param overrideDistFromCenter if >= 0, use this instead of DialogueClientConfig
     * @param overrideScale if >= 0, use this instead of DialogueClientConfig
     * @param overrideEdgePadding if >= 0, use this instead of DialogueClientConfig
     */
    private static void renderDialogueInternal(DrawContext drawContext, String icon, String name, int nameColor,
                                                Text dialogue, float alpha,
                                                int overrideDistFromCenter, float overrideScale, float overrideEdgePadding) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getWindow() == null) {
            return;
        }

        // Read config — use overrides if provided, otherwise read from DialogueClientConfig
        float storedScale = overrideScale >= 0 ? overrideScale : DialogueClientConfig.getStoredWindowScale();
        int distFromCenter = overrideDistFromCenter >= 0 ? overrideDistFromCenter : DialogueClientConfig.getDistanceFromCenter();

        TextRenderer textRenderer = client.textRenderer;
        int screenWidth  = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int fbW          = client.getWindow().getWidth();
        int rendererFontHeight = textRenderer.fontHeight; // GUI units at current GUI scale

        // deviceScale: physical pixels per GUI unit (= getScaleFactor())
        float deviceScale = 1.0f;
        try {
            double sf = client.getWindow().getScaleFactor();
            if (sf > 0) deviceScale = (float) sf;
            else if (screenWidth > 0) deviceScale = (float) fbW / (float) screenWidth;
        } catch (Throwable t) {
            if (screenWidth > 0) deviceScale = (float) fbW / (float) screenWidth;
        }

        // ── All sizes are computed in PHYSICAL PIXELS then divided by deviceScale ──
        // The dialogue scales proportionally to the window's physical height,
        // so it looks correct in any window size and is NOT affected by GUI scale.

        // Physical height of the framebuffer (window)
        int fbH = client.getWindow().getHeight();

        // Desired physical pixel height of one text line (proportional to window height).
        float textHeightPx = fbH * TEXT_HEIGHT_FRACTION * storedScale;

        // textScale = how much we must scale the renderer (which draws at deviceScale px/unit)
        // so that one line occupies textHeightPx physical pixels.
        // rendererFontHeight GUI-units × deviceScale = physical px the font occupies at scale=1.
        float nativeFontPx = rendererFontHeight * deviceScale;
        float textScale    = textHeightPx / Math.max(0.001f, nativeFontPx);

        // Head / padding / box sizes proportional to text height (physical px → GUI units)
        float headSizePx    = textHeightPx * HEAD_SCALE;
        float headPaddingPx = textHeightPx * 0.4f;
        float boxPaddingPx  = textHeightPx * 0.65f;

        int headSize   = Math.max(1, (int) Math.ceil(headSizePx    / deviceScale));
        int headPadding= Math.max(0, (int) Math.ceil(headPaddingPx / deviceScale));
        int boxPadding = Math.max(0, (int) Math.ceil(boxPaddingPx  / deviceScale));

        // Build styled text
        // Build name prefix: [Name] with color handling (supports JSON names and color=0)
        MutableText namePrefixText = DialoguePacketHandler.buildNamePrefix(name, nameColor);
        OrderedText nameOrdered = namePrefixText.asOrderedText();
        int nameWidthUnscaled = textRenderer.getWidth(nameOrdered);
        // Debug: print name info
        // (debug logs removed)

        // Edge padding (fraction of framebuffer width → GUI units)
        float edgePaddingFraction = overrideEdgePadding >= 0 ? overrideEdgePadding : DialogueClientConfig.getEdgePadding();
        int edgePadding = Math.max(0, Math.round((fbW * edgePaddingFraction) / deviceScale));

        // Max width available for text (GUI units)
        int textAreaLeft   = headSize + headPadding;
        int maxContentWidth= screenWidth - edgePadding * 2 - textAreaLeft - boxPadding * 2;

        // Wrap text: renderer draws at scale=1; we will scale the matrix, so wrap in unscaled units
        int wrapWidthUnscaled = Math.max(1, (int) Math.ceil(maxContentWidth / Math.max(0.001f, textScale)));
        // small tolerance to avoid overly-early wrapping
        wrapWidthUnscaled = Math.max(1, wrapWidthUnscaled + 1);

        // First line has reduced width (name occupies space), subsequent lines use full width
        int firstLineWidth = Math.max(1, wrapWidthUnscaled - nameWidthUnscaled);

        // Use helper to split message into firstMsgLine (fits next to name) and a styled remainder
        TextWrapHelper.SplitResult split = TextWrapHelper.splitFirstLine(textRenderer, dialogue, firstLineWidth);
        OrderedText firstMsgLine = split.firstLine;
        java.util.List<OrderedText> subsequentLines = new java.util.ArrayList<>();
        if (split.remainder != null && !split.remainder.getString().isEmpty()) {
            subsequentLines.addAll(textRenderer.wrapLines(split.remainder, wrapWidthUnscaled));
        }

        // Debug: print wrap/first widths to compare with history
        // (debug logs removed)

        int totalLines = 1 + subsequentLines.size();

        // Vertical position (distFromCenter is treated as if defined at 1080p,
        // then scaled proportionally to the actual window height → GUI units)
        float distScaleFactor = fbH / 1080.0f;
        int distFromCenterGui = Math.round((distFromCenter * distScaleFactor) / deviceScale);
        int centerY = (screenHeight / 2) + distFromCenterGui;

        // Line spacing
        int unscaledLineSpacing = rendererFontHeight + 2;
        int scaledLineSpacing   = (int) Math.ceil(unscaledLineSpacing * textScale);
        int totalTextHeight     = totalLines * scaledLineSpacing;

        // Box dimensions
        int contentHeight = Math.max(headSize, totalTextHeight);
        int boxHeight     = contentHeight + boxPadding * 2;

        int actualTextWidthUnscaled = nameWidthUnscaled;
        if (firstMsgLine != null) {
            int line0W = nameWidthUnscaled + textRenderer.getWidth(firstMsgLine);
            if (line0W > actualTextWidthUnscaled) actualTextWidthUnscaled = line0W;
        }
        for (OrderedText line : subsequentLines) {
            int w = textRenderer.getWidth(line);
            if (w > actualTextWidthUnscaled) actualTextWidthUnscaled = w;
        }
        int actualTextWidthScaled = (int) Math.ceil(actualTextWidthUnscaled * textScale);
        int boxWidth = textAreaLeft + actualTextWidthScaled + boxPadding * 2;

        int boxX = (screenWidth - boxWidth) / 2;
        int boxY = centerY - boxHeight / 2;

        // Push matrix and raise z so dialogue renders above vanilla chat
        var matrices = drawContext.getMatrices();
        matrices.push();
        matrices.translate(0, 0, 200);

        // Draw background box
        drawSemiTransparentBox(drawContext, boxX, boxY, boxWidth, boxHeight, alpha);

        // Draw head icon
        int headX = boxX + boxPadding;
        int headY = boxY + boxPadding + (contentHeight - headSize) / 2;
        drawPlayerHead(drawContext, client, icon, headX, headY, headSize, alpha);

        // Draw text lines with matrix scaling so physical size == textHeightPx
        int textX      = boxX + boxPadding + textAreaLeft;
        int textStartY = boxY + boxPadding + (contentHeight - totalTextHeight) / 2;
        // Compensate for rounding differences between ideal scaled spacing and integer scaledLineSpacing
        float idealScaledSpacing = unscaledLineSpacing * textScale;
        float spacingDiff = (float) scaledLineSpacing - idealScaledSpacing;
        int roundingCompensation = Math.round(spacingDiff / 2.0f);
        textStartY += roundingCompensation;
        // Apply a small downward nudge (2 GUI pixels) to visually center the text inside the box.
        textStartY += 2;
        int textArgb   = ((int)(alpha * 255) << 24) | 0x00FFFFFF;

        matrices.scale(textScale, textScale, 1.0f);

        int drawX    = Math.max(0, Math.round(textX      / Math.max(0.001f, textScale)));
        int currentY = Math.max(0, Math.round(textStartY / Math.max(0.001f, textScale)));

        // Draw name on line 0
        try {
            drawContext.drawText(textRenderer, nameOrdered, drawX, currentY, textArgb, false);
        } catch (Throwable t) {
            // Fallback: draw literal name to ensure visibility
            try {
                drawContext.drawText(textRenderer, Text.literal(name == null ? "" : name).asOrderedText(), drawX, currentY, textArgb, false);
            } catch (Throwable ignored) {}
        }

        // Draw first message part right after name on line 0
        if (firstMsgLine != null) {
            int msgDrawX = drawX + nameWidthUnscaled;
            drawContext.drawText(textRenderer, firstMsgLine, msgDrawX, currentY, textArgb, false);
        }
        currentY += unscaledLineSpacing;

        // Draw subsequent lines at full width
        for (OrderedText line : subsequentLines) {
            drawContext.drawText(textRenderer, line, drawX, currentY, textArgb, false);
            currentY += unscaledLineSpacing;
        }

        matrices.pop();
    }

    private static void drawPlayerHead(DrawContext drawContext, MinecraftClient client, String icon, int x, int y, int size, float alpha) {
        // Resolve selector @s -> current player's name so we use local player's skin texture
        String resolvedIcon = icon;
        if ("@s".equals(icon)) {
            if (client.player != null && client.player.getGameProfile() != null) {
                // Use raw profile name (unformatted) so SkinCache and player lookup match exact username
                try { resolvedIcon = client.getSession().getUsername(); } catch (Throwable ignored) { try { resolvedIcon = client.player.getName().getString(); } catch (Throwable ignored2) { resolvedIcon = null; } }
            } else {
                resolvedIcon = null;
            }
        }

        if (CustomIconCache.isCustomIcon(icon)) {
            Identifier customTex = CustomIconCache.getIconTextureId(icon);
            if (customTex != null) {
                drawTexture120(drawContext, customTex, x, y, size, alpha);
                return;
            }
            drawTexture120(drawContext, MissingTextureHelper.getTextureId(), x, y, size, alpha);
            return;
        }

        // If resolvedIcon is set (possibly via @s) prefer that for skin lookup
        // For the local player: prefer PlayerListEntry skin textures if available (ensures immediate update
        // when the player changes their skin locally), otherwise fall back to SkinCache which uses the
        // raw PNG composite (guarantees both layers when PlayerListEntry is missing overlay).
        boolean isLocalPlayerIcon = false;
        if (client.player != null && resolvedIcon != null) {
            try { isLocalPlayerIcon = resolvedIcon.equals(client.player.getGameProfile().getName()); } catch (Throwable ignored) {}
        }

        if (isLocalPlayerIcon) {
            // Attempt to register head from in-memory PlayerListEntry texture: covers cases where SkinRestorer
            // updated the texture in memory but didn't update disk file. This is a best-effort call and
            // is cheap because it quickly returns if no suitable texture exists.
            try {
                if (client.getNetworkHandler() != null) {
                    PlayerListEntry pleTry = client.getNetworkHandler().getPlayerListEntry(resolvedIcon);
                    if (pleTry != null) {
                        try { SkinCache.tryRegisterHeadFromPlayerListEntry(pleTry, resolvedIcon); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            // Prefer SkinCache composited head first (ensures overlay is shown); fall back to PlayerListEntry textures
            try {
                Identifier localHead = SkinCache.getHeadTextureId(resolvedIcon);
                if (localHead != null) {
                    drawTexture120(drawContext, localHead, x, y, size, alpha);
                    return;
                }
            } catch (Throwable ignored) {}

            // PlayerListEntry fallback (immediate local update)
            if (client.getNetworkHandler() != null) {
                PlayerListEntry pleLocal = client.getNetworkHandler().getPlayerListEntry(resolvedIcon);
                if (pleLocal != null) {
                    // avoid referencing SkinTextures class directly; rely on SkinCache registration
                    try { SkinCache.tryRegisterHeadFromPlayerListEntry(pleLocal, resolvedIcon); } catch (Throwable ignored) {}
                    Identifier hid = SkinCache.getHeadTextureId(resolvedIcon);
                    if (hid != null) {
                        drawTexture120(drawContext, hid, x, y, size, alpha);
                        return;
                    }
                    // otherwise show missing texture until SkinCache registers
                    drawTexture120(drawContext, MissingTextureHelper.getTextureId(), x, y, size, alpha);
                    return;
                }
            }
            // If neither is available — fall through and try other sources below
        }

        // Try PlayerListEntry skin textures for non-local icons (or if local fallback above didn't find anything)
        if (client.getNetworkHandler() != null) {
            PlayerListEntry ple = null;
            if (resolvedIcon != null) ple = client.getNetworkHandler().getPlayerListEntry(resolvedIcon);
            if (ple == null && icon != null) ple = client.getNetworkHandler().getPlayerListEntry(icon);
            if (ple != null) {
                String pleKey = resolvedIcon != null ? resolvedIcon : icon;
                // Check cache first — avoid expensive reflection every frame when head is already registered
                Identifier hid = pleKey != null ? SkinCache.getHeadTextureId(pleKey) : null;
                if (hid != null) {
                    drawTexture120(drawContext, hid, x, y, size, alpha);
                    return;
                }
                try { SkinCache.tryRegisterHeadFromPlayerListEntry(ple, pleKey); } catch (Throwable ignored) {}
                hid = pleKey != null ? SkinCache.getHeadTextureId(pleKey) : null;
                if (hid != null) {
                    drawTexture120(drawContext, hid, x, y, size, alpha);
                    return;
                }
                // If SkinCache doesn't have it yet, show missing (will be replaced when SkinCache registers)
                drawTexture120(drawContext, MissingTextureHelper.getTextureId(), x, y, size, alpha);
                return;
            }
        }


        // Next try cached head texture (faster) and then fallback to missing
        Identifier headId = null;
        if (resolvedIcon != null) headId = SkinCache.getHeadTextureId(resolvedIcon);
        if (headId != null) {
            drawTexture120(drawContext, headId, x, y, size, alpha);
            return;
        }

        drawTexture120(drawContext, MissingTextureHelper.getTextureId(), x, y, size, alpha);
    }

    private static void drawSemiTransparentBox(DrawContext drawContext, int x, int y, int width, int height, float runtimeAlpha) {
        int alphaInt = Math.max(0, Math.min(255, (int) (128 * runtimeAlpha)));
        int color = alphaInt << 24;
        drawContext.fill(x, y, x + width, y + height, color);
    }

    /**
     * Draw a texture compatible with Minecraft 1.20.1.
     * Uses RenderSystem.setShaderColor for alpha control.
     */
    private static void drawTexture120(DrawContext drawContext, Identifier texture, int x, int y, int size, float alpha) {
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, Math.max(0.0f, Math.min(1.0f, alpha)));
            drawContext.drawTexture(texture, x, y, 0, 0, size, size, size, size);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        } catch (Exception e) {
            int alphaInt = (int)(alpha * 255);
            int black   = alphaInt << 24;
            int magenta = (alphaInt << 24) | 0xF800F8;
            int half = Math.max(size / 2, 1);
            drawContext.fill(x, y, x + half, y + half, black);
            drawContext.fill(x + half, y, x + size, y + half, magenta);
            drawContext.fill(x, y + half, x + half, y + size, magenta);
            drawContext.fill(x + half, y + half, x + size, y + size, black);
        }
    }
}
