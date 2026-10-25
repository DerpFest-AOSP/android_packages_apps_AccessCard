// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.data;

import java.io.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.derpfest.accesscard.model.WalletSnapshot;

/** Portable, password-encrypted wallet data only. Contains no activation or controller state. */
public final class WalletBackup {
    private static final long MAGIC = 0x41434241434B5550L;
    private static final int VERSION = 1;
    private static final int ITERATIONS = 600000;
    public static final int MAX_BYTES = CardStore.MAX_BYTES + 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private WalletBackup() {}

    public static byte[] encrypt(WalletSnapshot wallet, char[] password) throws IOException {
        validatePassword(password);
        byte[] salt = new byte[16], iv = new byte[12];
        RANDOM.nextBytes(salt); RANDOM.nextBytes(iv);
        byte[] plain = CardStore.encode(wallet), key = null;
        try {
            byte[] header = header(salt, iv, plain.length + 16);
            key = key(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(header);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(header); bytes.write(cipher.doFinal(plain));
            return bytes.toByteArray();
        } catch (GeneralSecurityException e) { throw new IOException("Backup encryption failed", e); }
        finally { Arrays.fill(plain, (byte) 0); if (key != null) Arrays.fill(key, (byte) 0); }
    }
    public static WalletSnapshot decrypt(byte[] backup, char[] password) throws IOException {
        validatePassword(password);
        if (backup == null || backup.length < 64 || backup.length > MAX_BYTES)
            throw new IOException("Invalid backup size");
        byte[] key = null, plain = null;
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(backup));
            if (input.readLong() != MAGIC || input.readInt() != VERSION || input.readInt() != ITERATIONS)
                throw new IOException("Unsupported backup");
            byte[] salt = new byte[16], iv = new byte[12];
            input.readFully(salt); input.readFully(iv);
            int length = input.readInt();
            int headerLength = backup.length - input.available();
            if (length < 16 || length != input.available()) throw new IOException("Truncated backup");
            key = key(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(backup, 0, headerLength);
            plain = cipher.doFinal(backup, headerLength, length);
            return CardStore.decode(plain);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Invalid backup or password", e);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }
    private static void validatePassword(char[] password) {
        if (password == null || password.length < 12 || password.length > 256)
            throw new IllegalArgumentException("Password must have 12 to 256 characters");
    }
    private static byte[] key(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private static byte[] header(byte[] salt, byte[] iv, int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(MAGIC); out.writeInt(VERSION); out.writeInt(ITERATIONS);
        out.write(salt); out.write(iv); out.writeInt(length); out.flush();
        return bytes.toByteArray();
    }
}
