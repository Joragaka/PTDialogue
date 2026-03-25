package com.joragaka.ptdialogue.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen scrollable dialogue history.
 */
public class DialogueHistoryScreen extends Screen {

    private static final Identifier BACKGROUND_TEXTURE = new Identifier("ptdialogue", "textures/gui/dialogue_history_bg.png");

    // Base constants (match DialogueRenderer)
    private static final float TEXT_HEIGHT_FRACTION = 0.025f; // text height as fraction of window height
    private static final float HEAD_SCALE = 2.0f; // head size relative to text height

    private static final int HORIZONTAL_PADDING = 20;
    private static final int ENTRY_GAP = 6;

    private double scrollOffset = 0;
    private int totalContentHeight = 0;
    private final List<BakedEntry> bakedEntries = new ArrayList<>();

    // Caching: avoid rebaking every frame — track last known state
    private long lastKnownHistoryVersion = -1L;
    private int lastKnownWidth = -1;
    private int lastKnownHeight = -1;
    private float lastKnownStoredScale = -1f;
    private float lastKnownEdgePadding = -1f;
    private float lastKnownVerticalPaddingFrac = -1f;

    // runtime computed sizes (GUI units)
    private float currentTextScale = 1.0f; // matrix scale applied to text rendering
    private int headSizeGui = 16;
    private int headPaddingGui = 6;
    private int boxPaddingGui = 6;

    // Animation state (appearance/closing)
    private long animStartTimeNs = -1L; // nanoseconds
    private final float animDurationMs = 220f; // duration in ms
    private boolean animClosing = false;
    private float animProgress = 0f; // 0..1

    public DialogueHistoryScreen() {
        super(Text.translatable("screen.ptdialogue.dialogueHistory"));
    }

    private static class BakedEntry {
        final DialogueHistory.Entry source;
        final OrderedText nameLine; // rendered name prefix (e.g. "[Name]: ")
        final int nameWidthUnscaled;
        final OrderedText firstMsgLine;       // message part that fits on line 0 next to name (or null)
        final OrderedText fullFirstLine;     // optional: full first line including name (when used)
        final List<OrderedText> subsequentLines; // remaining message lines at full width
        final int entryHeight;
        final int maxLineWidthUnscaled;

        BakedEntry(DialogueHistory.Entry source, OrderedText nameLine, int nameWidthUnscaled,
                   OrderedText firstMsgLine, OrderedText fullFirstLine, List<OrderedText> subsequentLines, int entryHeight, int maxLineWidthUnscaled) {
            this.source = source;
            this.nameLine = nameLine;
            this.nameWidthUnscaled = nameWidthUnscaled;
            this.firstMsgLine = firstMsgLine;
            this.fullFirstLine = fullFirstLine;
            this.subsequentLines = subsequentLines;
            this.entryHeight = entryHeight;
            this.maxLineWidthUnscaled = maxLineWidthUnscaled;
        }
    }

    @Override
    protected void init() {
        super.init();
        refreshLocalPlayerHead();
        rebakeEntries();
        scrollToBottom();
        // start opening animation
        animStartTimeNs = System.nanoTime();
        animClosing = false;
        animProgress = 0f;
    }

    /** Force-refresh the local player's composited head so the history shows the latest skin. */
    private static void refreshLocalPlayerHead() {
        ptdialogueClient.refreshLocalPlayerHead(MinecraftClient.getInstance());
    }

    @Override
    public void resize(MinecraftClient client, int width, int height) {
        super.resize(client, width, height);
        // mark stale so next render will rebake
        lastKnownWidth = -1;
        lastKnownHeight = -1;
        clampScroll();
    }

