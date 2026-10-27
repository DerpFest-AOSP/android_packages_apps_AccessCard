// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.data;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.*;

public class DiagnosticStoreTest {
    private Path root;
    private AtomicLong now;
    private DiagnosticStore store;
    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("accesscard-diagnostics-test");
        now = new AtomicLong(1700000000000L);
        store = new DiagnosticStore(root.resolve("cache").toFile(), now::get);
    }
    @After public void cleanup() throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(p);
        }
    }
    private void record() throws Exception {
        store.record(DiagnosticStore.Event.APPLY, DiagnosticStore.Outcome.FAILED, 10, 7, true, 49);
    }
    @Test public void defaultOffCreatesNoFilesAndRejectsExport() throws Exception {
        record();
        assertFalse(Files.exists(root.resolve("cache")));
        assertEquals(0, store.size());
        assertThrows(IOException.class, () -> store.export());
    }
    @Test public void disableDeletesCacheAndStopsRecording() throws Exception {
        store.setEnabled(true); record(); assertTrue(store.size() > 0);
        store.setEnabled(false); record();
        assertFalse(Files.exists(root.resolve("cache")));
        store.setEnabled(true); assertEquals("", store.export());
    }
    @Test public void capsEventsAndDropsExpiredRecords() throws Exception {
        store.setEnabled(true);
        for (int i=0; i<DiagnosticStore.MAX_EVENTS + 20; i++) { record(); now.incrementAndGet(); }
        assertEquals(DiagnosticStore.MAX_EVENTS, store.export().lines().count());
        assertTrue(store.size() < 16 * 1024);
        now.addAndGet(DiagnosticStore.RETENTION_MS + 1);
        assertEquals("", store.export());
        record(); assertEquals(1, store.export().lines().count());
    }
    @Test public void enabledRecreationRetainsHistoryAndClockRollbackDropsFutureEvents() throws Exception {
        store.setEnabled(true); record();
        DiagnosticStore recreated = new DiagnosticStore(root.resolve("cache").toFile(), now::get);
        assertFalse(recreated.isEnabled());
        recreated.setEnabled(true);
        assertTrue(recreated.export().contains("event=APPLY outcome=FAILED error=10"));
        now.addAndGet(-1000);
        assertEquals("", recreated.export());
    }
    @Test public void rejectsMalformedOrOversizedCaches() throws Exception {
        store.setEnabled(true); record();
        Path file = root.resolve("cache/events.bin");
        Files.write(file, new byte[32 * 1024]);
        assertThrows(IOException.class, () -> store.export());
        store.clear(); assertEquals("", store.export());
    }
    @Test public void onlyTypedEventsAndBoundedNumbersAreAccepted() throws Exception {
        store.setEnabled(true);
        assertThrows(IllegalArgumentException.class, () -> store.record(
                DiagnosticStore.Event.APPLY, DiagnosticStore.Outcome.FAILED, -2, 7, true, 49));
        record();
        assertTrue(store.export().matches("[0-9]+ event=APPLY outcome=FAILED error=10 mode=7 recovery=true parameter=49\\n"));
    }
    @Test public void controllerParameterKeepsItsFullIntegerRange() throws Exception {
        store.setEnabled(true);
        store.record(DiagnosticStore.Event.APPLY, DiagnosticStore.Outcome.FAILED, 10, 7, false,
                Integer.MIN_VALUE);
        assertTrue(store.export().contains("parameter=" + Integer.MIN_VALUE));
        DiagnosticStore recreated = new DiagnosticStore(root.resolve("cache").toFile(), now::get);
        recreated.setEnabled(true);
        assertTrue(recreated.export().contains("parameter=" + Integer.MIN_VALUE));
    }
}
