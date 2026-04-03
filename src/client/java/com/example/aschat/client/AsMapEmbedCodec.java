package com.example.aschat.client;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

public final class AsMapEmbedCodec {
  private static final String PREFIX = "asmap:";
  private static final int MAX_MAPS_PER_AXIS = 4;
  private static final int MAX_TOTAL_MAPS = 16;

  private AsMapEmbedCodec() {}

  public static String normalizeOutgoingImageUrl(String rawUrl) {
    return normalizeUrl(rawUrl);
  }

  public static Optional<EmbedMatch> findFirst(String message) {
    if (message == null || message.isBlank()) {
      return Optional.empty();
    }

    int prefixIndex = message.toLowerCase(Locale.ROOT).indexOf(PREFIX);
    if (prefixIndex < 0) {
      return Optional.empty();
    }

    int end = findTokenEnd(message, prefixIndex);
    String token = message.substring(prefixIndex, end);
    return Optional.of(new EmbedMatch(prefixIndex, end, token, parseToken(token)));
  }

  public static void validateOutgoingMessage(String message) {
    if (message == null || message.isBlank()) {
      return;
    }

    Optional<EmbedMatch> first = findFirst(message);
    if (first.isEmpty()) {
      return;
    }

    String remaining = message.substring(first.get().end());
    if (remaining.toLowerCase(Locale.ROOT).contains(PREFIX)) {
      throw new IllegalArgumentException("Only one AS map embed is allowed per message.");
    }
  }

  public static String replaceFirstToken(String message, String replacement) {
    Optional<EmbedMatch> match = findFirst(message);
    if (match.isEmpty()) {
      return message;
    }

    return message.substring(0, match.get().start())
        + replacement
        + message.substring(match.get().end());
  }

  private static AsMapEmbed parseToken(String token) {
    if (!token.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
      throw new IllegalArgumentException("Invalid AS map embed.");
    }

    String body = token.substring(PREFIX.length());
    if (body.isBlank()) {
      throw new IllegalArgumentException("AS map embed is missing a URL.");
    }

    String[] parts = body.split("\\|", -1);
    if (parts.length > 5) {
      throw new IllegalArgumentException("AS map embed has too many fields.");
    }

    String sourceUrl = normalizeUrl(parts[0]);
    int mapsWide = parsePositiveInt(parts, 1, 1);
    int mapsHigh = parsePositiveInt(parts, 2, 1);
    String fitMode = parseFitMode(parts, 3);
    String posterUrl = parseOptionalUrl(parts, 4);

    if (mapsWide > MAX_MAPS_PER_AXIS || mapsHigh > MAX_MAPS_PER_AXIS) {
      throw new IllegalArgumentException("AS map embeds support at most 4 maps per side.");
    }

    if (mapsWide * mapsHigh > MAX_TOTAL_MAPS) {
      throw new IllegalArgumentException("AS map embeds support at most 16 maps total.");
    }

    boolean gifSource = isGifUrl(sourceUrl);
    if (gifSource && posterUrl == null) {
      throw new IllegalArgumentException("GIF embeds require a poster image URL.");
    }

    return new AsMapEmbed(sourceUrl, mapsWide, mapsHigh, fitMode, posterUrl);
  }

  private static int findTokenEnd(String message, int start) {
    int end = start;
    while (end < message.length() && !Character.isWhitespace(message.charAt(end))) {
      end++;
    }
    return end;
  }

  private static int parsePositiveInt(String[] parts, int index, int fallback) {
    if (parts.length <= index || parts[index].isBlank()) {
      return fallback;
    }

    try {
      int value = Integer.parseInt(parts[index]);
      if (value <= 0) {
        throw new IllegalArgumentException("AS map embed dimensions must be positive.");
      }
      return value;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("AS map embed dimensions must be whole numbers.");
    }
  }

  private static String parseFitMode(String[] parts, int index) {
    if (parts.length <= index || parts[index].isBlank()) {
      return "cover";
    }

    String value = parts[index].trim().toLowerCase(Locale.ROOT);
    if (!"contain".equals(value) && !"cover".equals(value)) {
      throw new IllegalArgumentException("AS map embeds support contain or cover mode.");
    }

    return value;
  }

  private static String parseOptionalUrl(String[] parts, int index) {
    if (parts.length <= index || parts[index].isBlank()) {
      return null;
    }

    return normalizeUrl(parts[index]);
  }

  private static String normalizeUrl(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank()) {
      throw new IllegalArgumentException("AS map embed URL cannot be blank.");
    }

    URI uri;
    try {
      uri = URI.create(rawUrl.trim()).normalize();
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("AS map embed URL is invalid.");
    }

    String scheme = uri.getScheme();
    if (scheme == null
        || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
      throw new IllegalArgumentException("AS map embeds require an http or https URL.");
    }

    return uri.toString();
  }

  private static boolean isGifUrl(String url) {
    String normalized = url.toLowerCase(Locale.ROOT);
    int queryIndex = normalized.indexOf('?');
    int hashIndex = normalized.indexOf('#');
    int end = normalized.length();
    if (queryIndex >= 0) {
      end = Math.min(end, queryIndex);
    }
    if (hashIndex >= 0) {
      end = Math.min(end, hashIndex);
    }
    return normalized.substring(0, end).endsWith(".gif");
  }

  public record EmbedMatch(int start, int end, String token, AsMapEmbed embed) {}

  public record AsMapEmbed(
      String sourceUrl, int mapsWide, int mapsHigh, String fitMode, String posterUrl) {
    public String resolvedImageUrl() {
      return isGifUrl(sourceUrl) ? posterUrl : sourceUrl;
    }

    public String cacheKey() {
      return sourceUrl + "|" + mapsWide + "|" + mapsHigh + "|" + fitMode + "|" + posterUrl;
    }

    public String placeholderText() {
      return "[image " + mapsWide + "x" + mapsHigh + "]";
    }
  }
}
