package com.lowlatency.dashboard;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tees every broadcast frame to a newline-delimited JSON file ({@code .ndjson}). Each line is
 * {@code {"event":"<name>","data":<frame-json>,"t":<epochMillis>}}. The frontend's demo mode replays this
 * file on GitHub Pages, so the dashboard is viewable live without a JVM or a network connection.
 */
final class FrameRecorder implements AutoCloseable {

    private final BufferedWriter writer;

    FrameRecorder(Path file) {
        try {
            Files.createDirectories(file.getParent());
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open recording " + file, e);
        }
    }

    synchronized void write(String event, String dataJson) {
        try {
            writer.write("{\"event\":\"");
            writer.write(event);
            writer.write("\",\"data\":");
            writer.write(dataJson);
            writer.write(",\"t\":");
            writer.write(Long.toString(System.currentTimeMillis()));
            writer.write("}\n");
        } catch (IOException e) {
            throw new UncheckedIOException("recording write failed", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