    /**
     * Recalculate wrapped lines and GUI sizes based on stored config scale (not current GUI scale).
     * Logic mirrors DialogueRenderer exactly.
     */
    private void rebakeEntries() {
        bakedEntries.clear();
        if (client == null) return;

        TextRenderer textRenderer = client.textRenderer;
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int fbW = client.getWindow().getWidth();

        float storedScale = DialogueClientConfig.getStoredWindowScale();

        // deviceScale: physical pixels per GUI unit
        float deviceScale = 1.0f;
        try {
            double sf = client.getWindow().getScaleFactor();
            if (sf > 0) deviceScale = (float) sf;
            else if (screenWidth > 0) deviceScale = (float) fbW / (float) screenWidth;
        } catch (Throwable t) {
            if (screenWidth > 0) deviceScale = (float) fbW / (float) screenWidth;
        }

        float textHeightPx  = client.getWindow().getHeight() * TEXT_HEIGHT_FRACTION * storedScale;
        float nativeFontPx  = textRenderer.fontHeight * deviceScale; // same as DialogueRenderer
        currentTextScale    = textHeightPx / Math.max(0.001f, nativeFontPx);

        float headSizePx    = textHeightPx * HEAD_SCALE;
        float headPaddingPx = textHeightPx * 0.4f;
        float boxPaddingPx  = textHeightPx * 0.65f;

        headSizeGui    = Math.max(1, (int) Math.ceil(headSizePx    / deviceScale));
        headPaddingGui = Math.max(0, (int) Math.ceil(headPaddingPx / deviceScale));
        boxPaddingGui  = Math.max(0, (int) Math.ceil(boxPaddingPx  / deviceScale));

        // edgePadding from config (fraction of fbW → GUI units) — same formula as DialogueRenderer
        float edgePaddingFraction = DialogueClientConfig.getEdgePadding();
        int edgePaddingGui = Math.max(0, Math.round((fbW * edgePaddingFraction) / deviceScale));

        int textAreaLeft = headSizeGui + headPaddingGui;
        int maxContentWidthGui = Math.max(1, screenWidth - edgePaddingGui * 2 - textAreaLeft - boxPaddingGui * 2);
        int wrapWidthUnscaled  = Math.max(1, (int) Math.ceil(maxContentWidthGui / Math.max(0.001f, currentTextScale)));
        // small tolerance to avoid overly-early wrapping (match DialogueRenderer)
        wrapWidthUnscaled = Math.max(1, wrapWidthUnscaled + 1);

        int unscaledLineSpacing = textRenderer.fontHeight + 2;
        int scaledLineSpacing   = Math.max(1, (int) Math.ceil(unscaledLineSpacing * currentTextScale));

        int totalHeight = 0;
        for (DialogueHistory.Entry entry : DialogueHistory.getEntries()) {
            // helper to extract plain string from OrderedText for debug
            java.util.function.Function<OrderedText, String> orderedToPlain = (ot) -> {
                if (ot == null) return "";
                StringBuilder sb = new StringBuilder();
                try {
                    ot.accept((index, style, codePoint) -> {
                        sb.appendCodePoint(codePoint);
                        return true;
                    });
                } catch (Throwable ignored) {}
                return sb.toString();
            };

            DialogueHistory.Entry eEntry = entry; // for lambda safety
             MutableText nameText = DialoguePacketHandler.buildNamePrefix(entry.getName(), entry.getNameColor());
             OrderedText nameLine = nameText.asOrderedText();
             int nameWidthUnscaled = textRenderer.getWidth(nameLine);

             Text messageText = entry.getMessage();
            // Wrap the combined name+message as a whole so history matches chat rendering
            MutableText combined = nameText.copy().append(messageText);
            java.util.List<OrderedText> wrapped = textRenderer.wrapLines(combined, wrapWidthUnscaled);
            OrderedText fullFirstLine = null;
            java.util.List<OrderedText> subsequentLines = new java.util.ArrayList<>();
            if (!wrapped.isEmpty()) {
                fullFirstLine = wrapped.get(0);
                if (wrapped.size() > 1) {
                    for (int i = 1; i < wrapped.size(); i++) subsequentLines.add(wrapped.get(i));
                }
            }
            OrderedText firstMsgLine = null; // not used when fullFirstLine is present

            int totalLines = 1 + subsequentLines.size();
            int textHeightGui  = totalLines * scaledLineSpacing;
            int innerHeightGui = Math.max(headSizeGui, textHeightGui);
            int entryHeightGui = innerHeightGui + boxPaddingGui * 2;

            int maxLineW = nameWidthUnscaled;
            if (fullFirstLine != null) {
                int w = textRenderer.getWidth(fullFirstLine);
                if (w > maxLineW) maxLineW = w;
            }
             for (OrderedText line : subsequentLines) {
                 int w = textRenderer.getWidth(line);
                 if (w > maxLineW) maxLineW = w;
             }

            bakedEntries.add(new BakedEntry(entry, nameLine, nameWidthUnscaled, firstMsgLine, fullFirstLine, subsequentLines, entryHeightGui, maxLineW));
            totalHeight += entryHeightGui;
        }

        if (!bakedEntries.isEmpty()) totalHeight += ENTRY_GAP * (bakedEntries.size() - 1);
        totalContentHeight = totalHeight;

        // update cached tracking values
        lastKnownHistoryVersion = DialogueHistory.getVersion();
        lastKnownWidth = this.width;
        lastKnownHeight = this.height;
        lastKnownStoredScale = DialogueClientConfig.getStoredWindowScale();
        lastKnownEdgePadding = DialogueClientConfig.getEdgePadding();
        lastKnownVerticalPaddingFrac = DialogueClientConfig.getVerticalPaddingFraction();
    }

