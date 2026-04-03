package com.example.aschat.client;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public final class AsMapVirtualManager {
  private static final int MAX_CACHE_ENTRIES = 12;
  private static final int MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024;
  private static final int MAP_SIZE = 128;
  private static final int PREVIEW_BACKGROUND = 0xFFD0D0D0;
  private static final int MAX_PREVIEW_DIMENSION = 2048;
  private static final String PREVIEW_INSERTION_PREFIX = "aspreview:";
  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private final Map<String, RenderedImage> cache =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RenderedImage> eldest) {
          return size() > MAX_CACHE_ENTRIES;
        }
      };
  private final Map<String, CompletableFuture<RenderedImage>> inFlight = new ConcurrentHashMap<>();
  private final Map<String, PreviewEntry> previews = new ConcurrentHashMap<>();
  private final AtomicInteger previewCounter = new AtomicInteger(1);
  private final AtomicInteger mapIdCounter = new AtomicInteger(1_000_000);

  private ClientLevel lastLevel;

  public void tick(Minecraft client) {
    if (client.level != lastLevel) {
      lastLevel = client.level;
      synchronized (cache) {
        cache.clear();
      }
      previews.clear();
      inFlight.clear();
    }
  }

  public String registerPreview(AsMapEmbedCodec.AsMapEmbed embed) {
    String previewId = "img" + Integer.toHexString(previewCounter.getAndIncrement());
    previews.put(previewId, new PreviewEntry(embed));
    return previewId;
  }

  public void beginRender(String previewId, AsMapEmbedCodec.AsMapEmbed embed) {
    render(embed)
        .whenComplete(
            (renderedImage, throwable) -> {
              PreviewEntry preview = previews.get(previewId);
              if (preview == null) {
                return;
              }

              if (throwable != null) {
                preview.errorMessage = AsChatClientMod.rootMessage(throwable);
                return;
              }

              preview.renderedImage = renderedImage;
            });
  }

  public String previewStatusMessage(String previewId) {
    PreviewEntry preview = previews.get(previewId);
    if (preview == null) {
      return "AS7 image preview is no longer available.";
    }
    if (preview.errorMessage != null) {
      return "AS7 map embed failed: " + preview.errorMessage;
    }
    if (preview.renderedImage == null) {
      return "AS7 image preview is still loading.";
    }
    return null;
  }

  public AsMapPreviewScreen createPreviewScreen(Screen parent, String previewId) {
    PreviewEntry preview = previews.get(previewId);
    if (preview == null || preview.renderedImage == null) {
      return null;
    }

    return new AsMapPreviewScreen(parent, this, preview.renderedImage);
  }

  public MutableComponent createViewComponent(String previewId) {
    return Component.literal("[View Image]")
        .withStyle(
            style ->
                style.withBold(false)
                    .applyFormat(ChatFormatting.UNDERLINE)
                    .withColor(ChatFormatting.AQUA)
                    .withInsertion(PREVIEW_INSERTION_PREFIX + previewId)
                    .withHoverEvent(
                        new HoverEvent.ShowText(
                            Component.literal("Hover to preview AS7 image"))));
  }

  public MutableComponent createReferenceComponent(RenderedImage renderedImage) {
    MutableComponent result = Component.empty().withStyle(style -> style.withBold(false));
    result.append(
        Component.literal("maps: ")
            .withStyle(style -> style.withBold(false).withColor(ChatFormatting.GRAY)));

    for (int i = 0; i < renderedImage.mapIds().size(); i++) {
      if (i > 0) {
        result.append(Component.literal(" ").withStyle(style -> style.withBold(false)));
      }
      result.append(createMapLink(renderedImage.mapIds().get(i), i + 1));
    }

    return result;
  }

  public void renderPreview(
      GuiGraphics guiGraphics,
      RenderedImage renderedImage,
      int left,
      int top,
      int targetWidth,
      int targetHeight) {
    int[] previewPixels = renderedImage.previewPixels();
    int sourceWidth = renderedImage.previewWidth();
    int sourceHeight = renderedImage.previewHeight();
    if (targetWidth <= 0 || targetHeight <= 0) {
      return;
    }

    for (int y = 0; y < targetHeight; y++) {
      int screenY = top + y;
      int x = 0;
      while (x < targetWidth) {
        int color =
            sampleBilinear(
                previewPixels, sourceWidth, sourceHeight, targetWidth, targetHeight, x, y);
        int runStart = x;
        x++;
        while (x < targetWidth
            && sampleBilinear(
                    previewPixels, sourceWidth, sourceHeight, targetWidth, targetHeight, x, y)
                == color) {
          x++;
        }

        guiGraphics.fill(left + runStart, screenY, left + x, screenY + 1, color);
      }
    }
  }

  public boolean renderHoverPreview(GuiGraphics guiGraphics, Style style, int mouseX, int mouseY) {
    if (style == null) {
      return false;
    }

    String insertion = style.getInsertion();
    if (insertion == null || !insertion.startsWith(PREVIEW_INSERTION_PREFIX)) {
      return false;
    }

    String previewId = insertion.substring(PREVIEW_INSERTION_PREFIX.length());
    String status = previewStatusMessage(previewId);
    if (status != null) {
      guiGraphics.setTooltipForNextFrame(
          Minecraft.getInstance().font, Component.literal(status), mouseX, mouseY);
      return true;
    }

    PreviewEntry preview = previews.get(previewId);
    if (preview == null || preview.renderedImage == null) {
      return false;
    }

    renderHoverPreview(guiGraphics, preview.renderedImage, mouseX, mouseY);
    return true;
  }

  public CompletableFuture<RenderedImage> render(AsMapEmbedCodec.AsMapEmbed embed) {
    Minecraft client = Minecraft.getInstance();
    tick(client);
    if (client.level == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Join a world before viewing AS map embeds."));
    }

    String cacheKey = embed.cacheKey();
    synchronized (cache) {
      RenderedImage cached = cache.get(cacheKey);
      if (cached != null) {
        return CompletableFuture.completedFuture(cached);
      }
    }

    return inFlight.computeIfAbsent(
        cacheKey,
        ignored ->
            fetchImage(embed)
                .thenApply(
                    image -> {
                      BufferedImage mapImage =
                          createFittedImage(
                              image,
                              embed.mapsWide() * MAP_SIZE,
                              embed.mapsHigh() * MAP_SIZE,
                              embed.fitMode());
                      int mapWidth = mapImage.getWidth();
                      int mapHeight = mapImage.getHeight();
                      int[] mapPixels = mapImage.getRGB(0, 0, mapWidth, mapHeight, null, 0, mapWidth);

                      int[] previewCanvasSize =
                          computePreviewCanvasSize(image.getWidth(), image.getHeight());
                      BufferedImage previewImage =
                          createPreviewImage(image, previewCanvasSize[0], previewCanvasSize[1]);
                      int previewWidth = previewCanvasSize[0];
                      int previewHeight = previewCanvasSize[1];
                      int[] previewPixels =
                          previewImage.getRGB(0, 0, previewWidth, previewHeight, null, 0, previewWidth);
                      return new PreparedImage(
                          AsMapQuantizer.quantize(
                              mapWidth,
                              mapHeight,
                              mapPixels,
                              embed.mapsWide(),
                              embed.mapsHigh()),
                          previewWidth,
                          previewHeight,
                          previewPixels,
                          embed.mapsWide(),
                          embed.mapsHigh());
                    })
                .thenCompose(prepared -> publish(cacheKey, prepared))
                .whenComplete((ignoredResult, ignoredThrowable) -> inFlight.remove(cacheKey)));
  }

  private CompletableFuture<RenderedImage> publish(String cacheKey, PreparedImage preparedImage) {
    CompletableFuture<RenderedImage> result = new CompletableFuture<>();
    AsChatClientMod.runOnClient(
        () -> {
          try {
            Minecraft client = Minecraft.getInstance();
            tick(client);
            ClientLevel level = client.level;
            if (level == null) {
              throw new IllegalStateException("World changed before AS map embed finished.");
            }

            List<MapId> mapIds = new ArrayList<>(preparedImage.tiles().length);
            for (byte[] tile : preparedImage.tiles()) {
              MapId mapId = new MapId(mapIdCounter.getAndIncrement());
              MapItemSavedData mapData =
                  MapItemSavedData.createForClient((byte) 0, true, level.dimension());
              mapData.colors = tile.clone();
              level.overrideMapData(mapId, mapData);
              mapIds.add(mapId);
            }

            RenderedImage rendered =
                new RenderedImage(
                    preparedImage.previewWidth(),
                    preparedImage.previewHeight(),
                    preparedImage.previewPixels(),
                    preparedImage.mapsWide(),
                    preparedImage.mapsHigh(),
                    mapIds);
            synchronized (cache) {
              cache.put(cacheKey, rendered);
            }
            result.complete(rendered);
          } catch (Throwable throwable) {
            result.completeExceptionally(throwable);
          }
        });
    return result;
  }

  private static CompletableFuture<BufferedImage> fetchImage(AsMapEmbedCodec.AsMapEmbed embed) {
    String resolvedUrl = embed.resolvedImageUrl();
    if (resolvedUrl == null || resolvedUrl.isBlank()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("AS map embed has no usable image URL."));
    }

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(resolvedUrl))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "ASChat-ImageMaps/1")
            .GET()
            .build();

    return HTTP_CLIENT
        .sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
        .thenApply(
            response -> {
              if (response.statusCode() >= 400) {
                throw new IllegalStateException(
                    "Image server returned HTTP " + response.statusCode());
              }

              byte[] body = response.body();
              if (body.length == 0) {
                throw new IllegalStateException("Image download was empty.");
              }

              if (body.length > MAX_DOWNLOAD_BYTES) {
                throw new IllegalStateException("Image download exceeds 5 MB.");
              }

              try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(body));
                if (image == null) {
                  throw new IllegalStateException("Image decode failed.");
                }
                return image;
              } catch (IOException exception) {
                throw new IllegalStateException("Image decode failed.");
              }
            });
  }

  private MutableComponent createMapLink(MapId mapId, int index) {
    ItemStack stack = createPreviewStack(mapId, index);

    return Component.literal("[Map " + index + "]")
        .withStyle(
            style ->
                style.withBold(false)
                    .applyFormat(ChatFormatting.UNDERLINE)
                    .withColor(ChatFormatting.AQUA)
                    .withHoverEvent(createHoverItem(stack)));
  }

  private static ItemStack createPreviewStack(MapId mapId, int index) {
    ItemStack stack = new ItemStack(Items.FILLED_MAP);
    stack.set(DataComponents.MAP_ID, mapId);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("AS7 Map " + index));
    return stack;
  }

  private static HoverEvent createHoverItem(ItemStack itemStack) {
    return new HoverEvent.ShowItem(itemStack);
  }

  private static BufferedImage createFittedImage(
      BufferedImage sourceImage, int targetWidth, int targetHeight, String fitMode) {
    int sourceWidth = Math.max(sourceImage.getWidth(), 1);
    int sourceHeight = Math.max(sourceImage.getHeight(), 1);
    boolean cover = "cover".equalsIgnoreCase(fitMode);
    double scale =
        cover
            ? Math.max((double) targetWidth / sourceWidth, (double) targetHeight / sourceHeight)
            : Math.min((double) targetWidth / sourceWidth, (double) targetHeight / sourceHeight);
    int scaledWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
    int scaledHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
    int offsetX = (targetWidth - scaledWidth) / 2;
    int offsetY = (targetHeight - scaledHeight) / 2;

    BufferedImage fittedImage =
        new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = fittedImage.createGraphics();
    try {
      graphics.setColor(new Color(PREVIEW_BACKGROUND, true));
      graphics.fillRect(0, 0, targetWidth, targetHeight);
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.drawImage(sourceImage, offsetX, offsetY, scaledWidth, scaledHeight, null);
    } finally {
      graphics.dispose();
    }

    return fittedImage;
  }

  private static BufferedImage createPreviewImage(
      BufferedImage sourceImage, int targetWidth, int targetHeight) {
    BufferedImage previewImage =
        new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = previewImage.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.drawImage(sourceImage, 0, 0, targetWidth, targetHeight, null);
    } finally {
      graphics.dispose();
    }

    return previewImage;
  }

  private void renderHoverPreview(
      GuiGraphics guiGraphics, RenderedImage renderedImage, int mouseX, int mouseY) {
    int panelPadding = 6;
    int maxPreviewWidth = Math.max(96, guiGraphics.guiWidth() / 2);
    int availableAbove = Math.max(32, mouseY - 20 - panelPadding * 2);
    int maxPreviewHeight = Math.min(guiGraphics.guiHeight() / 2, availableAbove);
    float widthScale = (float) maxPreviewWidth / renderedImage.previewWidth();
    float heightScale = (float) maxPreviewHeight / renderedImage.previewHeight();
    float scale = Math.min(widthScale, heightScale);
    int previewWidth = Math.max(1, Math.round(renderedImage.previewWidth() * scale));
    int previewHeight = Math.max(1, Math.round(renderedImage.previewHeight() * scale));
    int panelWidth = previewWidth + panelPadding * 2;
    int panelHeight = previewHeight + panelPadding * 2;
    int left = mouseX + 12;
    if (left + panelWidth > guiGraphics.guiWidth() - 8) {
      left = mouseX - panelWidth - 12;
    }

    int top = mouseY - panelHeight - 12;
    left = clamp(left, 8, Math.max(8, guiGraphics.guiWidth() - panelWidth - 8));
    top = Math.max(8, top);
    int right = left + panelWidth;
    int bottom = top + panelHeight;

    guiGraphics.nextStratum();
    guiGraphics.fill(left, top, right, bottom, 0xF01B1F23);
    guiGraphics.fill(left, top, right, top + 1, 0xFF2C8E95);
    guiGraphics.fill(left, bottom - 1, right, bottom, 0xFF2C8E95);
    guiGraphics.fill(left, top, left + 1, bottom, 0xFF2C8E95);
    guiGraphics.fill(right - 1, top, right, bottom, 0xFF2C8E95);
    renderPreview(
        guiGraphics,
        renderedImage,
        left + panelPadding,
        top + panelPadding,
        previewWidth,
        previewHeight);
  }

  private static int[] computePreviewCanvasSize(int sourceWidth, int sourceHeight) {
    int safeWidth = Math.max(1, sourceWidth);
    int safeHeight = Math.max(1, sourceHeight);
    int longestEdge = Math.max(safeWidth, safeHeight);
    if (longestEdge <= 0) {
      return new int[] {MAP_SIZE, MAP_SIZE};
    }

    double scale = Math.min((double) MAX_PREVIEW_DIMENSION / longestEdge, 1.0D);
    int previewWidth = Math.max(1, (int) Math.round(safeWidth * scale));
    int previewHeight = Math.max(1, (int) Math.round(safeHeight * scale));
    return new int[] {previewWidth, previewHeight};
  }

  private static int sampleBilinear(
      int[] pixels,
      int sourceWidth,
      int sourceHeight,
      int targetWidth,
      int targetHeight,
      int targetX,
      int targetY) {
    if (sourceWidth == targetWidth && sourceHeight == targetHeight) {
      return pixels[targetY * sourceWidth + targetX];
    }

    float sourceX = ((targetX + 0.5F) * sourceWidth / targetWidth) - 0.5F;
    float sourceY = ((targetY + 0.5F) * sourceHeight / targetHeight) - 0.5F;

    int x0 = clamp((int) Math.floor(sourceX), 0, sourceWidth - 1);
    int y0 = clamp((int) Math.floor(sourceY), 0, sourceHeight - 1);
    int x1 = clamp(x0 + 1, 0, sourceWidth - 1);
    int y1 = clamp(y0 + 1, 0, sourceHeight - 1);

    float tx = sourceX - x0;
    float ty = sourceY - y0;

    int c00 = pixels[y0 * sourceWidth + x0];
    int c10 = pixels[y0 * sourceWidth + x1];
    int c01 = pixels[y1 * sourceWidth + x0];
    int c11 = pixels[y1 * sourceWidth + x1];

    int alpha = bilerp(c00 >>> 24, c10 >>> 24, c01 >>> 24, c11 >>> 24, tx, ty);
    int red =
        bilerp(
            (c00 >>> 16) & 0xFF,
            (c10 >>> 16) & 0xFF,
            (c01 >>> 16) & 0xFF,
            (c11 >>> 16) & 0xFF,
            tx,
            ty);
    int green =
        bilerp(
            (c00 >>> 8) & 0xFF,
            (c10 >>> 8) & 0xFF,
            (c01 >>> 8) & 0xFF,
            (c11 >>> 8) & 0xFF,
            tx,
            ty);
    int blue = bilerp(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, tx, ty);
    return alpha << 24 | red << 16 | green << 8 | blue;
  }

  private static int bilerp(
      int topLeft, int topRight, int bottomLeft, int bottomRight, float tx, float ty) {
    float top = topLeft + (topRight - topLeft) * tx;
    float bottom = bottomLeft + (bottomRight - bottomLeft) * tx;
    return clamp(Math.round(top + (bottom - top) * ty), 0, 255);
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  public record RenderedImage(
      int previewWidth,
      int previewHeight,
      int[] previewPixels,
      int mapsWide,
      int mapsHigh,
      List<MapId> mapIds) {}

  private record PreparedImage(
      byte[][] tiles,
      int previewWidth,
      int previewHeight,
      int[] previewPixels,
      int mapsWide,
      int mapsHigh) {}

  private static final class PreviewEntry {
    private final AsMapEmbedCodec.AsMapEmbed embed;
    private volatile RenderedImage renderedImage;
    private volatile String errorMessage;

    private PreviewEntry(AsMapEmbedCodec.AsMapEmbed embed) {
      this.embed = embed;
    }
  }
}
