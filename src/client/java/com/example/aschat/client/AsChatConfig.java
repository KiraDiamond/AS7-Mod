package com.example.aschat.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;

public final class AsChatConfig {
  private static final String DEFAULT_RELAY_URL = "";
  private static final String DEFAULT_AUTH_TOKEN = "";
  private static final int DEFAULT_POLL_INTERVAL_TICKS = 15;
  private static final AsChatViewMode DEFAULT_VIEW_MODE = AsChatViewMode.BOTH;
  private static final boolean DEFAULT_HIDE_SHOUTS = false;
  private static final boolean DEFAULT_CHAT_HISTORY = false;
  private static final String IMAGE_ALIAS_PREFIX = "image_alias.";
  private static final Pattern IMAGE_ALIAS_TOKEN_PATTERN = Pattern.compile(":([A-Za-z0-9_-]+):");

  private final Path path;
  private final String relayUrl;
  private final AsChatSecurity.RelayEndpoint relayEndpoint;
  private final String authToken;
  private final int pollIntervalTicks;
  private AsChatViewMode viewMode;
  private boolean hideShouts;
  private boolean chatHistoryEnabled;
  private final Set<String> ignoredUsers;
  private final Set<String> filteredWords;
  private final Map<String, String> imageAliases;
  private Pattern filteredWordsPattern;

  private AsChatConfig(
      Path path,
      String relayUrl,
      String authToken,
      int pollIntervalTicks,
      AsChatViewMode viewMode,
      boolean hideShouts,
      boolean chatHistoryEnabled,
      Set<String> ignoredUsers,
      Set<String> filteredWords,
      Map<String, String> imageAliases) {
    this.path = path;
    this.relayUrl = relayUrl;
    this.relayEndpoint = AsChatSecurity.relayEndpoint(relayUrl);
    this.authToken = authToken;
    this.pollIntervalTicks = pollIntervalTicks;
    this.viewMode = viewMode;
    this.hideShouts = hideShouts;
    this.chatHistoryEnabled = chatHistoryEnabled;
    this.ignoredUsers = ignoredUsers;
    this.filteredWords = filteredWords;
    this.imageAliases = imageAliases;
    this.filteredWordsPattern = buildFilteredWordsPattern(filteredWords);
  }

  public static AsChatConfig load() {
    Path path = FabricLoader.getInstance().getConfigDir().resolve("aschat.properties");
    Properties properties = new Properties();

    if (Files.exists(path)) {
      try (InputStream input = Files.newInputStream(path)) {
        properties.load(input);
      } catch (IOException ignored) {
      }
    }

    String relayUrl = valueOrDefault(properties.getProperty("relay_url"), DEFAULT_RELAY_URL);
    String authToken = valueOrDefault(properties.getProperty("auth_token"), DEFAULT_AUTH_TOKEN);
    int pollIntervalTicks =
        parsePositiveInt(
            properties.getProperty("poll_interval_ticks"), DEFAULT_POLL_INTERVAL_TICKS);
    AsChatViewMode viewMode = parseViewMode(properties);
    boolean hideShouts = parseBoolean(properties.getProperty("hide_shouts"), DEFAULT_HIDE_SHOUTS);
    boolean chatHistoryEnabled =
        parseBoolean(properties.getProperty("chat_history"), DEFAULT_CHAT_HISTORY);
    Set<String> ignoredUsers = parseIgnoredUsers(properties.getProperty("ignored_users"));
    Set<String> filteredWords = parseFilteredWords(properties.getProperty("filtered_words"));
    Map<String, String> imageAliases = parseImageAliases(properties);

    AsChatConfig config =
        new AsChatConfig(
            path,
            relayUrl,
            authToken,
            pollIntervalTicks,
            viewMode,
            hideShouts,
            chatHistoryEnabled,
            ignoredUsers,
            filteredWords,
            imageAliases);
    if (!config.matchesPersistedProperties(properties)) {
      config.save();
    } else if (Files.exists(path)) {
      restrictOwnerOnly(path);
    }
    return config;
  }

  public String relayUrl() {
    return relayUrl;
  }

  public boolean hasRelayUrl() {
    return relayEndpoint.isConfigured();
  }

  public boolean hasValidRelayEndpoint() {
    return relayEndpoint.isValid();
  }

  public AsChatSecurity.RelayEndpoint relayEndpoint() {
    return relayEndpoint;
  }

  public String authToken() {
    return authToken;
  }

  public int pollIntervalTicks() {
    return pollIntervalTicks;
  }

  public AsChatViewMode viewMode() {
    return viewMode;
  }

  public AsChatViewMode toggleAsChatVisibility() {
    viewMode =
        viewMode == AsChatViewMode.VANILLA_ONLY ? AsChatViewMode.BOTH : AsChatViewMode.VANILLA_ONLY;
    save();
    return viewMode;
  }

  public AsChatViewMode toggleOnlyMode() {
    viewMode =
        viewMode == AsChatViewMode.AS_ONLY ? AsChatViewMode.VANILLA_ONLY : AsChatViewMode.AS_ONLY;
    save();
    return viewMode;
  }

