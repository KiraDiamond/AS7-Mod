package com.example.aschat.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class AsMapPreviewScreen extends Screen {
  private static final int OUTER_PADDING = 18;
  private static final int PANEL_PADDING = 10;
  private static final int TITLE_GAP = 16;
  private static final int FOOTER_GAP = 18;
  private static final float MIN_SCALE = 0.5F;

  private final Screen parent;
  private final AsMapVirtualManager mapVirtualManager;
  private final AsMapVirtualManager.RenderedImage renderedImage;

  private int previewLeft;
  private int previewTop;
  private int previewWidth;
  private int previewHeight;
  private int panelLeft;
  private int panelTop;
  private int panelRight;
  private int panelBottom;
  public AsMapPreviewScreen(
      Screen parent,
      AsMapVirtualManager mapVirtualManager,
      AsMapVirtualManager.RenderedImage renderedImage) {
    super(Component.literal("AS7 Image Preview"));
    this.parent = parent;
    this.mapVirtualManager = mapVirtualManager;
    this.renderedImage = renderedImage;
  }

  @Override
  protected void init() {
    recalculateLayout();
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    recalculateLayout();

    graphics.fill(0, 0, width, height, 0xC0101010);
    graphics.fill(panelLeft, panelTop, panelRight, panelBottom, 0xE01B1F23);
    graphics.fill(panelLeft, panelTop, panelRight, panelTop + 2, 0xFF2C8E95);
    graphics.fill(panelLeft, panelBottom - 1, panelRight, panelBottom, 0xFF2C8E95);
    graphics.fill(panelLeft, panelTop, panelLeft + 1, panelBottom, 0xFF2C8E95);
    graphics.fill(panelRight - 1, panelTop, panelRight, panelBottom, 0xFF2C8E95);

    graphics.drawCenteredString(
        font,
        Component.literal(
            "AS7 Image Preview " + renderedImage.mapsWide() + "x" + renderedImage.mapsHigh()),
        width / 2,
        panelTop + 8,
        0xFFE8FFFF);

    mapVirtualManager.renderPreview(
        graphics, renderedImage, previewLeft, previewTop, previewWidth, previewHeight);

    graphics.drawCenteredString(
        font,
        Component.literal("Click outside or press ESC to close"),
        width / 2,
        panelBottom - 14,
        0xFF8ED8DB);
  }

  public boolean mouseClicked(MouseButtonEvent event, boolean focused) {
    if (event.x() < panelLeft
        || event.x() > panelRight
        || event.y() < panelTop
        || event.y() > panelBottom) {
      onClose();
      return true;
    }

    return super.mouseClicked(event, focused);
  }

  @Override
  public void onClose() {
    if (minecraft != null) {
      minecraft.setScreen(parent);
    }
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  private void recalculateLayout() {
    int baseWidth = renderedImage.previewWidth();
    int baseHeight = renderedImage.previewHeight();
    float availableWidth = Math.max(128, width - OUTER_PADDING * 2 - PANEL_PADDING * 2);
    float availableHeight =
        Math.max(128, height - OUTER_PADDING * 2 - PANEL_PADDING * 2 - TITLE_GAP - FOOTER_GAP);
    float previewScale =
        Math.max(MIN_SCALE, Math.min(availableWidth / baseWidth, availableHeight / baseHeight));
    previewWidth = Math.round(baseWidth * previewScale);
    previewHeight = Math.round(baseHeight * previewScale);

    panelLeft = (width - previewWidth) / 2 - PANEL_PADDING;
    panelTop = (height - previewHeight) / 2 - PANEL_PADDING - 10;
    panelRight = panelLeft + previewWidth + PANEL_PADDING * 2;
    panelBottom = panelTop + previewHeight + PANEL_PADDING * 2 + 16;
    previewLeft = panelLeft + PANEL_PADDING;
    previewTop = panelTop + PANEL_PADDING + 18;
  }
}
