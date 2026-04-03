package com.example.aschat.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsChatClientMod implements ClientModInitializer {
  public static final Logger LOGGER = LoggerFactory.getLogger("aschat");
  private static final String WYNTILS_AS7 = "\uE040\uE052\uE067";
  private static final Component PREFIX =
      Component.literal(WYNTILS_AS7).withStyle(style -> style.withBold(false));
  private static final Component PREFIX_DIVIDER =
      Component.literal(" > ").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD);

  private static AsChatState state;
  private static AstLookupClient astLookupClient;
  private static AsChatRelayClient relayClient;
  private static AsMapVirtualManager mapVirtualManager;
  private static boolean openFilterScreenPending;
  private static String pendingPreviewId;

  @Override
  public void onInitializeClient() {
    state = new AsChatState(AsChatConfig.load());
    if (state.config().chatHistoryEnabled()) {
      AsChatSessionLog.init();
    }

    astLookupClient = new AstLookupClient();
    relayClient = new AsChatRelayClient(state.config());
    mapVirtualManager = new AsMapVirtualManager();

    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registryAccess) -> {
          dispatcher.register(
              ClientCommandManager.literal("as")
                  .then(
                      ClientCommandManager.argument("message", StringArgumentType.greedyString())
                          .executes(
                              context -> {
                                sendMessage(StringArgumentType.getString(context, "message"));
                                return 1;
                              })));

          dispatcher.register(
              ClientCommandManager.literal("aspreview")
                  .then(
                      ClientCommandManager.argument("id", StringArgumentType.word())
                          .executes(
                              context -> {
                                pendingPreviewId = StringArgumentType.getString(context, "id");
                                return 1;
                              })));

          dispatcher.register(
              ClientCommandManager.literal("astoggle")
                  .executes(
                      context -> {
                        AsChatViewMode mode = state.toggleChatVisible();
                        showSystemMessage(
                            mode == AsChatViewMode.VANILLA_ONLY
                                ? "AS7 chat rendering disabled."
                                : "AS7 chat rendering enabled.");
                        return 1;
                      }));

          dispatcher.register(
              ClientCommandManager.literal("asconfig")
                  .then(
                      ClientCommandManager.literal("toggle")
                          .executes(
                              context -> {
                                AsChatViewMode mode = state.toggleChatVisible();
                                showSystemMessage(
                                    mode == AsChatViewMode.VANILLA_ONLY
                                        ? "AS7 chat rendering disabled."
                                        : "AS7 chat rendering enabled.");
                                return 1;
                              }))
                  .then(
                      ClientCommandManager.literal("toggleshouts")
                          .executes(
                              context -> {
                                boolean hideShouts = state.toggleShouts();
                                showSystemMessage(
                                    hideShouts
                                        ? "Shout messages hidden."
                                        : "Shout messages shown.");
                                return 1;
                              }))
                  .then(
                      ClientCommandManager.literal("chathistory")
                          .executes(
                              context -> {
                                boolean enabled = state.toggleChatHistory();
                                if (enabled) {
                                  AsChatSessionLog.init();
                                  showSystemMessage("Chat history logging enabled.");
                                } else {
                                  AsChatSessionLog.shutdown();
                                  showSystemMessage("Chat history logging disabled.");
                                }
                                return 1;
                              }))
                  .then(
                      ClientCommandManager.literal("filter")
                          .executes(
                              context -> {
                                openFilterScreenPending = true;
                                return 1;
                              }))
                  .then(
                      ClientCommandManager.literal("image")
                          .then(
                              ClientCommandManager.argument("name", StringArgumentType.word())
                                  .then(
                                      ClientCommandManager.argument(
                                              "link", StringArgumentType.greedyString())
                                          .executes(
                                              context -> {
                                                String name =
                                                    StringArgumentType.getString(context, "name");
                                                String link =
                                                    StringArgumentType.getString(context, "link");
                                                boolean saved =
                                                    state.setImageAlias(name, link);
                                                if (!saved) {
                                                  showSystemMessage(
                                                      "Image alias failed. Use a-z, 0-9, _ or -"
                                                          + " for the name, and a valid http/https"
                                                          + " URL.");
                                                  return 0;
                                                }

                                                showSystemMessage(
                                                    "Saved image alias :"
                                                        + name.toLowerCase()
                                                        + ":");
                                                return 1;
                                              }))))
                  .then(
                      ClientCommandManager.literal("ignore")
                          .then(
                              ClientCommandManager.argument("player", StringArgumentType.word())
                                  .suggests(AsChatClientMod::suggestPlayers)
                                  .executes(
                                      context -> {
                                        String username =
                                            StringArgumentType.getString(context, "player");
                                        boolean added = state.ignoreUser(username);
                                        showSystemMessage(
                                            added
                                                ? "Ignoring AS7 messages from " + username + "."
                                                : username + " is already ignored.");
                                        return 1;
                                      })))
                  .then(
                      ClientCommandManager.literal("unignore")
                          .then(
                              ClientCommandManager.argument("player", StringArgumentType.word())
                                  .suggests(AsChatClientMod::suggestPlayers)
                                  .executes(
                                      context -> {
                                        String username =
                                            StringArgumentType.getString(context, "player");
                                        boolean removed = state.unignoreUser(username);
                                        showSystemMessage(
                                            removed
                                                ? "No longer ignoring AS7 messages from "
                                                    + username
                                                    + "."
                                                : username + " was not ignored.");
                                        return 1;
                                      })))
                  .executes(
                      context -> {
                        showSystemMessage(
                            "Use /asconfig toggle, /asconfig toggleshouts, /asconfig chathistory,"
                                + " /asconfig filter, /asconfig image <name> <link>, /asconfig"
                                + " ignore <player>, or /asconfig unignore <player>.");
                        return 1;
                      }));

          dispatcher.register(
              ClientCommandManager.literal("ast")
                  .then(
                      ClientCommandManager.argument("target", StringArgumentType.word())
                          .suggests(AsChatClientMod::suggestAstTargets)
                          .executes(
                              context ->
                                  executeAstTarget(StringArgumentType.getString(context, "target")))
                          .then(
                              ClientCommandManager.argument("player", StringArgumentType.word())
                                  .suggests(AsChatClientMod::suggestPlayers)
                                  .executes(
                                      context ->
                                          executeAstSubcommand(
                                              StringArgumentType.getString(context, "target"),
                                              StringArgumentType.getString(context, "player")))))
                  .executes(
                      context -> {
                        showSystemMessage(
                            "Use /ast <player>, /ast player <player>, /ast guild <player>, or /ast"
                                + " playtime <player>.");
                        return 1;
                      }));
        });

    ClientReceiveMessageEvents.ALLOW_CHAT.register(
        (message, signedMessage, sender, params, receptionTimestamp) -> {
          boolean allowed = state.shouldShowVanillaMessage(message.getString());
          if (!allowed) {
            AsChatSessionLog.log("CHAT_HIDDEN", message.getString());
            return false;
          }

          String filtered = state.filterText(message.getString());
          if (!filtered.equals(message.getString())) {
            AsChatSessionLog.log("CHAT_FILTERED", filtered);
            showVanillaFilteredMessage(filtered, message.getStyle());
            return false;
          }

          AsChatSessionLog.log("CHAT", message.getString());
          return true;
        });

    ClientReceiveMessageEvents.MODIFY_GAME.register(
        (message, overlay) -> {
          String filtered = state.filterText(message.getString());
          if (filtered.equals(message.getString())) {
            return message;
          }

          return Component.literal(filtered).withStyle(message.getStyle());
        });

    ClientReceiveMessageEvents.ALLOW_GAME.register(
        (message, overlay) -> {
          boolean allowed = state.shouldShowVanillaMessage(message.getString());
          if (!allowed) {
            AsChatSessionLog.log("GAME_HIDDEN", message.getString());
            return false;
          }

          AsChatSessionLog.log(
              overlay ? "GAME_OVERLAY" : "GAME", state.filterText(message.getString()));
          return allowed;
        });

    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          if (pendingPreviewId != null) {
            openPreviewScreen(client, pendingPreviewId);
            pendingPreviewId = null;
            return;
          }

          if (openFilterScreenPending && client.screen == null) {
            openFilterScreenPending = false;
            client.setScreen(new AsChatFilterScreen(null, state));
            return;
          }

          if (openFilterScreenPending && !(client.screen instanceof ChatScreen)) {
            openFilterScreenPending = false;
            client.setScreen(new AsChatFilterScreen(client.screen, state));
            return;
          }

          mapVirtualManager.tick(client);
          state.tick(client);
        });
  }

  private static void sendMessage(String rawMessage) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) {
      return;
    }

    String message = rawMessage.trim();
    if (message.isEmpty()) {
      showSystemMessage("AS7 messages cannot be empty.");
      return;
    }

    message = state.expandImageAliases(message);

    try {
      AsMapEmbedCodec.validateOutgoingMessage(message);
    } catch (IllegalArgumentException exception) {
      showSystemMessage(exception.getMessage());
      return;
    }

    try {
      message = WynntilsItemBridge.replaceItemPlaceholders(minecraft, message);
    } catch (IllegalStateException exception) {
      showSystemMessage(exception.getMessage());
      return;
    }

    if (message.length() > 4096) {
      showSystemMessage("AS7 messages can be at most 4096 characters.");
      return;
    }

    state.sendCurrentRoomMessage(minecraft, minecraft.player.getName().getString(), message);
  }

  private static void lookupGuild(String player) {
    state.rememberUsername(player);
    showSystemMessage("Fetching guild stats for " + player + "...");
    astLookupClient
        .fetchPlayerSummary(player)
        .whenComplete(
            (summary, throwable) ->
                runOnClient(
                    () -> {
                      if (throwable != null) {
                        showSystemMessage("AST guild lookup failed: " + rootMessage(throwable));
                        return;
                      }

                      showLookupHeader(player);
                      showStatLine("Guild", summary.guildName());
                      showStatLine("Rank", summary.guildRank());
                      showStatLine("Last seen", formatGuildLastSeen(summary.lastJoin()));
                      if (shouldShowGuildServer(summary.lastJoin())) {
                        showStatLine("Server", emptyToFallback(summary.server(), "Offline"));
                      }

                      if (summary.apiDisabled()) {
                        showStatLine("API", "disabled");
                      }
                    }));
  }

  private static void lookupPlayer(String player) {
    state.rememberUsername(player);
    showSystemMessage("Fetching stats for " + player + "...");
    astLookupClient
        .fetchPlayerSummary(player)
        .whenComplete(
            (summary, throwable) ->
                runOnClient(
                    () -> {
                      if (throwable != null) {
                        showSystemMessage("AST player lookup failed: " + rootMessage(throwable));
                        return;
                      }

                      showLookupHeader(player);
                      showStatLine("Wars", Integer.toString(summary.wars()));
                      showStatLine("Graids", Integer.toString(summary.guildRaidsTotal()));
                      showStatLine("NOL", Integer.toString(summary.nol()));
                      showStatLine("TNA", Integer.toString(summary.tna()));
                      showStatLine("NOTG", Integer.toString(summary.notg()));
                      showStatLine("TCC", Integer.toString(summary.tcc()));
                    }));
  }

  private static void lookupPlaytime(String player) {
    state.rememberUsername(player);
    showSystemMessage("Fetching playtime for " + player + "...");
    relayClient
        .fetchPlaytimeMinutes(player)
        .whenComplete(
            (minutes, throwable) ->
                runOnClient(
                    () -> {
                      if (throwable != null) {
                        showSystemMessage("AST playtime lookup failed: " + rootMessage(throwable));
                        return;
                      }

                      showLookupHeader(player);
                      showStatLine("Last 14d", minutes + " min");
                    }));
  }

  private static int executeAstTarget(String target) {
    return switch (target.toLowerCase()) {
      case "player" -> {
        showSystemMessage("Use /ast player <player>.");
        yield 1;
      }
      case "guild" -> {
        showSystemMessage("Use /ast guild <player>.");
        yield 1;
      }
      case "playtime" -> {
        showSystemMessage("Use /ast playtime <player>.");
        yield 1;
      }
      default -> {
        lookupPlayer(target);
        yield 1;
      }
    };
  }

  private static int executeAstSubcommand(String target, String player) {
    switch (target.toLowerCase()) {
      case "player" -> lookupPlayer(player);
      case "guild" -> lookupGuild(player);
      case "playtime" -> lookupPlaytime(player);
      default ->
          showSystemMessage(
              "Use /ast <player>, /ast player <player>, /ast guild <player>, or /ast playtime"
                  + " <player>.");
    }
    return 1;
  }

  private static CompletableFuture<Suggestions> suggestAstTargets(
      CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
    List<String> suggestions = new java.util.ArrayList<>();
    suggestions.add("player");
    suggestions.add("guild");
    suggestions.add("playtime");
    suggestions.addAll(state.commandSuggestions());
    return SharedSuggestionProvider.suggest(suggestions, builder);
  }

  private static CompletableFuture<Suggestions> suggestPlayers(
      CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
    return SharedSuggestionProvider.suggest(state.commandSuggestions(), builder);
  }

  public static void showRelayMessage(String sender, String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.gui == null) {
      return;
    }

    AsMapEmbedCodec.EmbedMatch embedMatch = AsMapEmbedCodec.findFirst(message).orElse(null);
    if (embedMatch != null) {
      showRelayImageMessage(sender, message, embedMatch);
      return;
    }

    String filteredSender = state == null ? sender : state.filterText(sender);
    AsChatSessionLog.log("AS_CHAT", filteredSender + ": " + message);
    minecraft
        .gui
        .getChat()
        .addMessage(
            PREFIX
                .copy()
                .append(PREFIX_DIVIDER.copy())
                .append(normalText(sender, ChatFormatting.GREEN))
                .append(normalText(": ", ChatFormatting.GRAY))
                .append(WynntilsItemBridge.decorateMessage(message, AsChatClientMod::normalText)));
  }

  private static void showRelayImageMessage(
      String sender, String message, AsMapEmbedCodec.EmbedMatch embedMatch) {
    String previewId = mapVirtualManager.registerPreview(embedMatch.embed());
    String filteredSender = state == null ? sender : state.filterText(sender);
    String displayMessage = AsMapEmbedCodec.replaceFirstToken(message, "[View Image]");
    AsChatSessionLog.log("AS_CHAT", filteredSender + ": " + displayMessage);

    Minecraft.getInstance()
        .gui
        .getChat()
        .addMessage(
            PREFIX
                .copy()
                .append(PREFIX_DIVIDER.copy())
                .append(normalText(sender, ChatFormatting.GREEN))
                .append(normalText(": ", ChatFormatting.GRAY))
                .append(buildImageMessageContent(message, embedMatch, previewId)));

    mapVirtualManager.beginRender(previewId, embedMatch.embed());
  }

  public static void showInfoLine(String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.gui == null) {
      LOGGER.info(message);
      return;
    }

    addChatLine(normalText(message, ChatFormatting.AQUA), "AS_INFO");
  }

  public static void showInfoLines(List<String> messages) {
    for (String message : messages) {
      showInfoLine(message);
    }
  }

  public static void showSystemMessage(String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.gui == null) {
      LOGGER.warn(message);
      return;
    }

    addChatLine(normalText(message, ChatFormatting.AQUA), "AS_SYSTEM");
  }

  private static void showLookupHeader(String title) {
    addChatLine(normalText(title), "AS_LOOKUP");
  }

  private static void showStatLine(String label, String value) {
    addChatLine(
        normalText(label + ": ", ChatFormatting.GRAY)
            .append(normalText(value, ChatFormatting.AQUA)),
        "AS_LOOKUP");
  }

  private static void addChatLine(Component content, String logType) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.gui == null) {
      LOGGER.info(content.getString());
      return;
    }

    Component line =
        PREFIX
            .copy()
            .append(PREFIX_DIVIDER.copy())
            .append(content.copy().withStyle(style -> style.withBold(false)));
    AsChatSessionLog.log(logType, line.getString());
    minecraft.gui.getChat().addMessage(line);
  }

  private static MutableComponent buildImageMessageContent(
      String originalMessage, AsMapEmbedCodec.EmbedMatch embedMatch, String previewId) {
    MutableComponent result = Component.empty().withStyle(style -> style.withBold(false));
    String before = originalMessage.substring(0, embedMatch.start());
    String after = originalMessage.substring(embedMatch.end());

    if (!before.isEmpty()) {
      result.append(WynntilsItemBridge.decorateMessage(before, AsChatClientMod::normalText));
    }

    result.append(mapVirtualManager.createViewComponent(previewId));

    if (!after.isEmpty()) {
      result.append(WynntilsItemBridge.decorateMessage(after, AsChatClientMod::normalText));
    }

    return result;
  }

  private static MutableComponent normalText(String text, ChatFormatting... formatting) {
    String filtered = state == null ? text : state.filterText(text);
    return Component.literal(filtered)
        .withStyle(
            style -> {
              style = style.withBold(false);
              for (ChatFormatting format : formatting) {
                style = style.applyFormat(format);
              }
              return style;
            });
  }

  private static MutableComponent normalText(String text) {
    String filtered = state == null ? text : state.filterText(text);
    return Component.literal(filtered).withStyle(style -> style.withBold(false));
  }

  private static void showVanillaFilteredMessage(
      String message, net.minecraft.network.chat.Style style) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.gui == null) {
      return;
    }

    minecraft.gui.getChat().addMessage(Component.literal(message).withStyle(style));
  }

  public static void runOnClient(Runnable runnable) {
    Minecraft client = Minecraft.getInstance();
    client.execute(runnable);
  }

  public static boolean renderCustomHoverEffect(
      GuiGraphics guiGraphics, Style style, int mouseX, int mouseY) {
    if (WynntilsItemBridge.renderCustomHover(guiGraphics, style, mouseX, mouseY)) {
      return true;
    }

    return mapVirtualManager != null
        && mapVirtualManager.renderHoverPreview(guiGraphics, style, mouseX, mouseY);
  }

  private static void openPreviewScreen(Minecraft client, String previewId) {
    String status = mapVirtualManager.previewStatusMessage(previewId);
    if (status != null) {
      showSystemMessage(status);
      return;
    }

    AsMapPreviewScreen previewScreen =
        mapVirtualManager.createPreviewScreen(client.screen, previewId);
    if (previewScreen == null) {
      showSystemMessage("AS7 image preview is no longer available.");
      return;
    }

    client.setScreen(previewScreen);
  }

  public static String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }

    String message = current.getMessage();
    return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
  }

  private static String formatSince(Instant instant) {
    if (instant == null || instant.equals(Instant.EPOCH)) {
      return "unknown";
    }

    Duration duration = Duration.between(instant, Instant.now());
    if (duration.isNegative()) {
      duration = Duration.ZERO;
    }

    long days = duration.toDays();
    long hours = duration.toHoursPart();
    long minutes = duration.toMinutesPart();
    long seconds = duration.toSecondsPart();

    if (days > 0) {
      return days + "d " + hours + "h";
    }
    if (hours > 0) {
      return hours + "h " + minutes + "m";
    }
    if (minutes > 0) {
      return minutes + "m";
    }
    return Math.max(seconds, 0) + "s";
  }

  private static String formatGuildLastSeen(Instant instant) {
    Duration duration = durationSince(instant);
    return duration.compareTo(Duration.ofMinutes(1)) < 0 ? "Online" : formatSince(instant);
  }

  private static boolean shouldShowGuildServer(Instant instant) {
    return durationSince(instant).compareTo(Duration.ofMinutes(5)) <= 0;
  }

  private static Duration durationSince(Instant instant) {
    if (instant == null || instant.equals(Instant.EPOCH)) {
      return Duration.ofDays(9999);
    }

    Duration duration = Duration.between(instant, Instant.now());
    return duration.isNegative() ? Duration.ZERO : duration;
  }

  private static String emptyToFallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