  public boolean hideShouts() {
    return hideShouts;
  }

  public boolean toggleShouts() {
    hideShouts = !hideShouts;
    save();
    return hideShouts;
  }

  public boolean chatHistoryEnabled() {
    return chatHistoryEnabled;
  }

  public boolean toggleChatHistory() {
    chatHistoryEnabled = !chatHistoryEnabled;
    save();
    return chatHistoryEnabled;
  }

  public boolean showsAsChat() {
    return viewMode.showsAsChat();
  }

  public boolean showsVanillaChat() {
    return viewMode.showsVanillaChat();
  }

  public boolean ignoreUser(String username) {
    String normalized = normalizeUsername(username);
    if (normalized.isEmpty()) {
      return false;
    }

    boolean added = ignoredUsers.add(normalized);
    if (added) {
      save();
    }

    return added;
  }

  public boolean unignoreUser(String username) {
    String normalized = normalizeUsername(username);
    if (normalized.isEmpty()) {
      return false;
    }

    boolean removed = ignoredUsers.remove(normalized);
    if (removed) {
      save();
    }

    return removed;
  }

  public boolean isIgnored(String username) {
    return ignoredUsers.contains(normalizeUsername(username));
  }

  public Set<String> ignoredUsers() {
    return Set.copyOf(ignoredUsers);
  }

  public List<String> filteredWords() {
    return List.copyOf(filteredWords);
  }

  public boolean addFilteredWord(String word) {
    String normalized = normalizeFilteredWord(word);
    if (normalized.isEmpty()) {
      return false;
    }

    boolean added = filteredWords.add(normalized);
    if (added) {
      rebuildFilteredWordsPattern();
      save();
    }

    return added;
  }

  public boolean removeFilteredWord(String word) {
    String normalized = normalizeFilteredWord(word);
    if (normalized.isEmpty()) {
      return false;
    }

    boolean removed = filteredWords.remove(normalized);
    if (removed) {
      rebuildFilteredWordsPattern();
      save();
    }

    return removed;
  }

