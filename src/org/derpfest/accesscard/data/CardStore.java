// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.data;

import org.derpfest.accesscard.model.AccessCardProfile;
import org.derpfest.accesscard.model.CardIdentity;
import org.derpfest.accesscard.model.WalletSnapshot;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Versioned, bounded wallet storage. A failed write never replaces the previous wallet. */
public final class CardStore {
    private static final int MAGIC = 0x41434344;
    private static final int VERSION = 2;
    private static final int MAX_BYTES = 256 * 1024;
    public interface Storage {
        byte[] read() throws IOException;
        void write(byte[] data) throws IOException;
    }
    public static final class DuplicateCardException extends IOException {
        public DuplicateCardException() { super("Card already exists"); }
    }
    private final Storage storage;
    public CardStore(Storage storage) { this.storage = storage; }

    public static CardStore inDirectory(File directory) {
        Path file = new File(directory, "wallet.acc").toPath();
        return new CardStore(new Storage() {
            public byte[] read() throws IOException {
                if (!Files.exists(file)) return null;
                if (Files.size(file) > MAX_BYTES) throw new IOException("Wallet exceeds size limit");
                return Files.readAllBytes(file);
            }
            public void write(byte[] bytes) throws IOException {
                Files.createDirectories(directory.toPath());
                Path temp = Files.createTempFile(directory.toPath(), ".wallet-", ".tmp");
                try {
                    try (FileOutputStream output = new FileOutputStream(temp.toFile())) {
                        output.write(bytes);
                        output.getFD().sync();
                    }
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        });
    }

    public synchronized WalletSnapshot load() throws IOException {
        byte[] bytes = storage.read();
        if (bytes == null) return WalletSnapshot.empty();
        if (bytes.length < 20 || bytes.length > MAX_BYTES) throw new IOException("Invalid wallet");
        try {
            DataInputStream header = new DataInputStream(new ByteArrayInputStream(bytes));
            if (header.readInt() != MAGIC) throw new IOException("Unknown wallet format");
            int version = header.readInt();
            if (version != 1 && version != VERSION) {
                throw new IOException("Unsupported wallet version: " + version);
            }
            int checksumLength = version == 1 ? 8 : 32;
            if (bytes.length < 14 + checksumLength) throw new IOException("Truncated wallet");
            int payloadLength = bytes.length - checksumLength;
            if (version == 1) {
                CRC32 crc = new CRC32();
                crc.update(bytes, 0, payloadLength);
                DataInputStream trailer = new DataInputStream(
                        new ByteArrayInputStream(bytes, payloadLength, checksumLength));
                if (crc.getValue() != trailer.readLong()) throw new IOException("Wallet checksum mismatch");
            } else if (!MessageDigest.isEqual(sha256(Arrays.copyOf(bytes, payloadLength)),
                    Arrays.copyOfRange(bytes, payloadLength, bytes.length))) {
                throw new IOException("Wallet checksum mismatch");
            }
            DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(bytes, 8, payloadLength - 8));
            String defaultId = input.readUTF();
            int count = input.readInt();
            if (count < 0 || count > 512) throw new IOException("Invalid card count");
            List<AccessCardProfile> cards = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String id = input.readUTF();
                String name = input.readUTF();
                int color = input.readUnsignedByte();
                int icon = input.readUnsignedByte();
                long created = input.readLong();
                int length = input.readUnsignedByte();
                if (length != 4 && length != 7 && length != 10) throw new IOException("Invalid UID");
                byte[] uid = new byte[length];
                input.readFully(uid);
                byte[] atqa = new byte[2];
                input.readFully(atqa);
                CardIdentity identity = new CardIdentity(uid, atqa, input.readUnsignedByte());
                cards.add(new AccessCardProfile(id, name, identity, color, icon, created));
            }
            if (input.available() != 0) throw new IOException("Unexpected wallet data");
            WalletSnapshot wallet = new WalletSnapshot(cards, defaultId.isEmpty() ? null : defaultId);
            // Validate the entire old record before atomically migrating it. Never rewrite
            // corrupt data or unknown versions, and propagate migration write failures.
            if (version == 1) save(wallet);
            return wallet;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid wallet data", e);
        }
    }

