package com.example.aschat.client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.io.InputStream;
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
                throw new AsChatSecurity.HttpStatusException(response.statusCode());
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
        .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        .thenApply(
            response -> {
              try (InputStream body = response.body()) {
                if (response.statusCode() >= 400) {
                  throw new AsChatSecurity.HttpStatusException(response.statusCode());
                }

                return parseMessages(
                    readBody(body, AsChatSecurity.MAX_RELAY_RESPONSE_BYTES));
              } catch (IOException exception) {
                throw new IllegalStateException("Failed to read relay response", exception);
              }
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
        .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        .thenApply(
            response -> {
              try (InputStream body = response.body()) {
                if (response.statusCode() >= 400) {
                  throw new AsChatSecurity.HttpStatusException(response.statusCode());
                }

                try {
                  return Integer.parseInt(
                      readBody(body, AsChatSecurity.MAX_PLAYTIME_RESPONSE_BYTES).trim());
                } catch (NumberFormatException exception) {
                  throw new AsChatSecurity.InvalidRelayResponseException(
                      "Relay returned invalid playtime data");
                }
              } catch (IOException exception) {
                throw new IllegalStateException("Failed to read relay response", exception);
              }
            });
  }

  private URI endpoint(String pathAndQuery) {
    return config.relayEndpoint().resolve(pathAndQuery);
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
      if (messages.size() >= AsChatSecurity.MAX_RELAY_MESSAGES_PER_BATCH) {
        break;
      }
      if (line.isBlank()) {
        continue;
      }

      String[] parts = line.split("\\|", 3);
      if (parts.length != 3) {
        continue;
      }

      try {
        long id = Long.parseLong(parts[0]);
        if (id <= 0) {
          continue;
        }

        String sender =
            AsChatSecurity.sanitizeInboundText(
                java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        String message =
            AsChatSecurity.sanitizeInboundText(
                java.net.URLDecoder.decode(parts[2], StandardCharsets.UTF_8));
        if (!AsChatSecurity.isValidRelaySender(sender)
            || sender.length() > AsChatSecurity.MAX_RELAY_SENDER_LENGTH
            || message.isEmpty()
            || message.length() > AsChatSecurity.MAX_RELAY_MESSAGE_LENGTH) {
          continue;
        }

        messages.add(new RelayMessage(id, sender, message));
      } catch (IllegalArgumentException ignored) {
      }
    }

    return messages;
  }

  private static String readBody(InputStream inputStream, int maxBytes) throws IOException {
    byte[] bytes = inputStream.readNBytes(maxBytes + 1);
    if (bytes.length > maxBytes) {
      throw new AsChatSecurity.InvalidRelayResponseException("Relay response exceeded size limit");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  public record RelayMessage(long id, String sender, String message) {}
}
