package com.joragaka.ptdialogue.mixin.client;

import com.joragaka.ptdialogue.client.DialogueRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "renderChat", at = @At("TAIL"))
    private void ptdialogue_renderAfterChat(DrawContext drawContext, RenderTickCounter tickCounter, CallbackInfo ci) {
        DialogueRenderer.renderDialogue(drawContext, tickCounter);
    }
}
