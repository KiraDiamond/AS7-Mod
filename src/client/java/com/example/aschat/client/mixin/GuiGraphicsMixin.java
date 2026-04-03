package com.example.aschat.client.mixin;

import com.example.aschat.client.AsChatClientMod;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
  @Inject(method = "renderComponentHoverEffect", at = @At("HEAD"), cancellable = true)
  private void aschat$renderMapHoverPreview(
      Font font, Style style, int mouseX, int mouseY, CallbackInfo ci) {
    if (AsChatClientMod.renderCustomHoverEffect(
        (GuiGraphics) (Object) this, style, mouseX, mouseY)) {
      ci.cancel();
    }
  }
}