    public synchronized AccessCardProfile add(String name, CardIdentity identity,
            int color, int icon) throws IOException {
        return add(UUID.randomUUID().toString(), name, identity, color, icon);
    }

    /** A retained reader operation uses its UUID so retry after process death is idempotent. */
    public synchronized AccessCardProfile add(String id, String name, CardIdentity identity,
            int color, int icon) throws IOException {
        UUID.fromString(id);
        WalletSnapshot current = load();
        for (AccessCardProfile card : current.getCards()) {
            if (card.getId().equals(id)) {
                if (!card.getIdentity().equals(identity)) throw new IOException("Reused operation ID");
                return card;
            }
        }
        for (AccessCardProfile card : current.getCards()) {
            if (card.getIdentity().getUidHex().equals(identity.getUidHex())) {
                throw new DuplicateCardException();
            }
        }
        AccessCardProfile card = new AccessCardProfile(id, name,
                identity, color, icon, System.currentTimeMillis());
        List<AccessCardProfile> cards = new ArrayList<>(current.getCards());
        cards.add(card);
        save(new WalletSnapshot(cards,
                current.getCards().isEmpty() ? card.getId() : current.getDefaultId()));
        return card;
    }

    public synchronized void update(AccessCardProfile replacement) throws IOException {
        WalletSnapshot current = load();
        List<AccessCardProfile> cards = new ArrayList<>(current.getCards());
        int index = find(cards, replacement.getId());
        if (!cards.get(index).getIdentity().equals(replacement.getIdentity())) {
            throw new IOException("Identity cannot be edited");
        }
        cards.set(index, replacement);
        save(new WalletSnapshot(cards, current.getDefaultId()));
    }

    public synchronized void setDefault(String id) throws IOException {
        WalletSnapshot current = load();
        if (id != null) find(current.getCards(), id);
        save(new WalletSnapshot(current.getCards(), id));
    }

    public synchronized void move(String id, int delta) throws IOException {
        WalletSnapshot current = load();
        List<AccessCardProfile> cards = new ArrayList<>(current.getCards());
        int from = find(cards, id);
        int to = Math.max(0, Math.min(cards.size() - 1, from + delta));
        if (from == to) return;
        cards.add(to, cards.remove(from));
        save(new WalletSnapshot(cards, current.getDefaultId()));
    }

    public synchronized void remove(String id) throws IOException {
        WalletSnapshot current = load();
        List<AccessCardProfile> cards = new ArrayList<>(current.getCards());
        cards.remove(find(cards, id));
        save(new WalletSnapshot(cards, id.equals(current.getDefaultId()) ? null : current.getDefaultId()));
    }

    private static int find(List<AccessCardProfile> cards, String id) throws IOException {
        for (int i = 0; i < cards.size(); i++) if (cards.get(i).getId().equals(id)) return i;
        throw new IOException("Unknown card");
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void save(WalletSnapshot wallet) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeUTF(wallet.getDefaultId() == null ? "" : wallet.getDefaultId());
        output.writeInt(wallet.getCards().size());
        for (AccessCardProfile card : wallet.getCards()) {
            output.writeUTF(card.getId());
            output.writeUTF(card.getName());
            output.writeByte(card.getColor());
            output.writeByte(card.getIcon());
            output.writeLong(card.getCreatedAt());
            output.writeByte(card.getIdentity().getUid().length);
            output.write(card.getIdentity().getUid());
            output.write(card.getIdentity().getAtqa());
            output.writeByte(card.getIdentity().getSak());
        }
        output.flush();
        output.write(sha256(bytes.toByteArray()));
        output.flush();
        if (bytes.size() > MAX_BYTES) throw new IOException("Wallet exceeds size limit");
        storage.write(bytes.toByteArray());
    }
}
