package io.github.aki1d.javaengineering.files;

import java.util.List;
import java.util.Map;

/** Immutable result of {@link AccessLogAnalyzer#analyze}. */
public record TrafficReport(
    int filesProcessed,
    long totalRequests,
    long malformedLines,
    Map<String, Long> requestsByStatusClass,
    List<EndpointLatency> slowestEndpoints) {

  public TrafficReport {
    requestsByStatusClass = Map.copyOf(requestsByStatusClass);
    slowestEndpoints = List.copyOf(slowestEndpoints);
  }

  public record EndpointLatency(
      String endpoint, long requests, double averageMillis, long maxMillis) {}
}
