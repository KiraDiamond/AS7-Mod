package com.example.aschat.client;

import com.google.common.net.InetAddresses;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.net.http.HttpTimeoutException;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

final class AsChatSecurity {
  static final int MAX_RELAY_RESPONSE_BYTES = 64 * 1024;
  static final int MAX_PLAYTIME_RESPONSE_BYTES = 256;
  static final int MAX_RELAY_MESSAGES_PER_BATCH = 200;
  static final int MAX_RELAY_MESSAGE_LENGTH = 256;
  static final int MAX_RELAY_SENDER_LENGTH = 16;

  private static final Pattern VALID_USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
  private static final Pattern TOKEN_QUERY_PATTERN =
      Pattern.compile("([?&]token=)[^&\\s]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern TOKEN_FIELD_PATTERN =
      Pattern.compile("(^|[?&\\s])token=([^&\\s]+)", Pattern.CASE_INSENSITIVE);

  private AsChatSecurity() {}

  static RelayEndpoint relayEndpoint(String configuredValue) {
    String trimmed = trimToEmpty(configuredValue);
    if (trimmed.isEmpty()) {
      return new RelayEndpoint("", null, false);
    }

    try {
      URI raw = URI.create(trimmed);
      String scheme = raw.getScheme();
      String host = extractHost(raw);
      if (scheme == null
          || host == null
          || raw.getRawUserInfo() != null
          || raw.getRawQuery() != null
          || raw.getRawFragment() != null) {
        return new RelayEndpoint(trimmed, null, true);
      }

      String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
      if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
        return new RelayEndpoint(trimmed, null, true);
      }

      String normalizedHost = host.toLowerCase(Locale.ROOT);
      if ("http".equals(normalizedScheme) && !isAllowedLocalHttpHost(normalizedHost)) {
        return new RelayEndpoint(trimmed, null, true);
      }

      URI baseUri =
          new URI(
                  normalizedScheme,
                  null,
                  normalizedHost,
                  raw.getPort(),
                  normalizeBasePath(raw.getPath()),
                  null,
                  null)
              .normalize();
      return new RelayEndpoint(trimmed, baseUri, false);
    } catch (IllegalArgumentException | URISyntaxException ignored) {
      return new RelayEndpoint(trimmed, null, true);
    }
  }

  static boolean isValidRelaySender(String value) {
    return value != null && VALID_USERNAME.matcher(value).matches();
  }

  static String sanitizeInboundText(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }

    StringBuilder sanitized = new StringBuilder(value.length());
    boolean previousWhitespace = false;
    int index = 0;
    while (index < value.length()) {
      int codePoint = value.codePointAt(index);
      index += Character.charCount(codePoint);

      if (Character.isWhitespace(codePoint)) {
        if (!previousWhitespace && !sanitized.isEmpty()) {
          sanitized.append(' ');
        }
        previousWhitespace = true;
        continue;
      }

      if (isUnsafeCodePoint(codePoint)) {
        continue;
      }

      sanitized.appendCodePoint(codePoint);
      previousWhitespace = false;
    }

