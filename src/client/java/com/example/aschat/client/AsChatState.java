package com.example.aschat.client;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

public class AsChatState {
  private static final int INITIAL_REPLAY_LIMIT = 20;

  private final AsChatConfig config;
  private final AsChatRelayClient relayClient;
  private final Map<String, String> knownUsers = new LinkedHashMap<>();

  private CompletableFuture<Void> inFlightSend;
  private CompletableFuture<List<AsChatRelayClient.RelayMessage>> inFlightPoll;
  private long latestMessageId;
  private int ticksUntilPoll;
  private String lastPollError = "";
  private String lastSendError = "";
  private boolean relayHealthy = true;

  public AsChatState(AsChatConfig config) {
    this.config = config;
    this.relayClient = new AsChatRelayClient(config);
  }

  public AsChatConfig config() {
    return config;
  }

  public AsChatViewMode viewMode() {
    return config.viewMode();
  }

  public AsChatViewMode toggleChatVisible() {
    return config.toggleAsChatVisibility();
  }

  public AsChatViewMode toggleOnlyMode() {
    return config.toggleOnlyMode();
  }

  public boolean toggleShouts() {
    return config.toggleShouts();
  }

  public boolean toggleChatHistory() {
    return config.toggleChatHistory();
  }

  public List<String> filteredWords() {
    return config.filteredWords();
  }

  public boolean addFilteredWord(String word) {
    return config.addFilteredWord(word);
  }

  public boolean removeFilteredWord(String word) {
    return config.removeFilteredWord(word);
  }

  public String filterText(String text) {
    return config.filterText(text);
  }

  public boolean ignoreUser(String username) {
    rememberUsername(username);
    return config.ignoreUser(username);
  }

  public boolean unignoreUser(String username) {
    rememberUsername(username);
    return config.unignoreUser(username);
  }

  public void rememberUsername(String username) {
    String normalized = normalizeUsername(username);
    String display = username == null ? "" : username.trim();
    if (!normalized.isEmpty() && !display.isEmpty()) {
      knownUsers.put(normalized, display);
    }
  }

  public List<String> commandSuggestions() {
    LinkedHashSet<String> suggestions = new LinkedHashSet<>(knownUsers.values());
    suggestions.addAll(config.ignoredUsers());
    return List.copyOf(suggestions);
  }

  public boolean showsVanillaChat() {
    return config.showsVanillaChat();
  }

  public boolean shouldShowVanillaMessage(String message) {
    if (!config.showsVanillaChat()) {
      return false;
    }

    return !config.hideShouts() || !isShoutMessage(message);
  }

  public void tick(Minecraft client) {
    if (inFlightPoll != null && inFlightPoll.isDone()) {
      handlePollResult();
    }

    if (ticksUntilPoll > 0) {
      ticksUntilPoll--;
      return;
    }

    if (inFlightPoll == null) {
      inFlightPoll = relayClient.pollMessages(latestMessageId);
      ticksUntilPoll = config.pollIntervalTicks();
    }
  }

  public void sendCurrentRoomMessage(Minecraft client, String sender, String message) {
    if (inFlightSend != null && !inFlightSend.isDone()) {
      AsChatClientMod.showSystemMessage("AS7 is still sending your previous message.");
      return;
    }

    rememberUsername(sender);
    inFlightSend =
        relayClient
            .sendMessage(sender, message)
            .whenComplete(
                (ignored, throwable) -> {
                  if (throwable != null) {
                    AsChatClientMod.runOnClient(
                        () -> reportSendFailure(AsChatClientMod.rootMessage(throwable)));
                  } else {
                    lastSendError = "";
                  }
                });
  }

  private void handlePollResult() {
    try {
      List<AsChatRelayClient.RelayMessage> messages = inFlightPoll.join();
      if (!relayHealthy) {
        relayHealthy = true;
        lastPollError = "";
        AsChatClientMod.showSystemMessage("AS7 relay connection restored.");
      }

      boolean initialReplay = latestMessageId == 0;
      int startIndex =
          initialReplay && messages.size() > INITIAL_REPLAY_LIMIT
              ? messages.size() - INITIAL_REPLAY_LIMIT
              : 0;

      for (AsChatRelayClient.RelayMessage message : messages) {
        latestMessageId = Math.max(latestMessageId, message.id());
      }

      for (int index = startIndex; index < messages.size(); index++) {
        AsChatRelayClient.RelayMessage message = messages.get(index);
        rememberUsername(message.sender());
        if (!config.showsAsChat() || config.isIgnored(message.sender())) {
          continue;
        }

        AsChatClientMod.showRelayMessage(message.sender(), message.message());
      }
    } catch (RuntimeException exception) {
      reportPollFailure(AsChatClientMod.rootMessage(exception));
    } finally {
      inFlightPoll = null;
    }
  }

  private void reportPollFailure(String error) {
    relayHealthy = false;
    if (!error.equals(lastPollError)) {
      lastPollError = error;
      AsChatClientMod.showSystemMessage("AS7 relay poll failed: " + error);
    }
  }

  private void reportSendFailure(String error) {
    if (!error.equals(lastSendError)) {
      lastSendError = error;
      AsChatClientMod.showSystemMessage("AS7 relay send failed: " + error);
    }
  }

  private static String normalizeUsername(String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }

  private static boolean isShoutMessage(String message) {
    return message != null && message.contains(" shouts: ");
  }
}
