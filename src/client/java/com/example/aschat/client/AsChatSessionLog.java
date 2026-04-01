package com.example.aschat.client;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AsChatSessionLog {
  private static final Logger LOGGER = LoggerFactory.getLogger("aschat-session-log");
  private static final DateTimeFormatter FILE_STAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
  private static final DateTimeFormatter LINE_STAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final String FILE_PREFIX = "aschat-session-";
  private static final String FILE_SUFFIX = ".log";

  private static BufferedWriter writer;
  private static Path path;
  private static boolean shutdownHookRegistered;

  private AsChatSessionLog() {}

  public static synchronized void init() {
    if (writer != null) {
      return;
    }

    try {
      Path logsDir = FabricLoader.getInstance().getGameDir().resolve("logs");
      Files.createDirectories(logsDir);
      deleteOldLogs(logsDir);
      path = logsDir.resolve(FILE_PREFIX + LocalDateTime.now().format(FILE_STAMP) + FILE_SUFFIX);
      writer =
          Files.newBufferedWriter(
              path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      writeLine("SESSION", "Started");
      registerShutdownHook();
    } catch (IOException exception) {
      LOGGER.warn("Failed to initialize AS7 session log", exception);
    }
  }

  public static synchronized void log(String type, String message) {
    if (writer == null) {
      return;
    }

    writeLine(type, message);
  }

  public static synchronized Path path() {
    return path;
  }

  public static synchronized void shutdown() {
    if (writer == null) {
      return;
    }

    try {
      writeLine("SESSION", "Stopped");
      writer.close();
    } catch (IOException exception) {
      LOGGER.warn("Failed to close AS7 session log", exception);
    } finally {
      writer = null;
    }
  }

  private static void writeLine(String type, String message) {
    try {
      writer.write(
          "[" + LocalDateTime.now().format(LINE_STAMP) + "] [" + type + "] " + sanitize(message));
      writer.newLine();
      writer.flush();
    } catch (IOException exception) {
      LOGGER.warn("Failed to write AS7 session log entry", exception);
    }
  }

  private static String sanitize(String message) {
    if (message == null) {
      return "";
    }

    return message.replace('\n', ' ').replace('\r', ' ');
  }

  private static void registerShutdownHook() {
    if (shutdownHookRegistered) {
      return;
    }

    Runtime.getRuntime()
        .addShutdownHook(new Thread(AsChatSessionLog::shutdown, "aschat-session-log-shutdown"));
    shutdownHookRegistered = true;
  }

  private static void deleteOldLogs(Path logsDir) throws IOException {
    Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
    try (var stream = Files.list(logsDir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(
              path -> {
                String name = path.getFileName().toString();
                return name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
              })
          .forEach(path -> deleteIfOlderThan(path, cutoff));
    }
  }

  private static void deleteIfOlderThan(Path file, Instant cutoff) {
    try {
      Instant modified = Files.getLastModifiedTime(file).toInstant();
      if (modified.isBefore(cutoff)) {
        Files.deleteIfExists(file);
      }
    } catch (NoSuchFileException ignored) {
    } catch (IOException exception) {
      LOGGER.warn("Failed to delete old AS7 session log {}", file, exception);
    }
  }
}