    private int getVerticalPadding() {
        // Use user-configurable fraction; default slightly smaller so messages are closer to arches
        float frac = DialogueClientConfig.getVerticalPaddingFraction();
        return Math.max(2, Math.round(this.height * frac));
    }

    private void scrollToBottom() {
        int vp = getVerticalPadding();
        int visibleHeight = this.height - vp * 2;
        if (totalContentHeight > visibleHeight) {
            scrollOffset = totalContentHeight - visibleHeight;
        } else {
            scrollOffset = 0;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scrollOffset -= amount * 20;
        clampScroll();
        return true;
    }

    private void clampScroll() {
        int vp = getVerticalPadding();
        int visibleHeight = this.height - vp * 2;
        double maxScroll = Math.max(0, totalContentHeight - visibleHeight);
        if (scrollOffset < 0) scrollOffset = 0;
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        // Recompute wrapped lines only when necessary (history changed, window size or relevant config changed)
        boolean needsRebake = false;
        if (lastKnownHistoryVersion != DialogueHistory.getVersion()) needsRebake = true;
        if (lastKnownWidth != this.width || lastKnownHeight != this.height) needsRebake = true;
        float storedScale = DialogueClientConfig.getStoredWindowScale();
        if (Float.compare(storedScale, lastKnownStoredScale) != 0) needsRebake = true;
        float edgePad = DialogueClientConfig.getEdgePadding();
        if (Float.compare(edgePad, lastKnownEdgePadding) != 0) needsRebake = true;
        float vpadFrac = DialogueClientConfig.getVerticalPaddingFraction();
        if (Float.compare(vpadFrac, lastKnownVerticalPaddingFrac) != 0) needsRebake = true;
        if (needsRebake) rebakeEntries();

        // Update animation progress (time-based)
        if (animStartTimeNs <= 0L) animStartTimeNs = System.nanoTime();
        long nowNs = System.nanoTime();
        float elapsedMs = (nowNs - animStartTimeNs) / 1_000_000.0f;
        float rawProgress = Math.min(1.0f, Math.max(0.0f, elapsedMs / Math.max(1.0f, animDurationMs)));
        animProgress = animClosing ? (1.0f - rawProgress) : rawProgress;

        // Easing (easeOutCubic)
        float eased = (float) (1 - Math.pow(1 - animProgress, 3));

        // If closing finished, actually close the screen
        if (animClosing && animProgress <= 0.001f) {
            if (this.client != null) this.client.setScreen(null);
            return; // don't render further
        }

        // Draw background and entries with alpha/scale/offset applied per-element (no RenderSystem / push/pop)
        try {
            TextRenderer textRenderer = client.textRenderer;

            // Establish blend state for this frame — external/vanilla code may leave blend disabled
            // between frames (e.g. after DrawContext.fill(), world rendering, other mods).
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            // draw background (apply overall alpha via color argument)
            drawBackgroundTexture(drawContext, eased);

            if (bakedEntries.isEmpty()) {
                Text emptyText = Text.translatable("screen.ptdialogue.dialogueHistory.empty");
                int col = applyAlphaToColor(0xFFAAAAAA, eased);
                int centerY = this.height / 2;
                drawContext.drawCenteredTextWithShadow(textRenderer, emptyText, this.width / 2, centerY, col);
                super.render(drawContext, mouseX, mouseY, delta);
                return;
            }

            int vp = getVerticalPadding();
            int areaTop = vp;
            int areaBottom = this.height - vp;

            // enableScissor takes (left, top, right, bottom) in GUI coordinates
            int visibleHeight = this.height - vp * 2;
            drawContext.enableScissor(HORIZONTAL_PADDING, areaTop, this.width - HORIZONTAL_PADDING, areaBottom);

            // We'll render entries without scale animation — only alpha fade
            int centerX = this.width / 2;
            int effectiveScroll = (int) scrollOffset;
            int y = areaTop - effectiveScroll;

            for (BakedEntry baked : bakedEntries) {
                int entryBottom = y + baked.entryHeight;
                if (entryBottom > areaTop && y < areaBottom) {
                    int actualTextWidthScaled = (int) Math.ceil(baked.maxLineWidthUnscaled * currentTextScale);
                    int boxPaddingScaled = boxPaddingGui;
                    int headSizeScaled = headSizeGui;
                    int headPaddingScaled = headPaddingGui;

                    int contentBoxWidth = boxPaddingScaled * 2 + headSizeScaled + headPaddingScaled + actualTextWidthScaled;
                    int maxInnerWidth = this.width - HORIZONTAL_PADDING * 2;
                    if (contentBoxWidth > maxInnerWidth) contentBoxWidth = maxInnerWidth;

                    int boxX = centerX - (contentBoxWidth / 2);
                    int boxBaseAlpha = 0x80;
                    int boxAlphaInt = Math.max(0, Math.min(255, (int) (boxBaseAlpha * eased)));
                    int boxColor = (boxAlphaInt << 24);
                    drawContext.fill(boxX, y, boxX + contentBoxWidth, y + baked.entryHeight, boxColor);
                    // fill() calls disableBlend internally — restore for subsequent transparent draws
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();

                    int headX = boxX + boxPaddingScaled;
                    int headY = y + boxPaddingScaled + (Math.max(0, baked.entryHeight - boxPaddingScaled * 2 - headSizeScaled) / 2);
                    drawEntryHead(drawContext, baked.source.getIcon(), headX, headY, headSizeScaled, eased);

                    int textX = headX + headSizeScaled + headPaddingScaled;

                    // Vertically center text inside the inner content area
                    int unscaledLineSpacing = client.textRenderer.fontHeight + 2;
                    int scaledLineSpacing = Math.max(1, (int) Math.ceil(unscaledLineSpacing * currentTextScale));
                    int totalLines = 1 + baked.subsequentLines.size();
                    int textHeightGui = totalLines * scaledLineSpacing;
                    int innerHeightGui = Math.max(headSizeScaled, textHeightGui);
                    int textStartY = y + boxPaddingScaled + ((innerHeightGui - textHeightGui) / 2);
                    // Compensate for rounding differences between ideal scaled spacing and integer scaledLineSpacing
                    float idealScaledSpacing = unscaledLineSpacing * currentTextScale;
                    float spacingDiff = (float) scaledLineSpacing - idealScaledSpacing;
                    int roundingCompensation = Math.round(spacingDiff / 2.0f);
                    textStartY += roundingCompensation;
                    // Small downward nudge to visually center (match DialogueRenderer)
                    textStartY += 2;

                    var matricesLocal = drawContext.getMatrices();
                    matricesLocal.scale(currentTextScale, currentTextScale, 1.0f);

                    int drawX = Math.max(0, Math.round(textX / Math.max(0.001f, currentTextScale)));
                    int currentY = Math.max(0, Math.round(textStartY / Math.max(0.001f, currentTextScale)));
                    int unscaledLineSpacingDraw = client.textRenderer.fontHeight + 2;

                    int textColor = applyAlphaToColor(0xFFFFFFFF, eased);

                    // Draw name on line 0 (fallback to building prefix if baked entry is malformed)
                    if (baked.fullFirstLine != null) {
                        // Draw the whole first line (including name) as one ordered text to avoid splitting issues
                        drawContext.drawText(textRenderer, baked.fullFirstLine, drawX, currentY, textColor, false);
                    } else {
                        OrderedText nameToDraw = baked.nameLine;
                        int nameWidth = baked.nameWidthUnscaled;
                        if (nameToDraw == null || nameWidth <= 0) {
                            try {
                                MutableText rebuilt = DialoguePacketHandler.buildNamePrefix(baked.source.getName(), baked.source.getNameColor());
                                nameToDraw = rebuilt.asOrderedText();
                                nameWidth = textRenderer.getWidth(nameToDraw);
                            } catch (Throwable ignored) {
                                nameToDraw = Text.literal(baked.source.getName() == null ? "" : baked.source.getName()).asOrderedText();
                                nameWidth = textRenderer.getWidth(nameToDraw);
                            }
                        }
                        drawContext.drawText(textRenderer, nameToDraw, drawX, currentY, textColor, false);

                        // Draw first message part right after name on line 0
                        if (baked.firstMsgLine != null) {
                            int msgDrawX = drawX + nameWidth;
                            drawContext.drawText(textRenderer, baked.firstMsgLine, msgDrawX, currentY, textColor, false);
                        }
                    }
                    currentY += unscaledLineSpacingDraw;

                    // Draw subsequent lines at full width
                    for (OrderedText line : baked.subsequentLines) {
                        drawContext.drawText(textRenderer, line, drawX, currentY, textColor, false);
                        currentY += unscaledLineSpacingDraw;
                    }

                    matricesLocal.scale(1.0f / Math.max(0.001f, currentTextScale), 1.0f / Math.max(0.001f, currentTextScale), 1.0f);
                 }
                 y += baked.entryHeight + ENTRY_GAP;
             }

            drawContext.disableScissor();

            // Previously drew a scrollbar on the right when content overflowed. Remove visual scrollbar
            // so history opens without the right-hand slider while keeping scroll behavior intact.
            // if (totalContentHeight > visibleHeight) {
            //     drawScrollbar(drawContext, this.width - HORIZONTAL_PADDING - 6, areaTop, 6, visibleHeight);
            // }

            super.render(drawContext, mouseX, mouseY, delta);
        } catch (Exception e) {
            // If anything fails, fall back to previous rendering path without fancy animation
            super.render(drawContext, mouseX, mouseY, delta);
        }
    }

    // Cached texture dimensions (read once from the resource PNG)
    private static int cachedTexW = -1;
    private static int cachedTexH = -1;

    private static void ensureTextureDimensions() {
        if (cachedTexW > 0) return;
        try {
            var rm = MinecraftClient.getInstance().getResourceManager();
            var optResource = rm.getResource(BACKGROUND_TEXTURE);
            if (optResource.isPresent()) {
                try (var is = optResource.get().getInputStream()) {
                    NativeImage img = NativeImage.read(is);
                    cachedTexW = img.getWidth();
                    cachedTexH = img.getHeight();
                    img.close();
                    // background texture size read (debug output removed)
                 }
             }
         } catch (Exception e) {
            // failed to read background texture size (debug output removed)
         }
        if (cachedTexW <= 0) { cachedTexW = 64; cachedTexH = 64; }
    }

    // modified drawBackgroundTexture to accept overall alpha
    private void drawBackgroundTexture(DrawContext drawContext, float alpha) {
        try {
             MinecraftClient mc = MinecraftClient.getInstance();

             int guiW = this.width;
             int guiH = this.height;
             int fbH  = mc.getWindow().getFramebufferHeight();
             float guiScale = (guiH > 0) ? (float) fbH / (float) guiH : 1.0f;

             ensureTextureDimensions();
             int texW = cachedTexW;
             int texH = cachedTexH;

             // k = physical screen height / texture height
             float k = (float) fbH / (float) texH;
             // Desired size in GUI units preserving aspect ratio, height fills screen
             int destW = Math.max(1, Math.round((texW * k) / guiScale));
             int destX = (guiW - destW) / 2;

             drawContext.enableScissor(0, 0, guiW, guiH);

             float a = Math.max(0.0f, Math.min(1.0f, alpha));
             RenderSystem.enableBlend();
             RenderSystem.defaultBlendFunc();
             RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, a);
             drawContext.drawTexture(BACKGROUND_TEXTURE, destX, 0, destW, guiH, 0.0f, 0.0f, texW, texH, texW, texH);
             RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

             drawContext.disableScissor();
         } catch (Exception e) {
             int bgColor = applyAlphaToColor(0xCC000000, alpha);
             drawContext.fill(0, 0, this.width, this.height, bgColor);
         }
     }

