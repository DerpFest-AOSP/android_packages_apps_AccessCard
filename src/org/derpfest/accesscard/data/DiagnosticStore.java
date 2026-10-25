// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.data;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Opt-in, identifier-free event cache. No arbitrary messages or exception text are accepted. */
public final class DiagnosticStore {
    public enum Event { SESSION, REFRESH, APPLY, STOP, UPDATE, DEFAULT, REORDER, DELETE,
        READER_OPEN, READER_CLOSE, SAVE, IMPORT, BACKUP, READ }
    public enum Outcome { STARTED, SUCCEEDED, FAILED }
    public static final int MAX_EVENTS = 512;
    public static final long RETENTION_MS = 24 * 60 * 60 * 1000L;
    private static final int MAGIC = 0x41434431;
    private final Path file;
    private final LongSupplier clock;
    private boolean enabled;

    public DiagnosticStore(File directory, LongSupplier clock) throws IOException {
        file = new File(directory, "events.bin").toPath();
        this.clock = clock;
        // The owner explicitly restores its opt-in preference before recording.
    }
    public synchronized boolean isEnabled() { return enabled; }
    public synchronized void setEnabled(boolean enabled) throws IOException {
        this.enabled = enabled;
        if (!enabled) clear();
    }
    public synchronized void clear() throws IOException {
        Files.deleteIfExists(file);
        if (Files.isDirectory(file.getParent())) {
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(file.getParent(), ".events-*.tmp")) {
                for (Path path : paths) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(file.getParent());
        }
    }
    public synchronized long size() throws IOException {
        if (!enabled) return 0;
        return Files.exists(file) ? Files.size(file) : 0;
    }
    public synchronized void record(Event event, Outcome outcome, int error, int mode,
            boolean recovery, int parameter) throws IOException {
        if (!enabled) return;
        if (error < -1 || error > 255 || mode < -1 || mode > 255
                || parameter < -1 || parameter > 65535) throw new IllegalArgumentException();
        List<Entry> entries = read();
        entries.add(new Entry(clock.getAsLong(), event, outcome, error, mode, recovery, parameter));
        while (entries.size() > MAX_EVENTS) entries.remove(0);
        write(entries);
    }
    public synchronized String export() throws IOException {
        if (!enabled) throw new IOException("Debug recording is disabled");
        List<Entry> entries = read();
        // Also remove expired records when the user exports, without a background worker.
        if (Files.exists(file)) write(entries);
        StringBuilder result = new StringBuilder();
        for (Entry e : entries) result.append(e.time).append(" event=").append(e.event)
                .append(" outcome=").append(e.outcome).append(" error=").append(e.error)
                .append(" mode=").append(e.mode).append(" recovery=").append(e.recovery)
                .append(" parameter=").append(e.parameter).append('\n');
        return result.toString();
    }
    private List<Entry> read() throws IOException {
        List<Entry> result = new ArrayList<>();
        if (!Files.exists(file)) return result;
        if (Files.size(file) > 16 * 1024) throw new IOException("Invalid diagnostic cache");
        long now = clock.getAsLong();
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid diagnostic cache");
            int count = input.readInt();
            if (count < 0 || count > MAX_EVENTS) throw new IOException("Invalid event count");
            for (int i = 0; i < count; i++) {
                long time = input.readLong();
                int event = input.readUnsignedByte(), outcome = input.readUnsignedByte();
                int error = input.readInt(), mode = input.readInt();
                boolean recovery = input.readBoolean();
                int parameter = input.readInt();
                if (event >= Event.values().length || outcome >= Outcome.values().length
                        || error < -1 || error > 255 || mode < -1 || mode > 255
                        || parameter < -1 || parameter > 65535) throw new IOException("Invalid event");
                if (time <= now && now - time <= RETENTION_MS)
                    result.add(new Entry(time, Event.values()[event], Outcome.values()[outcome],
                            error, mode, recovery, parameter));
            }
            if (input.read() != -1) throw new IOException("Unexpected event data");
        }
        return result;
    }
    private void write(List<Entry> entries) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".events-", ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temp))) {
                output.writeInt(MAGIC); output.writeInt(entries.size());
                for (Entry e : entries) {
                    output.writeLong(e.time); output.writeByte(e.event.ordinal());
                    output.writeByte(e.outcome.ordinal()); output.writeInt(e.error);
                    output.writeInt(e.mode); output.writeBoolean(e.recovery); output.writeInt(e.parameter);
                }
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static final class Entry {
        final long time;
        final Event event;
        final Outcome outcome;
        final int error, mode, parameter;
        final boolean recovery;
        Entry(long time, Event event, Outcome outcome, int error, int mode, boolean recovery, int parameter) {
            this.time = time; this.event = event; this.outcome = outcome; this.error = error;
            this.mode = mode; this.recovery = recovery; this.parameter = parameter;
        }
    }
}
