package io.github.aki1d.javaengineering.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aki1d.javaengineering.files.TrafficReport.EndpointLatency;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessLogAnalyzerTest {

  @TempDir Path logs;

  @Test
  void aggregatesAcrossFilesAndSubdirectories() throws Exception {
    write(
        "api-1.log",
        "2026-03-01T10:00:00Z GET /api/orders/41 200 100",
        "2026-03-01T10:00:01Z GET /api/orders/42 200 300",
        "2026-03-01T10:00:02Z POST /api/orders 201 50");
    write(
        "nested/api-2.log",
        "2026-03-01T10:00:03Z GET /api/orders/43 500 800",
        "2026-03-01T10:00:04Z GET /api/health 200 1");
    write("notes.txt", "ignored because of the extension");

    TrafficReport report = new AccessLogAnalyzer(4).analyze(logs, 2);

    assertThat(report.filesProcessed()).isEqualTo(2);
    assertThat(report.totalRequests()).isEqualTo(5);
    assertThat(report.malformedLines()).isZero();
    assertThat(report.requestsByStatusClass()).isEqualTo(Map.of("2xx", 4L, "5xx", 1L));
    assertThat(report.slowestEndpoints())
        .containsExactly(
            new EndpointLatency("GET /api/orders/{id}", 3, 400.0, 800),
            new EndpointLatency("POST /api/orders", 1, 50.0, 50));
  }

  @Test
  void countsMalformedLinesWithoutAbortingTheFile() throws Exception {
    write(
        "mixed.log",
        "2026-03-01T10:00:00Z GET /a 200 10",
        "not a log line",
        "2026-03-01T10:00:00Z GET /a 999 10",
        "2026-03-01T10:00:00Z GET /a 200 -5",
        "yesterday GET /a 200 10",
        "",
        "2026-03-01T10:00:00Z GET /a 200 30");

    TrafficReport report = new AccessLogAnalyzer(1).analyze(logs, 5);

    assertThat(report.totalRequests()).isEqualTo(2);
    assertThat(report.malformedLines()).isEqualTo(4);
  }

  @Test
  void invalidUtf8MakesALineMalformedInsteadOfFailingTheFile() throws Exception {
    byte[] valid = "2026-03-01T10:00:00Z GET /a 200 10\n".getBytes(StandardCharsets.UTF_8);
    byte[] corrupt = {(byte) 0xC3, (byte) 0x28, '\n'};
    Files.write(logs.resolve("corrupt.log"), concat(valid, corrupt, valid));

    TrafficReport report = new AccessLogAnalyzer(1).analyze(logs, 1);

    assertThat(report.totalRequests()).isEqualTo(2);
    assertThat(report.malformedLines()).isEqualTo(1);
  }

  @Test
  void processesManyFilesWithFewerOpenFileSlotsThanFiles() throws Exception {
    for (int i = 0; i < 50; i++) {
      write("part-" + i + ".log", "2026-03-01T10:00:00Z GET /x/" + i + " 200 " + i);
    }

    TrafficReport report = new AccessLogAnalyzer(3).analyze(logs, 1);

    assertThat(report.filesProcessed()).isEqualTo(50);
    assertThat(report.slowestEndpoints())
        .singleElement()
        .satisfies(
            endpoint -> {
              assertThat(endpoint.endpoint()).isEqualTo("GET /x/{id}");
              assertThat(endpoint.requests()).isEqualTo(50);
              assertThat(endpoint.maxMillis()).isEqualTo(49);
            });
  }

  @Test
  void emptyDirectoryProducesAnEmptyReport() throws Exception {
    TrafficReport report = new AccessLogAnalyzer(2).analyze(logs, 3);

    assertThat(report.totalRequests()).isZero();
    assertThat(report.slowestEndpoints()).isEmpty();
  }

  @Test
  void missingDirectoryIsAnIoError() {
    assertThatThrownBy(() -> new AccessLogAnalyzer(2).analyze(logs.resolve("missing"), 3))
        .isInstanceOf(NoSuchFileException.class);
  }

  @Test
  void normalisesNumericAndUuidPathSegments() {
    AccessLogEntry entry =
        AccessLogEntry.parse(
                "2026-03-01T10:00:00Z DELETE /users/7/sessions/"
                    + "3f2b8c1e-9a4d-4e2b-8c7a-1b2c3d4e5f60 204 12")
            .orElseThrow();

    assertThat(entry.endpoint()).isEqualTo("DELETE /users/{id}/sessions/{id}");
    assertThat(entry.statusClass()).isEqualTo("2xx");
  }

  @Test
  void rejectsNonPositiveOpenFileLimit() {
    assertThatThrownBy(() -> new AccessLogAnalyzer(0)).isInstanceOf(IllegalArgumentException.class);
  }

  private void write(String relativePath, String... lines) throws IOException {
    Path file = logs.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.write(file, List.of(lines), StandardCharsets.UTF_8);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }
}