  public String filterText(String text) {
    if (text == null || text.isEmpty() || filteredWordsPattern == null) {
      return text;
    }

    Matcher matcher = filteredWordsPattern.matcher(text);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(
          result, Matcher.quoteReplacement("*".repeat(matcher.group().length())));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  public boolean setImageAlias(String alias, String url) {
    String normalizedAlias = normalizeImageAlias(alias);
    if (normalizedAlias.isEmpty()) {
      return false;
    }

    String normalizedUrl;
    try {
      normalizedUrl = AsMapEmbedCodec.normalizeOutgoingImageUrl(url);
    } catch (IllegalArgumentException exception) {
      return false;
    }

    String previous = imageAliases.put(normalizedAlias, normalizedUrl);
    if (!normalizedUrl.equals(previous)) {
      save();
    }
    return true;
  }

  public String expandImageAliases(String text) {
    if (text == null || text.isEmpty() || imageAliases.isEmpty()) {
      return text;
    }

    Matcher matcher = IMAGE_ALIAS_TOKEN_PATTERN.matcher(text);
    StringBuilder result = new StringBuilder();
    boolean replaced = false;
    while (matcher.find()) {
      String alias = normalizeImageAlias(matcher.group(1));
      String url = imageAliases.get(alias);
      if (url == null) {
        matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
        continue;
      }

      matcher.appendReplacement(result, Matcher.quoteReplacement("asmap:" + url));
      replaced = true;
    }
    matcher.appendTail(result);
    return replaced ? result.toString() : text;
  }

  private void save() {
    try {
      Files.createDirectories(path.getParent());
      Path tempFile = Files.createTempFile(path.getParent(), "aschat", ".tmp");
      try {
        try (OutputStream stream = Files.newOutputStream(tempFile)) {
          toProperties().store(stream, "AS Chat client config");
        }
        restrictOwnerOnly(tempFile);
        moveIntoPlace(tempFile, path);
        restrictOwnerOnly(path);
      } finally {
        Files.deleteIfExists(tempFile);
      }
    } catch (IOException ignored) {
    }
  }

  private Properties toProperties() {
    Properties output = new Properties();
    output.setProperty("relay_url", relayUrl);
    output.setProperty("auth_token", authToken);
    output.setProperty("poll_interval_ticks", Integer.toString(pollIntervalTicks));
    output.setProperty("chat_view_mode", viewMode.name());
    output.setProperty("hide_shouts", Boolean.toString(hideShouts));
    output.setProperty("chat_history", Boolean.toString(chatHistoryEnabled));
    output.setProperty("ignored_users", joinIgnoredUsers());
    output.setProperty("filtered_words", joinFilteredWords());
    for (Map.Entry<String, String> entry : imageAliases.entrySet()) {
      output.setProperty(IMAGE_ALIAS_PREFIX + entry.getKey(), entry.getValue());
    }
    return output;
  }

  private boolean matchesPersistedProperties(Properties properties) {
    Properties desired = toProperties();
    for (String key : desired.stringPropertyNames()) {
      if (!desired.getProperty(key).equals(properties.getProperty(key))) {
        return false;
      }
    }
    return true;
  }

  private String joinIgnoredUsers() {
    StringJoiner joiner = new StringJoiner(",");
    for (String ignoredUser : ignoredUsers) {
      joiner.add(ignoredUser);
    }
    return joiner.toString();
  }

  private String joinFilteredWords() {
    StringJoiner joiner = new StringJoiner(",");
    for (String filteredWord : filteredWords) {
      joiner.add(filteredWord);
    }
    return joiner.toString();
  }

  private static Set<String> parseIgnoredUsers(String value) {
    Set<String> users = new LinkedHashSet<>();
    if (value == null || value.isBlank()) {
      return users;
    }

    String[] parts = value.split(",");
    for (String part : parts) {
      String normalized = normalizeUsername(part);
      if (!normalized.isEmpty()) {
        users.add(normalized);
      }
    }

    return users;
  }

  private static Set<String> parseFilteredWords(String value) {
    Set<String> words = new LinkedHashSet<>();
    if (value == null || value.isBlank()) {
      return words;
    }

    String[] parts = value.split(",");
    for (String part : parts) {
      String normalized = normalizeFilteredWord(part);
      if (!normalized.isEmpty()) {
        words.add(normalized);
      }
    }

    return words;
  }

  private static Map<String, String> parseImageAliases(Properties properties) {
    Map<String, String> aliases = new LinkedHashMap<>();
    for (String key : properties.stringPropertyNames()) {
      if (!key.startsWith(IMAGE_ALIAS_PREFIX)) {
        continue;
      }

      String alias = normalizeImageAlias(key.substring(IMAGE_ALIAS_PREFIX.length()));
      if (alias.isEmpty()) {
        continue;
      }

      String rawUrl = properties.getProperty(key);
      try {
        aliases.put(alias, AsMapEmbedCodec.normalizeOutgoingImageUrl(rawUrl));
      } catch (IllegalArgumentException ignored) {
      }
    }
    return aliases;
  }

  private static String normalizeUsername(String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }

  private static String normalizeFilteredWord(String value) {
    if (value == null) {
      return "";
    }

    String trimmed = value.trim().toLowerCase();
    if (trimmed.isEmpty()) {
      return "";
    }

    for (int index = 0; index < trimmed.length(); index++) {
      if (Character.isWhitespace(trimmed.charAt(index))) {
        return "";
      }
    }

    return trimmed;
  }

  private static String normalizeImageAlias(String value) {
    if (value == null) {
      return "";
    }

    String trimmed = value.trim().toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty()) {
      return "";
    }

    for (int index = 0; index < trimmed.length(); index++) {
      char character = trimmed.charAt(index);
      boolean valid =
          (character >= 'a' && character <= 'z')
              || (character >= '0' && character <= '9')
              || character == '_'
              || character == '-';
      if (!valid) {
        return "";
      }
    }

    return trimmed;
  }

  private static AsChatViewMode parseViewMode(Properties properties) {
    String viewModeValue = properties.getProperty("chat_view_mode");
    if (viewModeValue != null) {
      try {
        AsChatViewMode parsed = AsChatViewMode.valueOf(viewModeValue.trim().toUpperCase());
        return parsed == AsChatViewMode.AS_ONLY ? DEFAULT_VIEW_MODE : parsed;
      } catch (IllegalArgumentException ignored) {
      }
    }

    boolean chatVisible = parseBoolean(properties.getProperty("chat_visible"), true);
    return chatVisible ? DEFAULT_VIEW_MODE : AsChatViewMode.VANILLA_ONLY;
  }

  private static String valueOrDefault(String value, String fallback) {
    if (value == null) {
      return fallback;
    }

    String trimmed = value.trim();
    return trimmed.isEmpty() ? fallback : trimmed;
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

  private void rebuildFilteredWordsPattern() {
    filteredWordsPattern = buildFilteredWordsPattern(filteredWords);
  }

  private static Pattern buildFilteredWordsPattern(Set<String> filteredWords) {
    if (filteredWords.isEmpty()) {
      return null;
    }

    List<String> escapedWords = new ArrayList<>();
    for (String filteredWord : filteredWords) {
      escapedWords.add(Pattern.quote(filteredWord));
    }

    return Pattern.compile(
        "(?i)(?<![\\p{L}\\p{N}_])(" + String.join("|", escapedWords) + ")(?![\\p{L}\\p{N}_])");
  }

  private static void moveIntoPlace(Path source, Path target) throws IOException {
    try {
      Files.move(
          source,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void restrictOwnerOnly(Path file) {
    try {
      Files.setPosixFilePermissions(
          file,
          java.util.Set.of(
              java.nio.file.attribute.PosixFilePermission.OWNER_READ,
              java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
    } catch (IOException | UnsupportedOperationException ignored) {
    }
  }
}
