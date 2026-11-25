package io.github.aki1d.javaengineering.files;

import io.github.aki1d.javaengineering.collections.MoreCollectors;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mutable aggregate of access log statistics.
 *
 * <p>Each file is summarised by its own instance on its own thread, and the partial results are
 * merged at the end (map-reduce). Instances are therefore never shared while being updated, which
 * makes synchronisation unnecessary; the class is deliberately not thread-safe.
 *
 * <p>Memory use is O(number of distinct endpoints), independent of the number of lines.
 */
final class TrafficSummary {

  private long requests;
  private long malformedLines;
  private int files;
  private final Map<String, Long> requestsByStatusClass = new TreeMap<>();
  private final Map<String, EndpointStats> endpoints = new HashMap<>();

  void record(AccessLogEntry entry) {
    requests++;
    requestsByStatusClass.merge(entry.statusClass(), 1L, Long::sum);
    endpoints
        .computeIfAbsent(entry.endpoint(), key -> new EndpointStats())
        .add(entry.latencyMillis());
  }

  void recordMalformedLine() {
    malformedLines++;
  }

  void recordFile() {
    files++;
  }

  TrafficSummary merge(TrafficSummary other) {
    requests += other.requests;
    malformedLines += other.malformedLines;
    files += other.files;
    other.requestsByStatusClass.forEach(
        (key, count) -> requestsByStatusClass.merge(key, count, Long::sum));
    other.endpoints.forEach(
        (key, stats) -> endpoints.computeIfAbsent(key, k -> new EndpointStats()).merge(stats));
    return this;
  }

  TrafficReport toReport(int slowestEndpointLimit) {
    List<TrafficReport.EndpointLatency> slowest =
        endpoints.entrySet().stream()
            .map(entry -> entry.getValue().toLatency(entry.getKey()))
            .collect(
                MoreCollectors.topK(
                    slowestEndpointLimit,
                    Comparator.comparingDouble(TrafficReport.EndpointLatency::averageMillis)));
    return new TrafficReport(files, requests, malformedLines, requestsByStatusClass, slowest);
  }

  private static final class EndpointStats {
    private long count;
    private long totalLatency;
    private long maxLatency;

    void add(long latency) {
      count++;
      totalLatency += latency;
      maxLatency = Math.max(maxLatency, latency);
    }

    void merge(EndpointStats other) {
      count += other.count;
      totalLatency += other.totalLatency;
      maxLatency = Math.max(maxLatency, other.maxLatency);
    }

    TrafficReport.EndpointLatency toLatency(String endpoint) {
      return new TrafficReport.EndpointLatency(
          endpoint, count, (double) totalLatency / count, maxLatency);
    }
  }
}
