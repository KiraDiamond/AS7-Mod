package com.example.aschat.client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AsChatRelayClient {
  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final AsChatConfig config;

  public AsChatRelayClient(AsChatConfig config) {
    this.config = config;
  }

  public CompletableFuture<Void> sendMessage(String sender, String message) {
    String body =
        formField("sender", sender)
            + "&"
            + formField("token", config.authToken())
            + "&"
            + formField("message", message);

    HttpRequest request =
        HttpRequest.newBuilder(endpoint("/messages"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    return httpClient
        .sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .thenAccept(
            response -> {
              if (response.statusCode() >= 400) {
                throw new IllegalStateException("Relay returned HTTP " + response.statusCode());
              }
            });
  }

  public CompletableFuture<List<RelayMessage>> pollMessages(long sinceId) {
    HttpRequest request =
        HttpRequest.newBuilder(
                endpoint("/messages?since=" + sinceId + "&token=" + encode(config.authToken())))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

    return httpClient
        .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .thenApply(
            response -> {
              if (response.statusCode() >= 400) {
                throw new IllegalStateException("Relay returned HTTP " + response.statusCode());
              }

              return parseMessages(response.body());
            });
  }

  public CompletableFuture<Integer> fetchPlaytimeMinutes(String player) {
    HttpRequest request =
        HttpRequest.newBuilder(
                endpoint(
                    "/playtime?player=" + encode(player) + "&token=" + encode(config.authToken())))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();

    return httpClient
        .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .thenApply(
            response -> {
              if (response.statusCode() >= 400) {
                throw new IllegalStateException("Relay returned HTTP " + response.statusCode());
              }

              try {
                return Integer.parseInt(response.body().trim());
              } catch (NumberFormatException exception) {
                throw new IllegalStateException("Relay returned invalid playtime data");
              }
            });
  }

  private URI endpoint(String pathAndQuery) {
    return URI.create(config.relayUrl() + pathAndQuery);
  }

  private static String formField(String key, String value) {
    return encode(key) + "=" + encode(value);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static List<RelayMessage> parseMessages(String body) {
    List<RelayMessage> messages = new ArrayList<>();
    if (body.isBlank()) {
      return messages;
    }

    String[] lines = body.split("\\R");
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }

      String[] parts = line.split("\\|", 3);
      if (parts.length != 3) {
        continue;
      }

      try {
        long id = Long.parseLong(parts[0]);
        String sender = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        String message = java.net.URLDecoder.decode(parts[2], StandardCharsets.UTF_8);
        messages.add(new RelayMessage(id, sender, message));
      } catch (IllegalArgumentException ignored) {
      }
    }

    return messages;
  }

  public record RelayMessage(long id, String sender, String message) {}
}
