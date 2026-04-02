package com.example.aschat.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public class AstLookupClient {
  private static final String WYNNCRAFT_PLAYER_ENDPOINT = "https://api.wynncraft.com/v3/player/";

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public CompletableFuture<PlayerSummary> fetchPlayerSummary(String username) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(WYNNCRAFT_PLAYER_ENDPOINT + encode(username)))
            .timeout(Duration.ofSeconds(6))
            .GET()
            .build();

    return httpClient
        .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .thenApply(
            response -> {
              if (response.statusCode() >= 400) {
                throw new AsChatSecurity.HttpStatusException(response.statusCode());
              }

              return parsePlayerSummary(response.body(), username);
            });
  }

  private static PlayerSummary parsePlayerSummary(String body, String requestedUsername) {
    JsonElement root;
    try {
      root = JsonParser.parseString(body);
    } catch (RuntimeException exception) {
      throw new AsChatSecurity.InvalidLookupResponseException(
          "Unexpected Wynncraft API response");
    }
    if (!root.isJsonObject()) {
      throw new AsChatSecurity.InvalidLookupResponseException("Unexpected Wynncraft API response");
    }

    JsonObject object = root.getAsJsonObject();
    if (!object.has("username")) {
      if (object.has("message")) {
        throw new AsChatSecurity.LookupNotFoundException();
      }

      throw new AsChatSecurity.LookupNotFoundException();
    }

    String username = stringValue(object, "username", requestedUsername);
    JsonObject guild =
        object.has("guild") && object.get("guild").isJsonObject()
            ? object.getAsJsonObject("guild")
            : null;
    JsonObject globalData =
        object.has("globalData") && object.get("globalData").isJsonObject()
            ? object.getAsJsonObject("globalData")
            : null;
    JsonObject guildRaids =
        globalData != null
                && globalData.has("guildRaids")
                && globalData.get("guildRaids").isJsonObject()
            ? globalData.getAsJsonObject("guildRaids")
            : null;
    JsonObject raids =
        globalData != null && globalData.has("raids") && globalData.get("raids").isJsonObject()
            ? globalData.getAsJsonObject("raids")
            : null;
    JsonObject raidList =
        raids != null && raids.has("list") && raids.get("list").isJsonObject()
            ? raids.getAsJsonObject("list")
            : null;
    JsonObject restrictions =
        object.has("restrictions") && object.get("restrictions").isJsonObject()
            ? object.getAsJsonObject("restrictions")
            : null;

    try {
      return new PlayerSummary(
          username,
          guild == null ? "No guild" : stringValue(guild, "name", "No guild"),
          guild == null ? "-" : stringValue(guild, "rank", "-"),
          parseInstant(stringValue(object, "lastJoin", "")),
          stringValue(object, "server", "Offline"),
          restrictions != null && booleanValue(restrictions, "onlineStatus", false),
          globalData == null ? 0 : intValue(globalData, "wars"),
          guildRaids == null ? 0 : intValue(guildRaids, "total"),
          raidCount(raidList, "Orphion's Nexus of Light"),
          raidCount(raidList, "The Nameless Anomaly"),
          raidCount(raidList, "Nest of the Grootslangs"),
          raidCount(raidList, "The Canyon Colossus"));
    } catch (RuntimeException exception) {
      throw new AsChatSecurity.InvalidLookupResponseException(
          "Unexpected Wynncraft API response");
    }
  }

  private static int raidCount(JsonObject raidList, String key) {
    if (raidList == null || !raidList.has(key)) {
      return 0;
    }

    return raidList.get(key).getAsInt();
  }

  private static String stringValue(JsonObject object, String key, String fallback) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
      return fallback;
    }

    return object.get(key).getAsString();
  }

  private static int intValue(JsonObject object, String key) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
      return 0;
    }

    return object.get(key).getAsInt();
  }

  private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
      return fallback;
    }

    return object.get(key).getAsBoolean();
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return Instant.EPOCH;
    }

    try {
      return Instant.parse(value);
    } catch (Exception ignored) {
      return Instant.EPOCH;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public record PlayerSummary(
      String username,
      String guildName,
      String guildRank,
      Instant lastJoin,
      String server,
      boolean apiDisabled,
      int wars,
      int guildRaidsTotal,
      int nol,
      int tna,
      int notg,
      int tcc) {}
}
