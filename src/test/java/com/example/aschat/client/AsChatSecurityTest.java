package com.example.aschat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AsChatSecurityTest {
  @Test
  void acceptsHttpsRelayUrlsAndNormalizesBasePath() {
    AsChatSecurity.RelayEndpoint endpoint =
        AsChatSecurity.relayEndpoint("https://relay.example.com/aschat");

    assertTrue(endpoint.isConfigured());
    assertTrue(endpoint.isValid());
    assertEquals(URI.create("https://relay.example.com/aschat/messages"), endpoint.resolve("messages"));
    assertEquals(
        URI.create("https://relay.example.com/aschat/playtime?player=test"),
        endpoint.resolve("playtime?player=test"));
    assertEquals(
        URI.create("https://relay.example.com/aschat/messages"),
        endpoint.resolve("/messages"));
  }

  @Test
  void allowsHttpForLocalTargetsOnly() {
    assertTrue(AsChatSecurity.relayEndpoint("http://localhost:8080").isValid());
    assertTrue(AsChatSecurity.relayEndpoint("http://127.0.0.1:8080").isValid());
    assertTrue(AsChatSecurity.relayEndpoint("http://192.168.1.25:8080").isValid());
    assertTrue(AsChatSecurity.relayEndpoint("http://[::1]:8080").isValid());
    assertTrue(AsChatSecurity.relayEndpoint("http://[fd00::1]:8080").isValid());

    assertFalse(AsChatSecurity.relayEndpoint("http://relay.example.com").isValid());
    assertFalse(AsChatSecurity.relayEndpoint("http://example.com/path?x=1").isValid());
    assertFalse(AsChatSecurity.relayEndpoint("https://user:pass@example.com").isValid());
    assertFalse(AsChatSecurity.relayEndpoint("ftp://relay.example.com").isValid());
  }

  @Test
  void rejectsUnconfiguredOrInvalidRelayEndpointsOnResolve() {
    AsChatSecurity.RelayEndpoint unconfigured = AsChatSecurity.relayEndpoint("   ");
    AsChatSecurity.RelayEndpoint invalid = AsChatSecurity.relayEndpoint("http://example.com");

    assertThrows(AsChatSecurity.RelayNotConfiguredException.class, () -> unconfigured.resolve("messages"));
    assertThrows(AsChatSecurity.RelayConfigurationException.class, () -> invalid.resolve("messages"));
  }

  @Test
  void sanitizesInboundRelayText() {
    String raw = "  hi\u202E there\tfriend\nsecond\u0007line  ";

    assertEquals("hi there friend secondline", AsChatSecurity.sanitizeInboundText(raw));
  }

  @Test
  void validatesRelaySenders() {
    assertTrue(AsChatSecurity.isValidRelaySender("Player_123"));
    assertFalse(AsChatSecurity.isValidRelaySender("way-too-long-player-name"));
    assertFalse(AsChatSecurity.isValidRelaySender("not valid"));
    assertFalse(AsChatSecurity.isValidRelaySender("bad!"));
  }

  @Test
  void redactsTokenValues() {
    String message =
        "failed GET https://relay.example.com/messages?since=1&token=secret-value and token=secret-value";

    assertEquals(
        "failed GET https://relay.example.com/messages?since=1&token=<redacted> and token=<redacted>",
        AsChatSecurity.redactSecrets(message, List.of("secret-value")));
  }

  @Test
  void describesRelayFailuresWithoutLeakingImplementationDetails() {
    assertEquals(
        "relay URL is not configured",
        AsChatSecurity.describeRelayFailure(new AsChatSecurity.RelayNotConfiguredException(), ""));
    assertEquals(
        "relay URL is invalid",
        AsChatSecurity.describeRelayFailure(new AsChatSecurity.RelayConfigurationException(), ""));
    assertEquals(
        "received invalid data",
        AsChatSecurity.describeRelayFailure(
            new AsChatSecurity.InvalidRelayResponseException("too large"), ""));
    assertEquals(
        "request timed out",
        AsChatSecurity.describeRelayFailure(new HttpTimeoutException("timed out"), ""));
    assertEquals(
        "received HTTP 503",
        AsChatSecurity.describeRelayFailure(new AsChatSecurity.HttpStatusException(503), ""));
    assertEquals(
        "could not reach host",
        AsChatSecurity.describeRelayFailure(new ConnectException("boom"), ""));
    assertEquals("request failed", AsChatSecurity.describeRelayFailure(new RuntimeException("token=secret"), "secret"));
  }

  @Test
  void describesLookupFailuresSafely() {
    assertEquals(
        "player not found",
        AsChatSecurity.describeLookupFailure(new AsChatSecurity.LookupNotFoundException()));
    assertEquals(
        "received invalid data",
        AsChatSecurity.describeLookupFailure(
            new AsChatSecurity.InvalidLookupResponseException("not json")));
    assertEquals(
        "received HTTP 404",
        AsChatSecurity.describeLookupFailure(new AsChatSecurity.HttpStatusException(404)));
  }

  @Test
  void relayResponseCapCoversTheBundledRelayBacklog() {
    int relayMaxMessages = 200;
    int relayMaxSenderLength = 64;
    int relayMaxMessageLength = 256;
    int maxEncodedCharsPerChar = 12;
    int perMessageOverhead = 32;
    int conservativeWorstCaseBytes =
        relayMaxMessages
            * (perMessageOverhead
                + (relayMaxSenderLength * maxEncodedCharsPerChar)
                + (relayMaxMessageLength * maxEncodedCharsPerChar));

    assertTrue(AsChatSecurity.MAX_RELAY_RESPONSE_BYTES >= conservativeWorstCaseBytes);
  }
}
