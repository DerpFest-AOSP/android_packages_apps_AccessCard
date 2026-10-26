// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.data;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Private, bounded system diagnostics, separate from the wallet and optional event cache. */
public final class SystemDiagnosticStore {
    public static final int CHUNK_BYTES = 512 * 1024;
    public static final int CHUNKS = 4;
    public static final int SNAPSHOT_BYTES = 1024 * 1024;
    public static final long RETENTION_MS = 24 * 60 * 60 * 1000L;
    private final File directory;
    private final Properties metadata = new Properties();
    private boolean enabled;

    public SystemDiagnosticStore(File directory) {
        this.directory = directory;
    }

    public synchronized void begin(long now) throws IOException {
        File state = new File(directory, "capture.properties");
        if (state.isFile() && state.length() <= 8192) {
            try (InputStream in = new FileInputStream(state)) {
                metadata.load(in);
            } catch (IllegalArgumentException e) {
                metadata.clear();
            }
        }
        long start = number("start_ms", 0);
        if (start <= 0 || start > now || now - start > RETENTION_MS) {
            clear();
            metadata.setProperty("start_ms", Long.toString(now));
            metadata.setProperty("cursor_ns", Long.toString(now * 1_000_000));
        }
        enabled = true;
        Files.createDirectories(directory.toPath());
        save();
    }

    public synchronized long cursor() {
        return number("cursor_ns", 0);
    }

    public synchronized boolean expired(long now) {
        long start = number("start_ms", 0);
        return start <= 0 || now < start || now - start > RETENTION_MS;
    }

    public synchronized void batch(long cursor, byte[] bytes, int length, String status)
            throws IOException {
        if (!enabled) return;
        if (length < 0 || length > bytes.length) throw new IllegalArgumentException();
        append("logcat", bytes, length);
        metadata.setProperty("cursor_ns", Long.toString(cursor));
        metadata.setProperty("logd_status", status);
        metadata.setProperty("last_poll_ms", Long.toString(System.currentTimeMillis()));
        save();
    }

    public synchronized void trace(String instance, String text) throws IOException {
        if (!enabled) return;
        long previous =
                instance.equals(metadata.getProperty("trace_instance"))
                        ? number("trace_sequence", 0)
                        : 0;
        long latest = previous;
        StringBuilder output = new StringBuilder();
        for (String line : text.split("\n")) {
            int end = line.indexOf(' ');
            if (end <= 0 || !Character.isDigit(line.charAt(0))) continue;
            long sequence;
            try {
                sequence = Long.parseLong(line.substring(0, end));
            } catch (NumberFormatException e) {
                continue;
            }
            if (sequence <= previous) continue;
            int wallStart = line.indexOf("wall_ms=");
            if (wallStart >= 0) {
                int wallEnd = line.indexOf(' ', wallStart);
                try {
                    long wall =
                            Long.parseLong(
                                    line.substring(
                                            wallStart + 8, wallEnd < 0 ? line.length() : wallEnd));
                    if (wall < number("start_ms", 0)) {
                        latest = sequence;
                        continue;
                    }
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            if (latest > 0 && sequence > latest + 1)
                output.append("TRACE_GAP after=")
                        .append(latest)
                        .append(" next=")
                        .append(sequence)
                        .append('\n');
            output.append(instance).append(' ').append(line).append('\n');
            latest = sequence;
        }
        byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
        append("rf-events", bytes, bytes.length);
        metadata.setProperty("trace_instance", instance);
        metadata.setProperty("trace_sequence", Long.toString(latest));
        metadata.setProperty("trace_status", text.lines().findFirst().orElse("unavailable"));
        save();
    }

    public synchronized void status(String key, String value) throws IOException {
        if (!enabled) return;
        if (!Set.of(
                                "recorder",
                                "nfc_trace",
                                "nfc_snapshot",
                                "last_interruption",
                                "last_logd_error",
                                "last_trace_error")
                        .contains(key)
                || value.length() > 1024) throw new IllegalArgumentException();
        metadata.setProperty(key, value);
        save();
    }

    public synchronized void snapshot(String name, byte[] bytes) throws IOException {
        if (!enabled) return;
        if (!Set.of("nfc-start.txt", "nfc-latest.txt", "nfc-state.txt").contains(name)
                || bytes.length > SNAPSHOT_BYTES) throw new IllegalArgumentException();
        Files.write(new File(directory, name).toPath(), bytes);
    }

    public synchronized Map<String, byte[]> export(long now) throws IOException {
        if (!enabled) throw new IOException("Recording is disabled");
        long start = number("start_ms", 0);
        if (now < start || now - start > RETENTION_MS) {
            clear();
            throw new IOException("Diagnostic session expired");
        }
        Map<String, byte[]> result = new TreeMap<>();
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Diagnostic cache unavailable");
        for (File file : files) {
            String name = file.getName();
            if (!(name.equals("capture.properties")
                    || name.equals("nfc-start.txt")
                    || name.equals("nfc-latest.txt")
                    || name.equals("nfc-state.txt")
                    || name.matches("(logcat|rf-events)-[0-3]\\.txt"))) continue;
            long limit = name.startsWith("nfc-") ? SNAPSHOT_BYTES : CHUNK_BYTES;
            if (file.length() > limit) throw new IOException("Diagnostic file exceeds limit");
            result.put("system/" + name, Files.readAllBytes(file.toPath()));
        }
        return result;
    }

    public synchronized long size() {
        File[] files = directory.listFiles();
        if (files == null) return 0;
        long size = 0;
        for (File file : files) size += file.length();
        return size;
    }

    public synchronized void clear() throws IOException {
        enabled = false;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) Files.delete(file.toPath());
        Files.deleteIfExists(directory.toPath());
        metadata.clear();
    }

    private void append(String prefix, byte[] bytes, int length) throws IOException {
        int index = (int) number(prefix + "_chunk", 0);
        if (index < 0 || index >= CHUNKS) index = 0;
        File file = new File(directory, prefix + "-" + index + ".txt");
        int offset = 0;
        while (offset < length) {
            long existing = file.length();
            if (existing >= CHUNK_BYTES) {
                index = (index + 1) % CHUNKS;
                file = new File(directory, prefix + "-" + index + ".txt");
                Files.deleteIfExists(file.toPath());
                existing = 0;
                metadata.setProperty(prefix + "_wrapped", "true");
            }
            int count = Math.min(length - offset, CHUNK_BYTES - (int) existing);
            try (OutputStream out = new FileOutputStream(file, true)) {
                out.write(bytes, offset, count);
            }
            offset += count;
        }
        metadata.setProperty(prefix + "_chunk", Integer.toString(index));
    }

    private long number(String key, long fallback) {
        try {
            return Long.parseLong(metadata.getProperty(key, ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void save() throws IOException {
        Path temp = new File(directory, "capture.tmp").toPath();
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                metadata.store(out, null);
            }
            Files.move(
                    temp,
                    new File(directory, "capture.properties").toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
