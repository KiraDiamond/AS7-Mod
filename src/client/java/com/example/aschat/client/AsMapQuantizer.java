package com.example.aschat.client;

import java.util.Arrays;
import net.minecraft.world.level.material.MapColor;

public final class AsMapQuantizer {
  private static final int MAP_SIZE = 128;
  private static final int[] PALETTE_RGB = new int[256];
  private static final byte[] PALETTE_INDEX = new byte[256];

  static {
    for (int i = 0; i < 256; i++) {
      PALETTE_INDEX[i] = (byte) i;
      PALETTE_RGB[i] = MapColor.getColorFromPackedId(i) & 0x00FFFFFF;
    }
  }

  private AsMapQuantizer() {}

  public static byte[][] quantize(
      int previewWidth, int previewHeight, int[] previewPixels, int mapsWide, int mapsHigh) {
    int pixelCount = previewWidth * previewHeight;
    float[] red = new float[pixelCount];
    float[] green = new float[pixelCount];
    float[] blue = new float[pixelCount];
    short[] colorCache = new short[4096];
    Arrays.fill(colorCache, (short) -1);

    for (int i = 0; i < pixelCount; i++) {
      int pixel = previewPixels[i];
      red[i] = pixel >> 16 & 0xFF;
      green[i] = pixel >> 8 & 0xFF;
      blue[i] = pixel & 0xFF;
    }

    byte[] quantized = new byte[pixelCount];
    for (int y = 0; y < previewHeight; y++) {
      int rowOffset = y * previewWidth;
      for (int x = 0; x < previewWidth; x++) {
        int index = rowOffset + x;
        int currentRed = clampToByte(red[index]);
        int currentGreen = clampToByte(green[index]);
        int currentBlue = clampToByte(blue[index]);
        byte packedColor = quantizePixel(currentRed, currentGreen, currentBlue, colorCache);
        quantized[index] = packedColor;

        int paletteRgb = PALETTE_RGB[Byte.toUnsignedInt(packedColor)];
        float redError = currentRed - (paletteRgb >> 16 & 0xFF);
        float greenError = currentGreen - (paletteRgb >> 8 & 0xFF);
        float blueError = currentBlue - (paletteRgb & 0xFF);

        diffuseError(red, green, blue, previewWidth, previewHeight, x + 1, y, redError, greenError, blueError, 7.0F / 16.0F);
        diffuseError(red, green, blue, previewWidth, previewHeight, x - 1, y + 1, redError, greenError, blueError, 3.0F / 16.0F);
        diffuseError(red, green, blue, previewWidth, previewHeight, x, y + 1, redError, greenError, blueError, 5.0F / 16.0F);
        diffuseError(red, green, blue, previewWidth, previewHeight, x + 1, y + 1, redError, greenError, blueError, 1.0F / 16.0F);
      }
    }

    byte[][] tiles = new byte[mapsWide * mapsHigh][MAP_SIZE * MAP_SIZE];
    for (int tileY = 0; tileY < mapsHigh; tileY++) {
      for (int tileX = 0; tileX < mapsWide; tileX++) {
        byte[] tile = tiles[tileY * mapsWide + tileX];
        for (int localY = 0; localY < MAP_SIZE; localY++) {
          int sourceRow = (tileY * MAP_SIZE + localY) * previewWidth + tileX * MAP_SIZE;
          int targetRow = localY * MAP_SIZE;
          System.arraycopy(quantized, sourceRow, tile, targetRow, MAP_SIZE);
        }
      }
    }

    return tiles;
  }

  private static void diffuseError(
      float[] red,
      float[] green,
      float[] blue,
      int width,
      int height,
      int x,
      int y,
      float redError,
      float greenError,
      float blueError,
      float factor) {
    if (x < 0 || y < 0 || x >= width || y >= height) {
      return;
    }

    int index = y * width + x;
    red[index] += redError * factor;
    green[index] += greenError * factor;
    blue[index] += blueError * factor;
  }

  private static byte quantizePixel(int red, int green, int blue, short[] colorCache) {
    int cacheKey = (red >> 4) << 8 | (green >> 4) << 4 | (blue >> 4);
    short cached = colorCache[cacheKey];
    if (cached >= 0) {
      return (byte) cached;
    }

    int bestDistance = Integer.MAX_VALUE;
    byte bestColor = 0;

    for (int i = 0; i < 256; i++) {
      int paletteRgb = PALETTE_RGB[i];
      int redDistance = red - (paletteRgb >> 16 & 0xFF);
      int greenDistance = green - (paletteRgb >> 8 & 0xFF);
      int blueDistance = blue - (paletteRgb & 0xFF);
      int distance =
          redDistance * redDistance + greenDistance * greenDistance + blueDistance * blueDistance;
      if (distance < bestDistance) {
        bestDistance = distance;
        bestColor = PALETTE_INDEX[i];
        if (distance == 0) {
          break;
        }
      }
    }

    colorCache[cacheKey] = (short) Byte.toUnsignedInt(bestColor);
    return bestColor;
  }

  private static int clampToByte(float value) {
    return Math.max(0, Math.min(255, Math.round(value)));
  }
}