    // modified: accept alpha and pass color through
    private void drawEntryHead(DrawContext drawContext, String icon, int x, int y, int size, float alpha) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // Resolve selector @s -> current player's name so we use local player's skin texture
        String resolvedIcon = icon;
        if ("@s".equals(icon)) {
            if (mc.player != null && mc.player.getGameProfile() != null) {
                try { resolvedIcon = mc.player.getGameProfile().getName(); } catch (Throwable t) { resolvedIcon = mc.player.getName().getString(); }
            } else {
                resolvedIcon = null;
            }
        }

        // Custom icons (file-based) take priority
        if (CustomIconCache.isCustomIcon(icon)) {
            Identifier customTex = CustomIconCache.getIconTextureId(icon);
            if (customTex != null) {
                drawTex(drawContext, customTex, x, y, size, applyAlphaToColor(0xFFFFFFFF, alpha));
                return;
            }
            drawTex(drawContext, MissingTextureHelper.getTextureId(), x, y, size, applyAlphaToColor(0xFFFFFFFF, alpha));
            return;
        }

        // Prefer resolvedIcon (e.g. when icon == "@s") for skin lookup
        boolean isLocalPlayerIcon = false;
        if (mc.player != null && resolvedIcon != null) {
            try { isLocalPlayerIcon = resolvedIcon.equals(mc.player.getGameProfile().getName()); } catch (Throwable ignored) {}
        }

