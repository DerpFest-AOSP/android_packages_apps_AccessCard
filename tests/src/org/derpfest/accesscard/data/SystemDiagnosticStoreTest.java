// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.data;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class SystemDiagnosticStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private SystemDiagnosticStore store() {
        return new SystemDiagnosticStore(new File(temp.getRoot(), "diag"));
    }

    @Test
    public void disabledDoesNotCreateOrRecordFiles() throws Exception {
        SystemDiagnosticStore s = store();
        s.batch(10, new byte[10], 10, "ignored");
        s.trace("instance", "1 event=FIELD_ON\n");
        s.snapshot("nfc-start.txt", new byte[1]);
        assertEquals(0, temp.getRoot().list().length);
        assertThrows(IOException.class, () -> s.export(1000));
    }

    @Test
    public void ringIsBoundedAndReportsWrap() throws Exception {
        SystemDiagnosticStore s = store();
        s.begin(1000);
        byte[] data = new byte[SystemDiagnosticStore.CHUNK_BYTES * 6 + 10];
        s.batch(2000, data, data.length, "ok");
        var files = s.export(1001);
        long bytes =
                files.entrySet().stream()
                        .filter(e -> e.getKey().contains("logcat-"))
                        .mapToLong(e -> e.getValue().length)
                        .sum();
        assertTrue(bytes <= SystemDiagnosticStore.CHUNK_BYTES * SystemDiagnosticStore.CHUNKS);
        assertTrue(
                new String(files.get("system/capture.properties"), StandardCharsets.UTF_8)
                        .contains("logcat_wrapped=true"));
    }

    @Test
    public void resumesCursorAndClearRemovesEverything() throws Exception {
        SystemDiagnosticStore s = store();
        s.begin(1000);
        s.batch(2000, new byte[10], 10, "ok");
        s.snapshot("nfc-start.txt", new byte[10]);
        s = store();
        s.begin(1001);
        assertEquals(2000, s.cursor());
        assertEquals(2, s.export(1002).size() - 1);
        s.clear();
        assertEquals(0, s.size());
        s.batch(4000, new byte[10], 10, "ignored");
        assertEquals(0, temp.getRoot().list().length);
    }

    @Test
    public void traceDeduplicatesAndReportsMissingSequence() throws Exception {
        SystemDiagnosticStore s = store();
        s.begin(1000);
        String one = "recording=true\n1 event=START\n2 event=FIELD_ON\n";
        s.trace("a", one);
        s.trace("a", one);
        s.trace("a", "recording=true\n5 event=FIELD_OFF\n");
        s.trace("b", "recording=true\n1 event=START\n");
        String text =
                new String(s.export(1001).get("system/rf-events-0.txt"), StandardCharsets.UTF_8);
        assertEquals(1, text.split("a 1 event=START", -1).length - 1);
        assertTrue(text.contains("TRACE_GAP after=2 next=5"));
        assertTrue(text.contains("b 1 event=START"));
    }

    @Test
    public void oldTraceCannotReturnAfterClearingOrEnabling() throws Exception {
        SystemDiagnosticStore s = store();
        s.begin(1000);
        s.trace(
                "a",
                "recording=true\n1 wall_ms=999 event=FIELD_ON\n2 wall_ms=1001 event=FIELD_OFF\n");
        String text =
                new String(s.export(1002).get("system/rf-events-0.txt"), StandardCharsets.UTF_8);
        assertFalse(text.contains("FIELD_ON"));
        assertTrue(text.contains("FIELD_OFF"));
        s.snapshot("nfc-state.txt", new byte[] {1, 2});
        assertArrayEquals(new byte[] {1, 2}, s.export(1002).get("system/nfc-state.txt"));
    }

    @Test
    public void rejectsUnboundedSnapshotsAndExpiresOldSession() throws Exception {
        SystemDiagnosticStore s = store();
        s.begin(1000);
        assertThrows(IllegalArgumentException.class, () -> s.snapshot("../wallet", new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        s.snapshot(
                                "nfc-start.txt",
                                new byte[SystemDiagnosticStore.SNAPSHOT_BYTES + 1]));
        assertThrows(IOException.class, () -> s.export(1001 + SystemDiagnosticStore.RETENTION_MS));
        assertEquals(0, s.size());
        s.begin(1002 + SystemDiagnosticStore.RETENTION_MS);
        assertTrue(
                s.export(1003 + SystemDiagnosticStore.RETENTION_MS)
                        .containsKey("system/capture.properties"));
    }
}
