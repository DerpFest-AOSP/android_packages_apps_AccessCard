// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.data;

import static org.junit.Assert.*;

import org.derpfest.accesscard.model.*;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.CRC32;

public class CardStoreTest {
    private MemoryStorage storage;
    private CardStore store;
    private CardIdentity first;
    private CardIdentity second;

    @Before
    public void setUp() {
        storage = new MemoryStorage();
        store = new CardStore(storage);
        first = identity(1);
        second = identity(2);
    }

    private static CardIdentity identity(int n) {
        return new CardIdentity(new byte[] {(byte) n, 2, 3, 4}, new byte[] {0, 4}, 8);
    }

    @Test
    public void emptyWalletDoesNotCreateData() throws Exception {
        assertTrue(store.load().getCards().isEmpty());
        assertNull(storage.bytes);
    }

    @Test
    public void durableRoundTripKeepsAppearanceIdentityAndDefault() throws Exception {
        AccessCardProfile added = store.add("  家裡 · entrance  ", first, 4, 2);
        AccessCardProfile loaded = new CardStore(storage).load().getCards().get(0);
        assertEquals("家裡 · entrance", loaded.getName());
        assertEquals(added.getId(), loaded.getId());
        assertEquals(first, loaded.getIdentity());
        assertEquals(4, loaded.getColor());
        assertEquals(2, loaded.getIcon());
        assertEquals(added.getId(), store.load().getDefaultId());
    }

    @Test
    public void duplicateUidCannotBeAddedWithAnotherNameOrAtqa() throws Exception {
        store.add("Home", first, 0, 0);
        assertThrows(
                CardStore.DuplicateCardException.class,
                () ->
                        store.add(
                                "Office",
                                new CardIdentity(first.getUid(), new byte[] {1, 2}, 24),
                                1,
                                1));
        assertEquals(1, store.load().getCards().size());
    }

    @Test
    public void failedWriteKeepsPreviousWallet() throws Exception {
        store.add("Home", first, 0, 0);
        byte[] before = storage.bytes.clone();
        storage.failWrite = true;
        assertThrows(IOException.class, () -> store.add("Office", second, 1, 1));
        assertArrayEquals(before, storage.bytes);
        assertEquals(1, store.load().getCards().size());
    }

    @Test
    public void renameAndReorderDoNotChangeDefaultOrIdentity() throws Exception {
        AccessCardProfile a = store.add("Home", first, 0, 0);
        AccessCardProfile b = store.add("Office", second, 1, 1);
        store.update(b.withAppearance("Studio", 3, 4));
        store.move(b.getId(), -1);
        assertEquals(b.getId(), store.load().getCards().get(0).getId());
        assertEquals(second, store.load().getCards().get(0).getIdentity());
        assertEquals("Studio", store.load().getCards().get(0).getName());
        assertEquals(a.getId(), store.load().getDefaultId());
    }

    @Test
    public void deletingDefaultDoesNotSilentlyChooseAnotherCard() throws Exception {
        AccessCardProfile a = store.add("Home", first, 0, 0);
        store.add("Office", second, 1, 1);
        store.remove(a.getId());
        assertNull(store.load().getDefaultId());
    }

    @Test
    public void corruptWalletIsPreservedAndCannotBeOverwrittenByAdd() throws Exception {
        store.add("Home", first, 0, 0);
        storage.bytes[15] ^= 1;
        byte[] corrupted = storage.bytes.clone();
        assertThrows(IOException.class, () -> store.load());
        assertThrows(IOException.class, () -> store.add("Office", second, 1, 1));
        assertArrayEquals(corrupted, storage.bytes);
    }

    @Test
    public void futureVersionIsPreservedForExplicitMigration() throws Exception {
        store.add("Home", first, 0, 0);
        ByteBuffer.wrap(storage.bytes).putInt(4, 2);
        CRC32 crc = new CRC32();
        crc.update(storage.bytes, 0, storage.bytes.length - 8);
        ByteBuffer.wrap(storage.bytes).putLong(storage.bytes.length - 8, crc.getValue());
        byte[] future = storage.bytes.clone();
        assertThrows(IOException.class, () -> store.load());
        assertThrows(IOException.class, () -> store.add("Office", second, 0, 0));
        assertArrayEquals(future, storage.bytes);
    }

