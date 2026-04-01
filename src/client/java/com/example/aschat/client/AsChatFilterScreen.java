package com.example.aschat.client;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AsChatFilterScreen extends Screen {
  private static final int PANEL_WIDTH = 332;
  private static final int PANEL_HEIGHT = 214;
  private static final int LIST_ROW_HEIGHT = 18;
  private static final int LIST_VISIBLE_ROWS = 6;
  private static final int BACKGROUND = 0xD0141B1D;
  private static final int PANEL_FILL = 0xE0182024;
  private static final int PANEL_BORDER = 0xFF2C8E95;
  private static final int PANEL_ACCENT = 0xAA55FFF6;
  private static final int SELECTED_FILL = 0x8030A3A9;
  private static final int HOVER_FILL = 0x402C8E95;
  private static final int TEXT_PRIMARY = 0xFFE8FFFF;
  private static final int TEXT_MUTED = 0xFF8ED8DB;
  private static final int TEXT_WARNING = 0xFFFFD37A;
  private static final int ROW_BUTTON_WIDTH = 216;

  private final Screen parent;
  private final AsChatState state;

  private EditBox wordInput;
  private Button addButton;
  private Button removeButton;
  private Button scrollUpButton;
  private Button scrollDownButton;
  private Button doneButton;
  private final Button[] wordButtons = new Button[LIST_VISIBLE_ROWS];
  private int selectedIndex = -1;
  private int scrollOffset;
  private String statusMessage = "Whole-word filter. Matching is case-insensitive.";

  public AsChatFilterScreen(Screen parent, AsChatState state) {
    super(Component.literal("AS7 Filter"));
    this.parent = parent;
    this.state = state;
  }

  @Override
  protected void init() {
    int left = panelLeft();
    int top = panelTop();

    wordInput = new EditBox(font, left + 18, top + 46, 208, 20, Component.literal("Filtered word"));
    wordInput.setMaxLength(32);
    wordInput.setHint(Component.literal("word to mask"));
    wordInput.setResponder(value -> refreshButtons());
    addRenderableWidget(wordInput);
    setInitialFocus(wordInput);

    addButton =
        addRenderableWidget(
            Button.builder(Component.literal("Add"), button -> addWord())
                .pos(left + 234, top + 46)
                .size(80, 20)
                .build());

    removeButton =
        addRenderableWidget(
            Button.builder(Component.literal("Remove"), button -> removeSelected())
                .pos(left + 234, top + 150)
                .size(80, 20)
                .build());

    scrollUpButton =
        addRenderableWidget(
            Button.builder(Component.literal("Up"), button -> scrollBy(-1))
                .pos(left + 234, top + 88)
                .size(80, 20)
                .build());

    scrollDownButton =
        addRenderableWidget(
            Button.builder(Component.literal("Down"), button -> scrollBy(1))
                .pos(left + 234, top + 114)
                .size(80, 20)
                .build());

    for (int row = 0; row < LIST_VISIBLE_ROWS; row++) {
      final int rowIndex = row;
      wordButtons[row] =
          addRenderableWidget(
              Button.builder(Component.empty(), button -> selectVisibleRow(rowIndex))
                  .pos(left + 18, top + 88 + (row * LIST_ROW_HEIGHT))
                  .size(ROW_BUTTON_WIDTH, LIST_ROW_HEIGHT - 2)
                  .build());
    }

    doneButton =
        addRenderableWidget(
            Button.builder(Component.literal("Done"), button -> onClose())
                .pos(left + 234, top + 176)
                .size(80, 20)
                .build());

    refreshButtons();
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    refreshButtons();

    int left = panelLeft();
    int top = panelTop();
    int right = left + PANEL_WIDTH;
    int bottom = top + PANEL_HEIGHT;

    graphics.fill(0, 0, width, height, BACKGROUND);
    graphics.fill(left, top, right, bottom, PANEL_FILL);
    graphics.fill(left, top, right, top + 2, PANEL_ACCENT);
    graphics.fill(left, bottom - 1, right, bottom, PANEL_BORDER);
    graphics.fill(left, top, left + 1, bottom, PANEL_BORDER);
    graphics.fill(right - 1, top, right, bottom, PANEL_BORDER);

    graphics.drawString(
        font,
        Component.literal("AS7 Word Filter").withStyle(ChatFormatting.BOLD),
        left + 18,
        top + 16,
        TEXT_PRIMARY,
        false);
    graphics.drawString(
        font, Component.literal(statusMessage), left + 18, top + 29, statusColor(), false);
    graphics.drawString(
        font, Component.literal("Filtered words"), left + 18, top + 74, TEXT_MUTED, false);

    renderWordList(graphics, left + 18, top + 88, 216, LIST_VISIBLE_ROWS * LIST_ROW_HEIGHT);
    wordInput.render(graphics, mouseX, mouseY, partialTick);
    addButton.render(graphics, mouseX, mouseY, partialTick);
    removeButton.render(graphics, mouseX, mouseY, partialTick);
    scrollUpButton.render(graphics, mouseX, mouseY, partialTick);
    scrollDownButton.render(graphics, mouseX, mouseY, partialTick);
    doneButton.render(graphics, mouseX, mouseY, partialTick);
    for (Button wordButton : wordButtons) {
      if (wordButton != null && wordButton.visible) {
        wordButton.render(graphics, mouseX, mouseY, partialTick);
      }
    }
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    int maxScroll = Math.max(0, state.filteredWords().size() - LIST_VISIBLE_ROWS);
    if (maxScroll == 0) {
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    if (scrollY > 0) {
      scrollOffset = Math.max(0, scrollOffset - 1);
    } else if (scrollY < 0) {
      scrollOffset = Math.min(maxScroll, scrollOffset + 1);
    }

    return true;
  }

  @Override
  public void onClose() {
    if (minecraft != null) {
      minecraft.setScreen(parent);
    }
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  private void renderWordList(GuiGraphics graphics, int left, int top, int width, int height) {
    graphics.fill(left, top, left + width, top + height, 0xB0101518);
    graphics.fill(left, top, left + width, top + 1, PANEL_BORDER);
    graphics.fill(left, top + height - 1, left + width, top + height, PANEL_BORDER);
    graphics.fill(left, top, left + 1, top + height, PANEL_BORDER);
    graphics.fill(left + width - 1, top, left + width, top + height, PANEL_BORDER);

    List<String> words = state.filteredWords();
    if (selectedIndex >= words.size()) {
      selectedIndex = words.isEmpty() ? -1 : words.size() - 1;
    }

    int maxScroll = Math.max(0, words.size() - LIST_VISIBLE_ROWS);
    scrollOffset = Math.min(scrollOffset, maxScroll);

    for (int row = 0; row < LIST_VISIBLE_ROWS; row++) {
      int index = scrollOffset + row;
      int rowTop = top + (row * LIST_ROW_HEIGHT);
      if (index >= words.size()) {
        continue;
      }

      if (index == selectedIndex) {
        graphics.fill(
            left + 4, rowTop + 1, left + width - 4, rowTop + LIST_ROW_HEIGHT - 1, SELECTED_FILL);
      }
    }

    if (words.isEmpty()) {
      graphics.drawString(font, "No filtered words yet.", left + 12, top + 8, TEXT_MUTED, false);
    }
  }

  private void addWord() {
    String raw = wordInput.getValue();
    String trimmed = raw == null ? "" : raw.trim();
    if (trimmed.isEmpty()) {
      statusMessage = "Type one word first.";
      refreshButtons();
      return;
    }

    if (trimmed.chars().anyMatch(Character::isWhitespace)) {
      statusMessage = "Use one word at a time.";
      refreshButtons();
      return;
    }

    boolean added = state.addFilteredWord(trimmed);
    statusMessage =
        added
            ? "\"" + trimmed.toLowerCase() + "\" will now be masked."
            : "\"" + trimmed.toLowerCase() + "\" is already in the filter.";
    if (added) {
      wordInput.setValue("");
      selectedIndex = state.filteredWords().size() - 1;
      scrollOffset = Math.max(0, state.filteredWords().size() - LIST_VISIBLE_ROWS);
    }
    refreshButtons();
  }

  private void selectVisibleRow(int row) {
    int index = scrollOffset + row;
    List<String> words = state.filteredWords();
    if (index < 0 || index >= words.size()) {
      return;
    }

    selectedIndex = index;
    statusMessage = "Selected \"" + words.get(index) + "\".";
    refreshButtons();
  }

  private void scrollBy(int delta) {
    int maxScroll = Math.max(0, state.filteredWords().size() - LIST_VISIBLE_ROWS);
    scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + delta));
    refreshButtons();
  }

  private void removeSelected() {
    List<String> words = state.filteredWords();
    if (selectedIndex < 0 || selectedIndex >= words.size()) {
      statusMessage = "Select a word to remove.";
      refreshButtons();
      return;
    }

    String selectedWord = words.get(selectedIndex);
    if (state.removeFilteredWord(selectedWord)) {
      statusMessage = "\"" + selectedWord + "\" removed from the filter.";
    }

    List<String> updatedWords = state.filteredWords();
    if (updatedWords.isEmpty()) {
      selectedIndex = -1;
      scrollOffset = 0;
    } else {
      selectedIndex = Math.min(selectedIndex, updatedWords.size() - 1);
      scrollOffset = Math.min(scrollOffset, Math.max(0, updatedWords.size() - LIST_VISIBLE_ROWS));
    }
    refreshButtons();
  }

  private void refreshButtons() {
    List<String> words = state.filteredWords();
    if (addButton != null) {
      String value = wordInput == null ? "" : wordInput.getValue().trim();
      addButton.active = !value.isEmpty();
    }
    if (removeButton != null) {
      removeButton.active = selectedIndex >= 0 && selectedIndex < words.size();
    }
    if (scrollUpButton != null) {
      scrollUpButton.active = scrollOffset > 0;
    }
    if (scrollDownButton != null) {
      scrollDownButton.active = scrollOffset < Math.max(0, words.size() - LIST_VISIBLE_ROWS);
    }
    for (int row = 0; row < wordButtons.length; row++) {
      Button button = wordButtons[row];
      if (button == null) {
        continue;
      }

      int index = scrollOffset + row;
      boolean hasWord = index < words.size();
      button.visible = hasWord;
      button.active = hasWord;
      if (hasWord) {
        String word = words.get(index);
        boolean selected = index == selectedIndex;
        button.setMessage(
            Component.literal(selected ? "> " + word : word)
                .withStyle(selected ? ChatFormatting.AQUA : ChatFormatting.WHITE));
      }
    }
  }

  private int panelLeft() {
    return (width - PANEL_WIDTH) / 2;
  }

  private int panelTop() {
    return (height - PANEL_HEIGHT) / 2;
  }

  private int statusColor() {
    return statusMessage.startsWith("Use ") || statusMessage.startsWith("Type ")
        ? TEXT_WARNING
        : TEXT_MUTED;
  }
}
