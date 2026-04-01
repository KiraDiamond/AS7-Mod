package relay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class AsChatRelayServer {
  private static final int DEFAULT_PORT = 8787;
  private static final int MAX_MESSAGE_LENGTH = 256;
  private static final int MAX_SENDER_LENGTH = 64;
  private static final int MAX_MESSAGES = 200;
  private static final String DEFAULT_PLAYTIME_SQL =
      "SELECT COALESCE(ROUND(SUM(playtime_hours) * 60, 0), 0) FROM daily_playtime WHERE username ="
          + " ? COLLATE NOCASE AND date >= date('now', '-13 days')";

  private final AtomicLong nextMessageId = new AtomicLong(1);
  private final List<RelayMessage> messages = new ArrayList<>();
  private final String authToken;
  private final Set<String> allowedUsers;
  private final boolean logMessages;
  private final String playtimeDbPath;
  private final String playtimeSql;

  public AsChatRelayServer(
      String authToken,
      Set<String> allowedUsers,
      boolean logMessages,
      String playtimeDbPath,
      String playtimeSql) {
    this.authToken = authToken == null ? "" : authToken.trim();
    this.allowedUsers = allowedUsers;
    this.logMessages = logMessages;
    this.playtimeDbPath = valueOrDefault(playtimeDbPath, "");
    this.playtimeSql = valueOrDefault(playtimeSql, "");
  }

  public static void main(String[] args) throws IOException {
    RelayConfig config = RelayConfig.load(Path.of("relay.properties"));
    int port = args.length > 0 ? Integer.parseInt(args[0]) : config.port();
    String authToken =
        args.length > 1
            ? args[1]
            : valueOrDefault(System.getenv("ASCHAT_TOKEN"), config.authToken());
    AsChatRelayServer relayServer =
        new AsChatRelayServer(
            authToken,
            config.allowedUsers(),
            config.logMessages(),
            config.playtimeDbPath(),
            config.playtimeSql());

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/messages", relayServer.new MessagesHandler());
    server.createContext("/playtime", relayServer.new PlaytimeHandler());
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();

    System.out.println("AS Chat relay listening on port " + port);
    System.out.println("Token protection: " + (authToken.isEmpty() ? "off" : "on"));
    System.out.println(
        "Username allowlist: " + (config.allowedUsers().isEmpty() ? "off" : config.allowedUsers()));
    System.out.println(
        "Playtime lookup: "
            + (config.playtimeDbPath().isBlank() || config.playtimeSql().isBlank() ? "off" : "on"));
  }

  private final class MessagesHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      try {
        String method = exchange.getRequestMethod();
        if ("POST".equalsIgnoreCase(method)) {
          handlePost(exchange);
          return;
        }
        if ("GET".equalsIgnoreCase(method)) {
          handleGet(exchange);
          return;
        }

        sendResponse(exchange, 405, "Method not allowed");
      } catch (IllegalArgumentException exception) {
        sendResponse(exchange, 400, exception.getMessage());
      }
    }
  }

  private final class PlaytimeHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      try {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
          sendResponse(exchange, 405, "Method not allowed");
          return;
        }

        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
        requireToken(query.getOrDefault("token", ""));
        String player = requireField(query, "player", MAX_SENDER_LENGTH);
        sendResponse(exchange, 200, Integer.toString(queryPlaytimeMinutes(player)));
      } catch (IllegalArgumentException exception) {
        sendResponse(exchange, 400, exception.getMessage());
      } catch (IllegalStateException exception) {
        sendResponse(exchange, 501, exception.getMessage());
      }
    }
  }

  private void handlePost(HttpExchange exchange) throws IOException {
    Map<String, String> fields = parseForm(readBody(exchange));
    requireToken(fields.getOrDefault("token", ""));
    String sender = requireField(fields, "sender", MAX_SENDER_LENGTH);
    requireAllowedSender(sender);
    String message = requireField(fields, "message", MAX_MESSAGE_LENGTH);

    RelayMessage relayMessage =
        new RelayMessage(
            nextMessageId.getAndIncrement(), sender, message, Instant.now().toEpochMilli());
    synchronized (messages) {
      messages.add(relayMessage);
      if (messages.size() > MAX_MESSAGES) {
        messages.remove(0);
      }
    }

    if (logMessages) {
      System.out.println(
          "[" + relayMessage.id() + "] " + relayMessage.sender() + ": " + relayMessage.message());
    }

    sendResponse(exchange, 204, "");
  }

  private void handleGet(HttpExchange exchange) throws IOException {
    Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
    requireToken(query.getOrDefault("token", ""));
    long since = parseSince(query.getOrDefault("since", "0"));

    StringBuilder response = new StringBuilder();

    synchronized (messages) {
      messages.stream()
          .filter(message -> message.id() > since)
          .sorted(Comparator.comparingLong(RelayMessage::id))
          .forEach(
              message ->
                  response
                      .append(message.id())
                      .append('|')
                      .append(encode(message.sender()))
                      .append('|')
                      .append(encode(message.message()))
                      .append('\n'));
    }

    sendResponse(exchange, 200, response.toString());
  }

  private void requireToken(String providedToken) {
    if (!authToken.isEmpty() && !authToken.equals(providedToken)) {
      throw new IllegalArgumentException("Invalid token");
    }
  }

  private void requireAllowedSender(String sender) {
    if (!allowedUsers.isEmpty() && !allowedUsers.contains(sender.trim().toLowerCase())) {
      throw new IllegalArgumentException("Sender not allowed");
    }
  }

  private int queryPlaytimeMinutes(String player) {
    if (playtimeDbPath.isBlank() || playtimeSql.isBlank()) {
      throw new IllegalStateException("Playtime lookup not configured");
    }

    String script =
        """
import sqlite3
import sys

db_path, sql, player = sys.argv[1:4]
connection = sqlite3.connect(db_path)
try:
    cursor = connection.cursor()
    row = cursor.execute(sql, (player,)).fetchone()
    value = 0 if row is None or row[0] is None else int(float(row[0]))
    print(value)
finally:
    connection.close()
""";

    ProcessBuilder processBuilder =
        new ProcessBuilder("python3", "-c", script, playtimeDbPath, playtimeSql, player);
    processBuilder.redirectErrorStream(true);

    try {
      Process process = processBuilder.start();
      String output;
      try (InputStream inputStream = process.getInputStream()) {
        output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
      }

      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IllegalStateException(output.isBlank() ? "Playtime lookup failed" : output);
      }

      return Integer.parseInt(output);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to start python3 for playtime lookup");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Playtime lookup interrupted");
    } catch (NumberFormatException exception) {
      throw new IllegalStateException("Playtime lookup returned invalid data");
    }
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream inputStream = exchange.getRequestBody()) {
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Map<String, String> parseForm(String form) {
    Map<String, String> values = new ConcurrentHashMap<>();
    if (form == null || form.isBlank()) {
      return values;
    }

    String[] parts = form.split("&");
    for (String part : parts) {
      String[] keyValue = part.split("=", 2);
      String key = decode(keyValue[0]);
      String value = keyValue.length > 1 ? decode(keyValue[1]) : "";
      values.put(key, value);
    }

    return values;
  }

  private static String requireField(Map<String, String> fields, String key, int maxLength) {
    String value = fields.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Missing field: " + key);
    }

    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("Field cannot be empty: " + key);
    }

    if (trimmed.length() > maxLength) {
      throw new IllegalArgumentException("Field too long: " + key);
    }

    if (trimmed.contains("\n") || trimmed.contains("\r")) {
      throw new IllegalArgumentException("Field must be a single line: " + key);
    }

    return trimmed;
  }

  private static long parseSince(String value) {
    try {
      long parsed = Long.parseLong(value);
      return Math.max(parsed, 0);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Invalid since");
    }
  }

  private static void sendResponse(HttpExchange exchange, int statusCode, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream outputStream = exchange.getResponseBody()) {
      outputStream.write(bytes);
    }
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String valueOrDefault(String value, String fallback) {
    if (value == null) {
      return fallback;
    }

    String trimmed = value.trim();
    return trimmed.isEmpty() ? fallback : trimmed;
  }

  private record RelayConfig(
      int port,
      String authToken,
      Set<String> allowedUsers,
      boolean logMessages,
      String playtimeDbPath,
      String playtimeSql) {
    private static RelayConfig load(Path path) throws IOException {
      Properties properties = new Properties();
      if (Files.exists(path)) {
        try (InputStream input = Files.newInputStream(path)) {
          properties.load(input);
        }
      }

      int port = parsePositiveInt(properties.getProperty("port"), DEFAULT_PORT);
      String authToken = valueOrDefault(properties.getProperty("auth_token"), "");
      Set<String> allowedUsers = parseAllowedUsers(properties.getProperty("allow_users"));
      boolean logMessages = parseBoolean(properties.getProperty("log_messages"), true);
      String playtimeDbPath = valueOrDefault(properties.getProperty("playtime_db_path"), "");
      String playtimeSql =
          valueOrDefault(properties.getProperty("playtime_sql"), DEFAULT_PLAYTIME_SQL);
      return new RelayConfig(
          port, authToken, allowedUsers, logMessages, playtimeDbPath, playtimeSql);
    }
  }

  private static int parsePositiveInt(String value, int fallback) {
    if (value == null) {
      return fallback;
    }

    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static boolean parseBoolean(String value, boolean fallback) {
    if (value == null) {
      return fallback;
    }

    String normalized = value.trim().toLowerCase();
    if ("true".equals(normalized)) {
      return true;
    }
    if ("false".equals(normalized)) {
      return false;
    }
    return fallback;
  }

  private static Set<String> parseAllowedUsers(String value) {
    Set<String> users = new LinkedHashSet<>();
    if (value == null || value.isBlank()) {
      return users;
    }

    String[] parts = value.split(",");
    for (String part : parts) {
      String normalized = part.trim().toLowerCase();
      if (!normalized.isEmpty()) {
        users.add(normalized);
      }
    }

    return users;
  }

  private record RelayMessage(long id, String sender, String message, long createdAt) {}
}
