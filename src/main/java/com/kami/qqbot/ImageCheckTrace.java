package com.kami.qqbot;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One local task ID across queue, worker, HTTP callbacks and reply; never contains a QQ identity. */
final class ImageCheckTrace {
    final String id = UUID.randomUUID().toString();
    private final long started = System.nanoTime();
    private final Consumer<String> sink;

    ImageCheckTrace() { this(System.out::println); }
    ImageCheckTrace(Consumer<String> sink) { this.sink = sink; }

    void log(String stage, String detail) {
        sink.accept("[AI Image] time=" + Instant.now() + " trace=" + id + " elapsed_ms="
                + elapsedMs() + " stage=" + stage + " " + detail);
    }

    long elapsedMs() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    /** Only bounded, single-line, allowlisted header identifiers; never pass URLs, keys or error messages. */
    static String header(String value) {
        return value != null && value.matches("[A-Za-z0-9._:/;= +,-]{1,160}") ? value : "unavailable";
    }

    static String causes(Throwable error) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; error != null && i < 6; i++, error = error.getCause()) {
            if (i > 0) result.append('>');
            result.append(error.getClass().getSimpleName());
        }
        return result.toString();
    }
}
