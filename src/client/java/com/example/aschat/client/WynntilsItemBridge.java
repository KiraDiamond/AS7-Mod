package com.example.aschat.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;

public final class WynntilsItemBridge {
  private static final Pattern ITEM_PLACEHOLDER_PATTERN =
      Pattern.compile("(?i)(<item>|\\[item\\])");
  private static final String HOVER_INSERTION_PREFIX = "aschat:wynnitem:";
  private static final int MAX_HOVER_ENTRIES = 128;

  private static boolean initialized;
  private static boolean available;

  private static final Map<String, HoverItemEntry> hoverItems =
      new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, HoverItemEntry> eldest) {
          return size() > MAX_HOVER_ENTRIES;
        }
      };
  private static final AtomicInteger hoverCounter = new AtomicInteger(1);

  private static Object itemModel;
  private static Object itemEncodingModel;
  private static Pattern encodedDataPattern;

  private static Class<?> wynnItemClass;
  private static Class<?> namedItemPropertyClass;
  private static Class<?> gearTierItemPropertyClass;
  private static Method getWynnItemMethod;
  private static Method canEncodeItemMethod;
  private static Method encodeItemMethod;
  private static Method decodeItemMethod;
  private static Method makeItemStringMethod;
  private static Method errorOrHasErrorMethod;
  private static Method errorOrGetValueMethod;
  private static Method errorOrGetErrorMethod;
  private static Method encodedFromUtf16StringMethod;
  private static Method namedItemGetNameMethod;
  private static Method gearTierItemGetGearTierMethod;
  private static Method gearTierGetChatFormattingMethod;
  private static Constructor<?> encodingSettingsConstructor;
  private static Constructor<?> fakeItemStackConstructor;

  private WynntilsItemBridge() {}

  public static String replaceItemPlaceholders(Minecraft minecraft, String message) {
    Matcher matcher = ITEM_PLACEHOLDER_PATTERN.matcher(message);
    if (!matcher.find()) {
      return message;
    }

    if (!isAvailable()) {
      throw new IllegalStateException("Wynntils is required to share items through AS7.");
    }

    if (minecraft.player == null) {
      throw new IllegalStateException("You must be in-game to share an item.");
    }

    ItemStack heldItem = minecraft.player.getMainHandItem();
    if (heldItem == null || heldItem.isEmpty()) {
      throw new IllegalStateException("Hold an item in your main hand before using <item>.");
    }

    try {
      Optional<?> wynnItem = (Optional<?>) getWynnItemMethod.invoke(itemModel, heldItem);
      if (wynnItem.isEmpty()) {
        throw new IllegalStateException("Wynntils cannot read the held item.");
      }

      Object item = wynnItem.get();
      boolean canEncode = (boolean) canEncodeItemMethod.invoke(itemEncodingModel, item);
      if (!canEncode) {
        throw new IllegalStateException(
            "The held item cannot be shared through Wynntils encoding.");
      }

      Object settings = encodingSettingsConstructor.newInstance(false, false);
      Object errorOr = encodeItemMethod.invoke(itemEncodingModel, item, settings);
      if ((boolean) errorOrHasErrorMethod.invoke(errorOr)) {
        throw new IllegalStateException((String) errorOrGetErrorMethod.invoke(errorOr));
      }

      Object encodedBuffer = errorOrGetValueMethod.invoke(errorOr);
      String encodedString =
          (String) makeItemStringMethod.invoke(itemEncodingModel, item, encodedBuffer);
      return matcher.replaceAll(Matcher.quoteReplacement(encodedString));
    } catch (IllegalStateException exception) {
      throw exception;
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("Failed to encode the held item through Wynntils.");
    }
  }

  public static MutableComponent decorateMessage(
      String message, Function<String, MutableComponent> plainTextFactory) {
    if (!isAvailable()) {
      return plainTextFactory.apply(message);
    }

    List<EncodedSegment> segments = findEncodedSegments(message);
    if (segments.isEmpty()) {
      return plainTextFactory.apply(message);
    }

    MutableComponent result = Component.empty().withStyle(style -> style.withBold(false));
    int cursor = 0;
    for (EncodedSegment segment : segments) {
      if (segment.start() > cursor) {
        result.append(plainTextFactory.apply(message.substring(cursor, segment.start())));
      }

      result.append(createItemComponent(segment.data(), segment.name()));
      cursor = segment.end();
    }

    if (cursor < message.length()) {
      result.append(plainTextFactory.apply(message.substring(cursor)));
    }

    return result;
  }

  private static MutableComponent createItemComponent(String encodedData, String encodedName) {
    String fallbackName = encodedName == null || encodedName.isBlank() ? "[item]" : encodedName;

    try {
      Object encodedByteBuffer = encodedFromUtf16StringMethod.invoke(null, encodedData);
      Object errorOr = decodeItemMethod.invoke(itemEncodingModel, encodedByteBuffer, encodedName);
      if ((boolean) errorOrHasErrorMethod.invoke(errorOr)) {
        String error = (String) errorOrGetErrorMethod.invoke(errorOr);
        return Component.literal(fallbackName)
            .withStyle(
                Style.EMPTY
                    .withBold(false)
                    .applyFormat(ChatFormatting.UNDERLINE)
                    .withColor(ChatFormatting.RED)
                    .withHoverEvent(createShowTextHoverEvent(Component.literal(error).withStyle(ChatFormatting.RED))));
      }

      Object wynnItem = errorOrGetValueMethod.invoke(errorOr);
      String itemName = fallbackName;
      try {
        itemName = resolveItemName(wynnItem, fallbackName);
      } catch (ReflectiveOperationException ignored) {
      }

      ChatFormatting color = ChatFormatting.GOLD;
      try {
        color = resolveItemColor(wynnItem);
      } catch (ReflectiveOperationException ignored) {
      }

      Style style =
          Style.EMPTY
              .withBold(false)
              .applyFormat(ChatFormatting.UNDERLINE)
              .withColor(color);
      if (fakeItemStackConstructor != null) {
        String hoverId = registerHoverItem(wynnItem);
        style = style.withInsertion(HOVER_INSERTION_PREFIX + hoverId);
      }

      if (fakeItemStackConstructor != null) {
        try {
          ItemStack fakeItemStack = createFakeItemStack(wynnItem);
          style = style.withHoverEvent(createShowItemHoverEvent(fakeItemStack));
        } catch (ReflectiveOperationException ignored) {
        }
      }

      if (style.getHoverEvent() == null) {
        style =
            style.withHoverEvent(
                createShowTextHoverEvent(
                    Component.literal("Hover to inspect " + itemName).withStyle(color)));
      }

      return Component.literal(itemName).withStyle(style);
    } catch (ReflectiveOperationException exception) {
      return Component.literal(fallbackName)
          .withStyle(ChatFormatting.RED, ChatFormatting.UNDERLINE);
    }
  }

  private static HoverEvent createShowTextHoverEvent(Component text)
      throws ReflectiveOperationException {
    try {
      return new HoverEvent.ShowText(text);
    } catch (RuntimeException exception) {
      throw new ReflectiveOperationException("No compatible SHOW_TEXT hover event constructor", exception);
    }
  }

  private static HoverEvent createShowItemHoverEvent(ItemStack itemStack)
      throws ReflectiveOperationException {
    try {
      return new HoverEvent.ShowItem(itemStack);
    } catch (RuntimeException exception) {
      throw new ReflectiveOperationException("No compatible SHOW_ITEM hover event constructor", exception);
    }
  }

  private static List<EncodedSegment> findEncodedSegments(String message) {
    List<EncodedSegment> segments = new ArrayList<>();
    if (message == null || message.isEmpty() || encodedDataPattern == null) {
      return segments;
    }

    Matcher matcher = encodedDataPattern.matcher(message);
    while (matcher.find()) {
      String data = matcher.group("data");
      if (data == null || data.isEmpty()) {
        continue;
      }

      segments.add(new EncodedSegment(matcher.start(), matcher.end(), data, matcher.group("name")));
    }

    return segments;
  }

  private static String resolveItemName(Object wynnItem, String fallbackName)
      throws ReflectiveOperationException {
    if (namedItemPropertyClass != null
        && namedItemGetNameMethod != null
        && namedItemPropertyClass.isInstance(wynnItem)) {
      String name = (String) namedItemGetNameMethod.invoke(wynnItem);
      if (name != null && !name.isBlank()) {
        return name;
      }
    }

    return fallbackName == null || fallbackName.isBlank()
        ? wynnItem.getClass().getSimpleName()
        : fallbackName;
  }

  private static ChatFormatting resolveItemColor(Object wynnItem)
      throws ReflectiveOperationException {
    if (gearTierItemPropertyClass == null
        || gearTierItemGetGearTierMethod == null
        || gearTierGetChatFormattingMethod == null
        || !gearTierItemPropertyClass.isInstance(wynnItem)) {
      return ChatFormatting.GOLD;
    }

    Object gearTier = gearTierItemGetGearTierMethod.invoke(wynnItem);
    Object formatting = gearTierGetChatFormattingMethod.invoke(gearTier);
    return formatting instanceof ChatFormatting chatFormatting
        ? chatFormatting
        : ChatFormatting.GOLD;
  }

  private static boolean isAvailable() {
    initialize();
    return available;
  }

  public static boolean renderCustomHover(GuiGraphics guiGraphics, Style style, int mouseX, int mouseY) {
    if (style == null) {
      return false;
    }

    String insertion = style.getInsertion();
    if (insertion == null || !insertion.startsWith(HOVER_INSERTION_PREFIX)) {
      return false;
    }

    HoverItemEntry entry;
    synchronized (hoverItems) {
      entry = hoverItems.get(insertion.substring(HOVER_INSERTION_PREFIX.length()));
    }
    if (entry == null) {
      return false;
    }

    try {
      ItemStack fakeItemStack = createFakeItemStack(entry.wynnItem());
      guiGraphics.setTooltipForNextFrame(Minecraft.getInstance().font, fakeItemStack, mouseX, mouseY);
    } catch (ReflectiveOperationException exception) {
      guiGraphics.setTooltipForNextFrame(
          Minecraft.getInstance().font,
          Component.literal("AS7 item hover failed").withStyle(ChatFormatting.RED),
          mouseX,
          mouseY);
    }
    return true;
  }

  private static synchronized void initialize() {
    if (initialized) {
      return;
    }
    initialized = true;

    if (!FabricLoader.getInstance().isModLoaded("wynntils")) {
      return;
    }

    try {
      Class<?> modelsClass = Class.forName("com.wynntils.core.components.Models");
      Field itemField = modelsClass.getField("Item");
      Field itemEncodingField = modelsClass.getField("ItemEncoding");
      itemModel = itemField.get(null);
      itemEncodingModel = itemEncodingField.get(null);

      Class<?> itemModelClass = Class.forName("com.wynntils.models.items.ItemModel");
      Class<?> itemEncodingModelClass =
          Class.forName("com.wynntils.models.items.ItemEncodingModel");
      Class<?> errorOrClass = Class.forName("com.wynntils.utils.type.ErrorOr");
      Class<?> encodedByteBufferClass = Class.forName("com.wynntils.utils.EncodedByteBuffer");
      Class<?> encodingSettingsClass =
          Class.forName("com.wynntils.models.items.encoding.type.EncodingSettings");
      wynnItemClass = Class.forName("com.wynntils.models.items.WynnItem");

      getWynnItemMethod = itemModelClass.getMethod("getWynnItem", ItemStack.class);
      canEncodeItemMethod = itemEncodingModelClass.getMethod("canEncodeItem", wynnItemClass);
      encodeItemMethod =
          itemEncodingModelClass.getMethod("encodeItem", wynnItemClass, encodingSettingsClass);
      decodeItemMethod =
          itemEncodingModelClass.getMethod("decodeItem", encodedByteBufferClass, String.class);
      makeItemStringMethod =
          itemEncodingModelClass.getMethod("makeItemString", wynnItemClass, encodedByteBufferClass);
      errorOrHasErrorMethod = errorOrClass.getMethod("hasError");
      errorOrGetValueMethod = errorOrClass.getMethod("getValue");
      errorOrGetErrorMethod = errorOrClass.getMethod("getError");
      encodedFromUtf16StringMethod =
          encodedByteBufferClass.getMethod("fromUtf16String", String.class);
      encodingSettingsConstructor =
          encodingSettingsClass.getConstructor(boolean.class, boolean.class);

      encodedDataPattern =
          (Pattern)
              itemEncodingModelClass.getMethod("getEncodedDataPattern").invoke(itemEncodingModel);

      initializeOptionalMembers();
      available =
          encodedDataPattern != null
              && getWynnItemMethod != null
              && canEncodeItemMethod != null
              && encodeItemMethod != null
              && decodeItemMethod != null
              && makeItemStringMethod != null
              && errorOrHasErrorMethod != null
              && errorOrGetValueMethod != null
              && errorOrGetErrorMethod != null
              && encodedFromUtf16StringMethod != null
              && encodingSettingsConstructor != null;
    } catch (ReflectiveOperationException exception) {
      available = false;
    }
  }

  private static void initializeOptionalMembers() {
    try {
      namedItemPropertyClass =
          Class.forName("com.wynntils.models.items.properties.NamedItemProperty");
      namedItemGetNameMethod = namedItemPropertyClass.getMethod("getName");
    } catch (ReflectiveOperationException ignored) {
      namedItemPropertyClass = null;
      namedItemGetNameMethod = null;
    }

    try {
      gearTierItemPropertyClass =
          Class.forName("com.wynntils.models.items.properties.GearTierItemProperty");
      gearTierItemGetGearTierMethod = gearTierItemPropertyClass.getMethod("getGearTier");
      Class<?> gearTierClass = Class.forName("com.wynntils.models.gear.type.GearTier");
      gearTierGetChatFormattingMethod = gearTierClass.getMethod("getChatFormatting");
    } catch (ReflectiveOperationException ignored) {
      gearTierItemPropertyClass = null;
      gearTierItemGetGearTierMethod = null;
      gearTierGetChatFormattingMethod = null;
    }

    try {
      Class<?> fakeItemStackClass = Class.forName("com.wynntils.models.items.FakeItemStack");
      fakeItemStackConstructor = fakeItemStackClass.getConstructor(wynnItemClass, String.class);
    } catch (ReflectiveOperationException ignored) {
      fakeItemStackConstructor = null;
    }

    try {
      createShowTextHoverEvent(Component.literal("test"));
      createShowItemHoverEvent(new ItemStack(net.minecraft.world.item.Items.STONE));
    } catch (ReflectiveOperationException ignored) {
    }
  }

  private static ItemStack createFakeItemStack(Object wynnItem) throws ReflectiveOperationException {
    if (fakeItemStackConstructor == null) {
      throw new ReflectiveOperationException("Wynntils FakeItemStack constructor unavailable");
    }
    return (ItemStack) fakeItemStackConstructor.newInstance(wynnItem, "From AS Chat");
  }

  private static String registerHoverItem(Object wynnItem) {
    String hoverId = "w" + Integer.toHexString(hoverCounter.getAndIncrement());
    synchronized (hoverItems) {
      hoverItems.put(hoverId, new HoverItemEntry(wynnItem));
    }
    return hoverId;
  }

  private record EncodedSegment(int start, int end, String data, String name) {}

  private record HoverItemEntry(Object wynnItem) {}
}
