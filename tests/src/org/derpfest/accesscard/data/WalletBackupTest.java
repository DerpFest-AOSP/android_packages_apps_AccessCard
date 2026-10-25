// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.data;

import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import org.derpfest.accesscard.model.*;
import org.junit.Test;

public class WalletBackupTest {
    private static final char[] PASSWORD = "a long backup passphrase".toCharArray();
    private static CardIdentity identity(int n) {
        return new CardIdentity(new byte[]{4, 1, 2, (byte)n}, new byte[]{0,4}, 8);
    }
    private static AccessCardProfile card(int n) {
        return new AccessCardProfile(UUID.randomUUID().toString(), "Card " + n, identity(n), 1, 2, 123)
                .withNotes("Private note " + n).withTest(new CardTestRecord(UUID.randomUUID().toString(),
                    "Lobby", "Manual only", 456, CardTestRecord.Result.ACCEPTED));
    }
    private static WalletSnapshot wallet() {
        AccessCardProfile a = card(1), b = card(2);
        return new WalletSnapshot(Arrays.asList(a,b), b.getId());
    }
    @Test public void authenticatedRoundTripPreservesOnlyWalletData() throws Exception {
        WalletSnapshot original = wallet();
        byte[] encrypted = WalletBackup.encrypt(original, PASSWORD);
        assertFalse(new String(encrypted, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Private note"));
        WalletSnapshot restored = WalletBackup.decrypt(encrypted, PASSWORD);
        assertEquals(original.getDefaultId(), restored.getDefaultId());
        assertEquals(original.getCards().get(1).getIdentity(), restored.getCards().get(1).getIdentity());
        assertEquals("Private note 1", restored.getCards().get(0).getNotes());
        assertEquals("Lobby", restored.getCards().get(0).getTests().get(0).getLocation());
    }
    @Test public void freshSaltAndNonceMakeEveryBackupDifferent() throws Exception {
        WalletSnapshot original = wallet();
        assertFalse(Arrays.equals(WalletBackup.encrypt(original, PASSWORD), WalletBackup.encrypt(original, PASSWORD)));
    }
    @Test public void rejectsWrongPasswordAndAuthenticatedDataTampering() throws Exception {
        byte[] encrypted = WalletBackup.encrypt(wallet(), PASSWORD);
        assertThrows(IOException.class, () -> WalletBackup.decrypt(encrypted, "a different password".toCharArray()));
        byte[] saltChanged = encrypted.clone(); saltChanged[20] ^= 1;
        assertThrows(IOException.class, () -> WalletBackup.decrypt(saltChanged, PASSWORD));
        encrypted[encrypted.length - 1] ^= 1;
        assertThrows(IOException.class, () -> WalletBackup.decrypt(encrypted, PASSWORD));
    }
    @Test public void rejectsTruncationTrailingBytesUnknownFormatsAndOversizedInput() throws Exception {
        byte[] encrypted = WalletBackup.encrypt(wallet(), PASSWORD);
        assertThrows(IOException.class, () -> WalletBackup.decrypt(Arrays.copyOf(encrypted, encrypted.length - 1), PASSWORD));
        assertThrows(IOException.class, () -> WalletBackup.decrypt(Arrays.copyOf(encrypted, encrypted.length + 1), PASSWORD));
        encrypted[11] = 99;
        assertThrows(IOException.class, () -> WalletBackup.decrypt(encrypted, PASSWORD));
        assertThrows(IOException.class, () -> WalletBackup.decrypt(new byte[WalletBackup.MAX_BYTES+1], PASSWORD));
    }
    @Test public void unicodePasswordsWorkAndInvalidLengthsAreRejected() throws Exception {
        char[] password = "這是一個足夠長度的測試用密碼🔑".toCharArray();
        assertEquals(2, WalletBackup.decrypt(WalletBackup.encrypt(wallet(), password), password).getCards().size());
        assertThrows(IllegalArgumentException.class, () -> WalletBackup.encrypt(wallet(), new char[11]));
        assertThrows(IllegalArgumentException.class, () -> WalletBackup.encrypt(wallet(), new char[257]));
    }
    @Test public void mergePreservesExistingDataAndRemapsCollidingIds() throws Exception {
        Memory storage = new Memory(); CardStore store = new CardStore(storage);
        AccessCardProfile existing = store.add("Existing", identity(1), 0, 0).withNotes("Keep me");
        store.update(existing);
        AccessCardProfile incoming = new AccessCardProfile(existing.getId(), "New", identity(2), 2, 3, 0);
        WalletSnapshot imported = new WalletSnapshot(Arrays.asList(card(1), incoming), incoming.getId());
        CardStore.ImportResult result = store.merge(imported);
        assertEquals(1, result.added); assertEquals(1, result.skipped);
        WalletSnapshot merged = store.load();
        assertEquals(existing.getId(), merged.getDefaultId());
        assertEquals("Keep me", merged.getCards().get(0).getNotes());
        assertNotEquals(existing.getId(), merged.getCards().get(1).getId());
        assertEquals(identity(2), merged.getCards().get(1).getIdentity());
    }
    @Test public void mergeIntoEmptyWalletKeepsImportedOrderAndDefault() throws Exception {
        CardStore store = new CardStore(new Memory()); WalletSnapshot incoming = wallet();
        store.merge(incoming);
        assertEquals(incoming.getDefaultId(), store.load().getDefaultId());
        assertEquals(incoming.getCards().get(0).getId(), store.load().getCards().get(0).getId());
    }
    @Test public void failedMergePreservesCurrentWalletAndDuplicateImportDoesNotWrite() throws Exception {
        Memory storage = new Memory(); CardStore store = new CardStore(storage);
        store.add("Existing", identity(1), 0, 0); byte[] before = storage.bytes.clone();
        storage.fail = true;
        assertThrows(IOException.class, () -> store.merge(wallet()));
        assertArrayEquals(before, storage.bytes);
        assertEquals(0, store.merge(new WalletSnapshot(Collections.singletonList(card(1)), null)).added);
        storage.fail = false; storage.bytes[15] ^= 1; before = storage.bytes.clone();
        assertThrows(IOException.class, () -> store.merge(wallet()));
        assertArrayEquals(before, storage.bytes);
    }
    @Test public void oversizedMergeIsRejectedBeforeReplacingCurrentWallet() throws Exception {
        Memory storage = new Memory();
        List<AccessCardProfile> cards = new ArrayList<>();
        for (int i=1; i<=512; i++) cards.add(new AccessCardProfile(UUID.randomUUID().toString(), "Card",
                new CardIdentity(java.nio.ByteBuffer.allocate(4).putInt(i).array(), new byte[]{0,4}, 8), 0, 0, 0));
        storage.bytes = CardStore.encode(new WalletSnapshot(cards, cards.get(0).getId()));
        byte[] before = storage.bytes.clone();
        assertThrows(IOException.class, () -> new CardStore(storage).merge(wallet()));
        assertArrayEquals(before, storage.bytes);
    }

    private static final class Memory implements CardStore.Storage {
        byte[] bytes; boolean fail;
        public byte[] read() { return bytes == null ? null : bytes.clone(); }
        public void write(byte[] data) throws IOException {
            if (fail) throw new IOException("Test storage failure"); bytes = data.clone();
        }
    }
}
