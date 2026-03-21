package com.joragaka.ptdialogue.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.SkinTextures;
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

    public static void renderDialogue(DrawContext drawContext, RenderTickCounter renderTickCounter) {
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

        // Edge padding (fraction of framebuffer width → GUI units)
        float edgePaddingFraction = overrideEdgePadding >= 0 ? overrideEdgePadding : DialogueClientConfig.getEdgePadding();
        int edgePadding = Math.max(0, Math.round((fbW * edgePaddingFraction) / deviceScale));

        // Max width available for text (GUI units)
        int textAreaLeft   = headSize + headPadding;
        int maxContentWidth= screenWidth - edgePadding * 2 - textAreaLeft - boxPadding * 2;

        // Wrap text: renderer draws at scale=1; we will scale the matrix, so wrap in unscaled units
        int wrapWidthUnscaled = Math.max(1, (int) Math.floor(maxContentWidth / Math.max(0.001f, textScale)));

        // First line has reduced width (name occupies space), subsequent lines use full width
        int firstLineWidth = Math.max(1, wrapWidthUnscaled - nameWidthUnscaled);

        // Word-based wrapping: wrap message at firstLineWidth to get what fits on line 0,
        // then wrap remainder at full width
        List<OrderedText> firstWrap = textRenderer.wrapLines(dialogue, firstLineWidth);

        OrderedText firstMsgLine = null;
        java.util.List<OrderedText> subsequentLines = new java.util.ArrayList<>();

        if (!firstWrap.isEmpty()) {
            firstMsgLine = firstWrap.get(0);
        }

        // If message needs more than one line, re-wrap at full width for subsequent lines
        if (firstWrap.size() > 1) {
            // Get char count of first line to extract styled remainder
            int[] firstLineCharCount = {0};
            firstWrap.get(0).accept((index, style, codePoint) -> {
                firstLineCharCount[0]++;
                return true;
            });
            int skipChars = firstLineCharCount[0];

            // Skip whitespace between first line and remainder (word-wrap boundary)
            String fullStr = dialogue.getString();
            while (skipChars < fullStr.length() && fullStr.charAt(skipChars) == ' ') {
                skipChars++;
            }
            int finalSkipChars = skipChars;

            MutableText styledRemainder = Text.empty().copy();
            int[] idx = {0};
            dialogue.asOrderedText().accept((index, style, codePoint) -> {
                if (idx[0] >= finalSkipChars) {
                    styledRemainder.append(Text.literal(new String(Character.toChars(codePoint))).setStyle(style));
                }
                idx[0]++;
                return true;
            });
            subsequentLines.addAll(textRenderer.wrapLines(styledRemainder, wrapWidthUnscaled));
        }

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

        var matrices = drawContext.getMatrices();
        matrices.scale(textScale, textScale);

        int drawX    = Math.max(0, Math.round(textX      / Math.max(0.001f, textScale)));
        int currentY = Math.max(0, Math.round(textStartY / Math.max(0.001f, textScale)));

        // Draw name on line 0
        drawContext.drawText(textRenderer, nameOrdered, drawX, currentY, textArgb, false);

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

        matrices.scale(1.0f / Math.max(0.001f, textScale), 1.0f / Math.max(0.001f, textScale));
    }

    private static void drawPlayerHead(DrawContext drawContext, MinecraftClient client, String icon, int x, int y, int size, float alpha) {
        if (CustomIconCache.isCustomIcon(icon)) {
            Identifier customTex = CustomIconCache.getIconTextureId(icon);
            if (customTex != null) {
                drawCustomTexture(drawContext, customTex, x, y, size, size, alpha);
                return;
            }
            drawCustomTexture(drawContext, MissingTextureHelper.getTextureId(), x, y, size, size, alpha);
            return;
        }

        Identifier headId = SkinCache.getHeadTextureId(icon);
        if (headId != null) {
            drawCustomTexture(drawContext, headId, x, y, size, size, alpha);
            return;
        }

        if (client.getNetworkHandler() != null) {
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(icon);
            if (entry != null) {
                SkinTextures skinTextures = entry.getSkinTextures();
                if (skinTextures != null) {
                    int colorArgb = ((int)(alpha * 255) << 24) | 0x00FFFFFF;
                    PlayerSkinDrawer.draw(drawContext, skinTextures, x, y, size, colorArgb);
                    return;
                }
            }
        }

        drawCustomTexture(drawContext, MissingTextureHelper.getTextureId(), x, y, size, size, alpha);
    }

    private static void drawSemiTransparentBox(DrawContext drawContext, int x, int y, int width, int height, float runtimeAlpha) {
        int alphaInt = Math.max(0, Math.min(255, (int) (128 * runtimeAlpha)));
        int color = alphaInt << 24;
        drawContext.fill(x, y, x + width, y + height, color);
    }

    /**
     * Draw a custom texture using DrawContext.drawTexture with the GUI_TEXTURED pipeline.
     * Direct import of RenderPipelines ensures Loom remaps the reference correctly
     * in both dev (yarn) and production (intermediary) environments.
     * No Class.forName, no reflection — just a normal Java reference.
     */
    private static void drawCustomTexture(DrawContext drawContext, Identifier texture, int x, int y, int width, int height, float alpha) {
        try {
            int colorArgb = ((int)(alpha * 255) << 24) | 0x00FFFFFF;
            var pipeline = RenderPipelines.GUI_TEXTURED;
            drawContext.drawTexture(pipeline, texture, x, y, 0.0f, 0.0f, width, height, width, height, colorArgb);
        } catch (Exception e) {
            // Fallback: checkerboard (should never happen now)
            int alphaInt = (int)(alpha * 255);
            int black   = alphaInt << 24;
            int magenta = (alphaInt << 24) | 0xF800F8;
            int half = Math.max(width / 2, 1);
            drawContext.fill(x, y, x + half, y + half, black);
            drawContext.fill(x + half, y, x + width, y + half, magenta);
            drawContext.fill(x, y + half, x + half, y + height, magenta);
            drawContext.fill(x + half, y + half, x + width, y + height, black);
        }
    }
}
