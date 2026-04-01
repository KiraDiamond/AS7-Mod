package com.example.aschat.client;

public enum AsChatViewMode {
  BOTH,
  VANILLA_ONLY,
  AS_ONLY;

  public boolean showsAsChat() {
    return this != VANILLA_ONLY;
  }

  public boolean showsVanillaChat() {
    return this != AS_ONLY;
  }
}