    @Test
    public void separateUserDirectoriesStayIndependent() throws Exception {
        Path root = Files.createTempDirectory("accesscard-users");
        try {
            CardStore userA = CardStore.inDirectory(root.resolve("a").toFile());
            CardStore userB = CardStore.inDirectory(root.resolve("b").toFile());
            userA.add("A", first, 0, 0);
            assertTrue(userB.load().getCards().isEmpty());
            userB.add("B", second, 1, 1);
            assertEquals(first, userA.load().getCards().get(0).getIdentity());
            assertEquals(second, userB.load().getCards().get(0).getIdentity());
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(
                                path -> {
                                    try {
                                        Files.delete(path);
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                });
            }
        }
    }

    @Test
    public void arraysAreDefensiveAndInvalidIdentitiesAreRejected() {
        byte[] uid = {1, 2, 3, 4};
        byte[] atqa = {0, 4};
        CardIdentity card = new CardIdentity(uid, atqa, 8);
        uid[0] = 9;
        atqa[0] = 9;
        card.getUid()[0] = 9;
        assertEquals("01020304", card.getUidHex());
        assertEquals("0004", card.getAtqaHex());
        for (int length : new int[] {0, 1, 3, 5, 8, 11}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CardIdentity(new byte[length], new byte[2], 8));
        }
        for (int length : new int[] {4, 7, 10}) {
            assertEquals(
                    length, new CardIdentity(new byte[length], new byte[2], 8).getUid().length);
        }
    }

    @Test
    public void invalidAtqaAndSakAreRejected() {
        for (int length : new int[] {0, 1, 3, 8}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CardIdentity(new byte[4], new byte[length], 8));
        }
        for (int sak : new int[] {-1, 256}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CardIdentity(new byte[4], new byte[2], sak));
        }
        assertThrows(IllegalArgumentException.class, () -> new CardIdentity(null, new byte[2], 8));
        assertThrows(IllegalArgumentException.class, () -> new CardIdentity(new byte[4], null, 8));
    }

    @Test
    public void failedStopPreventsDeletingActiveCard() throws Exception {
        AccessCardProfile card = store.add("Home", first, 0, 0);
        FakeGateway gateway = new FakeGateway(first);
        gateway.allowStop = false;
        assertThrows(
                WalletActions.StopFailedException.class,
                () -> new WalletActions(store, gateway).remove(card.getId()));
        assertEquals(1, store.load().getCards().size());
        assertEquals(first, gateway.active);
    }

    @Test
    public void inactiveDeletionDoesNotStopAnotherCard() throws Exception {
        AccessCardProfile card = store.add("Office", second, 0, 0);
        FakeGateway gateway = new FakeGateway(first);
        new WalletActions(store, gateway).remove(card.getId());
        assertEquals(0, gateway.stops);
        assertEquals(first, gateway.active);
    }

    @Test
    public void failedStorageAfterStopRetainsCardAndReportsInactiveHardware() throws Exception {
        AccessCardProfile card = store.add("Home", first, 0, 0);
        FakeGateway gateway = new FakeGateway(first);
        storage.failWrite = true;
        assertThrows(
                IOException.class, () -> new WalletActions(store, gateway).remove(card.getId()));
        assertNull(gateway.active);
        assertEquals(1, store.load().getCards().size());
    }

    private static final class FakeGateway implements WalletActions.Gateway {
        CardIdentity active;
        boolean allowStop = true;
        int stops;

        FakeGateway(CardIdentity active) {
            this.active = active;
        }

        public CardIdentity activeIdentity() {
            return active;
        }

        public boolean stop() {
            stops++;
            if (allowStop) active = null;
            return allowStop;
        }
    }

    private static final class MemoryStorage implements CardStore.Storage {
        byte[] bytes;
        boolean failWrite;

        public byte[] read() {
            return bytes == null ? null : bytes.clone();
        }

        public void write(byte[] bytes) throws IOException {
            if (failWrite) throw new IOException("Simulated storage failure");
            this.bytes = bytes.clone();
        }
    }
}