        if (isLocalPlayerIcon) {
            // Try to register head from in-memory PlayerListEntry (SkinRestorer case)
            try {
                if (mc.getNetworkHandler() != null) {
                    PlayerListEntry pleTry = mc.getNetworkHandler().getPlayerListEntry(resolvedIcon);
                    if (pleTry != null) {
                        try { SkinCache.tryRegisterHeadFromPlayerListEntry(pleTry, resolvedIcon); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            Identifier localHead = SkinCache.getHeadTextureId(resolvedIcon);
            if (localHead != null) {
                // Prefer the composited SkinCache head first — it guarantees face+overlay and will reflect in-memory changes
                drawTex(drawContext, localHead, x, y, size, applyAlphaToColor(0xFFFFFFFF, alpha));
                return;
            }

            // If no composited head available, attempt to request server or use missing texture
            drawTex(drawContext, MissingTextureHelper.getTextureId(), x, y, size, applyAlphaToColor(0xFFFFFFFF, alpha));
            return;
        }

        // Try cached head texture next (fast), then fall back to PlayerListEntry
        Identifier headId = null;
        if (resolvedIcon != null) headId = SkinCache.getHeadTextureId(resolvedIcon);
        if (headId != null) {
            // Prefer composited SkinCache head first — it contains both layers and updates when SkinCache was refreshed
            drawTex(drawContext, headId, x, y, size, applyAlphaToColor(0xFFFFFFFF, alpha));
            return;
        }

        if (mc.getNetworkHandler() != null) {
            PlayerListEntry entry = null;
            if (resolvedIcon != null) entry = mc.getNetworkHandler().getPlayerListEntry(resolvedIcon);
            if (entry == null && icon != null) entry = mc.getNetworkHandler().getPlayerListEntry(icon);
            if (entry != null) {
                // Avoid using SkinTextures directly; try to register via SkinCache and fall back to missing texture
                try { SkinCache.tryRegisterHeadFromPlayerListEntry(entry, resolvedIcon != null ? resolvedIcon : icon); } catch (Throwable ignored) {}
                Identifier hid = SkinCache.getHeadTextureId(resolvedIcon != null ? resolvedIcon : icon);
                if (hid != null) { drawTex(drawContext, hid, x, y, size, applyAlphaToColor(0xFFFFFFFF, alpha)); return; }
            }
        }

        drawTex(drawContext, MissingTextureHelper.getTextureId(), x, y, size, applyAlphaToColor(0xFFFFFFFF, alpha));
    }

    private void drawTex(DrawContext drawContext, Identifier texture, int x, int y, int size, int colorArgb) {
        try {
            float a = ((colorArgb >> 24) & 0xFF) / 255.0f;
            float r = ((colorArgb >> 16) & 0xFF) / 255.0f;
            float g = ((colorArgb >> 8) & 0xFF) / 255.0f;
            float b = (colorArgb & 0xFF) / 255.0f;
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(r, g, b, a);
            drawContext.drawTexture(texture, x, y, 0, 0, size, size, size, size);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        } catch (Exception ignored) {}
     }

    // Apply an alpha multiplier [0..1] to a 32-bit ARGB color
    private int applyAlphaToColor(int argbColor, float alpha) {
        int baseA = (argbColor >>> 24) & 0xFF;
        int baseRgb = argbColor & 0xFFFFFF;
        int a = Math.max(0, Math.min(255, Math.round(baseA * Math.max(0f, Math.min(1f, alpha)))));
        return (a << 24) | baseRgb;
    }

    // Lightweight scrollbar drawing (keeps it invisible by default but present for hit testing if needed)
    private void drawScrollbar(DrawContext drawContext, int x, int y, int barWidth, int areaHeight) {
        try {
            // Draw a very subtle background track
            drawContext.fill(x, y, x + barWidth, y + areaHeight, 0x22000000);
            // Draw thumb proportional to content
            int visibleHeight = areaHeight;
            int contentHeight = Math.max(1, totalContentHeight);
            double ratio = Math.min(1.0, (double) visibleHeight / (double) contentHeight);
            int thumbH = Math.max(4, (int) Math.round(visibleHeight * ratio));
            double scrollRatio = totalContentHeight <= visibleHeight ? 0.0 : (scrollOffset / Math.max(1.0, totalContentHeight - visibleHeight));
            int thumbY = y + (int) Math.round((visibleHeight - thumbH) * scrollRatio);
            drawContext.fill(x + 1, thumbY, x + barWidth - 1, thumbY + thumbH, 0x44000000);
        } catch (Throwable ignored) {}
    }
}
