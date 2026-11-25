package io.github.aki1d.javaengineering.files;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One parsed access log line in the format {@code <ISO-8601 timestamp> <method> <path> <status>
 * <latency-ms>}, for example {@code 2026-03-01T10:15:30Z GET /api/orders/42 200 87}.
 */
public record AccessLogEntry(
    Instant timestamp, String method, String path, int status, long latencyMillis) {

  private static final int FIELD_COUNT = 5;
  private static final int MIN_STATUS = 100;
  private static final int MAX_STATUS = 599;
  private static final Pattern NUMERIC_SEGMENT = Pattern.compile("/\\d+(?=/|$)");
  private static final Pattern UUID_SEGMENT =
      Pattern.compile(
          "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  /**
   * Parses a line, returning empty for anything malformed. Log files are external input: a broken
   * line is counted and skipped, it must not abort the analysis of a multi-gigabyte file.
   */
  public static Optional<AccessLogEntry> parse(String line) {
    String[] fields = line.trim().split("\\s+");
    if (fields.length != FIELD_COUNT) {
      return Optional.empty();
    }
    try {
      int status = Integer.parseInt(fields[3]);
      long latency = Long.parseLong(fields[4]);
      if (status < MIN_STATUS || status > MAX_STATUS || latency < 0) {
        return Optional.empty();
      }
      return Optional.of(
          new AccessLogEntry(Instant.parse(fields[0]), fields[1], fields[2], status, latency));
    } catch (NumberFormatException | DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /**
   * Groups requests by route rather than by URL: {@code GET /orders/42} and {@code GET /orders/43}
   * are the same endpoint. Without this, the number of distinct keys (and memory) grows with the
   * number of IDs in the logs.
   */
  public String endpoint() {
    String route = UUID_SEGMENT.matcher(path).replaceAll("/{id}");
    route = NUMERIC_SEGMENT.matcher(route).replaceAll("/{id}");
    return method + " " + route;
  }

  public String statusClass() {
    return (status / 100) + "xx";
  }
}
