package io.github.aki1d.javaengineering.files;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * Summarises all {@code *.log} files under a directory, processing files in parallel.
 *
 * <ul>
 *   <li><b>Streaming:</b> files are read line by line, so a 10 GB log needs no more heap than a 10
 *       KB one.
 *   <li><b>Virtual threads:</b> one per file. Reading is I/O-bound, and virtual threads make
 *       thread-per-file cheap without sizing a pool.
 *   <li><b>Bounded file handles:</b> a semaphore caps how many files are open at once, because
 *       cheap threads do not mean cheap file descriptors.
 *   <li><b>No shared mutable state:</b> each file produces its own {@link TrafficSummary}; partial
 *       results are merged on the calling thread.
 * </ul>
 */
public final class AccessLogAnalyzer {

  private static final String LOG_SUFFIX = ".log";

  private final int maxOpenFiles;

  public AccessLogAnalyzer(int maxOpenFiles) {
    if (maxOpenFiles <= 0) {
      throw new IllegalArgumentException("maxOpenFiles must be positive: " + maxOpenFiles);
    }
    this.maxOpenFiles = maxOpenFiles;
  }

  /**
   * @param slowestEndpointLimit how many endpoints to include in the slowest-endpoints ranking
   * @throws IOException if the directory cannot be listed or a file cannot be read
   */
  public TrafficReport analyze(Path directory, int slowestEndpointLimit)
      throws IOException, InterruptedException {
    List<Path> logFiles = findLogFiles(directory);
    Semaphore openFiles = new Semaphore(maxOpenFiles);
    TrafficSummary total = new TrafficSummary();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<TrafficSummary>> partials = new ArrayList<>(logFiles.size());
      for (Path file : logFiles) {
        partials.add(executor.submit(() -> summariseFile(file, openFiles)));
      }
      for (Future<TrafficSummary> partial : partials) {
        total.merge(awaitPartial(partial));
      }
    }
    return total.toReport(slowestEndpointLimit);
  }

  private static List<Path> findLogFiles(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(AccessLogAnalyzer::isLogFile)
          .sorted()
          .toList();
    }
  }

  private static boolean isLogFile(Path path) {
    Path name = path.getFileName(); // null for a file system root
    return name != null && name.toString().endsWith(LOG_SUFFIX);
  }

  private static TrafficSummary summariseFile(Path file, Semaphore openFiles)
      throws IOException, InterruptedException {
    openFiles.acquire();
    // InputStreamReader replaces malformed bytes instead of throwing (unlike
    // Files.newBufferedReader), so one corrupt byte makes one line malformed, not the whole file.
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
      TrafficSummary summary = new TrafficSummary();
      summary.recordFile();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        AccessLogEntry.parse(line).ifPresentOrElse(summary::record, summary::recordMalformedLine);
      }
      return summary;
    } finally {
      openFiles.release();
    }
  }

  private static TrafficSummary awaitPartial(Future<TrafficSummary> partial)
      throws IOException, InterruptedException {
    try {
      return partial.get();
    } catch (ExecutionException e) {
      // Rethrow the task's own exception so callers see an IOException, not an executor detail.
      // Leaving the try-with-resources in analyze() then waits for the remaining files to finish.
      Throwable cause = e.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("Unexpected checked exception", cause);
    }
  }
}