    return sanitized.toString().trim();
  }

  static String redactSecrets(String message, Collection<String> secrets) {
    if (message == null || message.isBlank()) {
      return "";
    }

    String redacted = TOKEN_QUERY_PATTERN.matcher(message).replaceAll("$1<redacted>");
    redacted = TOKEN_FIELD_PATTERN.matcher(redacted).replaceAll("$1token=<redacted>");
    for (String secret : secrets) {
      String trimmed = trimToEmpty(secret);
      if (!trimmed.isEmpty()) {
        redacted = redacted.replace(trimmed, "<redacted>");
      }
    }
    return sanitizeInboundText(redacted);
  }

  static String describeRelayFailure(Throwable throwable, String authToken) {
    Throwable root = rootCause(throwable);
    if (root instanceof RelayNotConfiguredException) {
      return "relay URL is not configured";
    }
    if (root instanceof RelayConfigurationException) {
      return "relay URL is invalid";
    }
    if (root instanceof InvalidRelayResponseException) {
      return "received invalid data";
    }
    return describeNetworkFailure(root, authToken, "request failed");
  }

  static String describeLookupFailure(Throwable throwable) {
    Throwable root = rootCause(throwable);
    if (root instanceof LookupNotFoundException) {
      return "player not found";
    }
    if (root instanceof InvalidLookupResponseException) {
      return "received invalid data";
    }
    return describeNetworkFailure(root, "", "lookup failed");
  }

  private static String describeNetworkFailure(
      Throwable root, String secretValue, String fallbackMessage) {
    if (root instanceof HttpTimeoutException) {
      return "request timed out";
    }
    if (root instanceof HttpStatusException statusException) {
      return "received HTTP " + statusException.statusCode();
    }
    if (root instanceof SSLException) {
      return "secure connection failed";
    }
    if (isUnreachable(root)) {
      return "could not reach host";
    }

    String sanitized = redactSecrets(root.getMessage(), java.util.List.of(secretValue));
    return sanitized.isEmpty() ? fallbackMessage : fallbackMessage;
  }

  private static boolean isUnreachable(Throwable throwable) {
    return throwable instanceof ConnectException
        || throwable instanceof UnknownHostException
        || throwable instanceof NoRouteToHostException
        || throwable instanceof UnresolvedAddressException;
  }

  private static boolean isUnsafeCodePoint(int codePoint) {
    return Character.isISOControl(codePoint)
        || switch (codePoint) {
          case 0x061C,
              0x200E,
              0x200F,
              0x202A,
              0x202B,
              0x202C,
              0x202D,
              0x202E,
              0x2066,
              0x2067,
              0x2068,
              0x2069 -> true;
          default -> false;
        };
  }

  private static boolean isAllowedLocalHttpHost(String host) {
    if ("localhost".equals(host)) {
      return true;
    }
    if (!InetAddresses.isInetAddress(host)) {
      return false;
    }

    InetAddress address = InetAddresses.forString(host);
    if (address.isLoopbackAddress()) {
      return true;
    }
    if (address instanceof Inet4Address ipv4Address) {
      return ipv4Address.isSiteLocalAddress();
    }
    if (address instanceof Inet6Address ipv6Address) {
      byte[] bytes = ipv6Address.getAddress();
      return bytes.length > 0 && (bytes[0] & 0xfe) == 0xfc;
    }
    return false;
  }

  private static String extractHost(URI uri) {
    if (uri.getHost() != null) {
      return stripIpv6Brackets(uri.getHost());
    }

    String authority = uri.getRawAuthority();
    if (authority == null || authority.isBlank()) {
      return null;
    }
    if (authority.startsWith("[")) {
      int closingBracket = authority.indexOf(']');
      if (closingBracket > 1) {
        return authority.substring(1, closingBracket);
      }
    }
    return null;
  }

  private static String stripIpv6Brackets(String host) {
    if (host != null && host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
      return host.substring(1, host.length() - 1);
    }
    return host;
  }

  private static String normalizeBasePath(String value) {
    if (value == null || value.isBlank()) {
      return "/";
    }

    String normalized = value.startsWith("/") ? value : "/" + value;
    return normalized.endsWith("/") ? normalized : normalized + "/";
  }

  private static String trimToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  record RelayEndpoint(String configuredValue, URI baseUri, boolean invalid) {
    boolean isConfigured() {
      return !configuredValue.isEmpty();
    }

    boolean isValid() {
      return baseUri != null;
    }

    URI resolve(String relativePathAndQuery) {
      if (!isConfigured()) {
        throw new RelayNotConfiguredException();
      }
      if (!isValid()) {
        throw new RelayConfigurationException();
      }
      return baseUri.resolve(relativePathAndQuery);
    }
  }

  static final class RelayNotConfiguredException extends IllegalStateException {}

  static class RelayConfigurationException extends IllegalStateException {}

  static final class InvalidRelayResponseException extends IllegalStateException {
    InvalidRelayResponseException(String message) {
      super(message);
    }
  }

  static final class InvalidLookupResponseException extends IllegalStateException {
    InvalidLookupResponseException(String message) {
      super(message);
    }
  }

  static final class LookupNotFoundException extends IllegalStateException {
    LookupNotFoundException() {
      super("Player not found");
    }
  }

  static final class HttpStatusException extends IllegalStateException {
    private final int statusCode;

    HttpStatusException(int statusCode) {
      super("HTTP " + statusCode);
      this.statusCode = statusCode;
    }

    int statusCode() {
      return statusCode;
    }
  }
}
