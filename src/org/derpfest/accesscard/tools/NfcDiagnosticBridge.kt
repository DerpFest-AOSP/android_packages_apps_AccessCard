// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.tools

import android.content.Context
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayOutputStream
import java.time.Instant
import org.derpfest.accesscard.data.SystemDiagnosticStore

/** Uses the existing NFC service; no OEM callback registration or RF configuration writes. */
class NfcDiagnosticBridge(private val context: Context) {
    fun lease(enabled: Boolean): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return false
        val extension = adapter.nfcOemExtension
        val method =
            extension.javaClass.getMethod(
                "setNfcATagDiagnosticsEnabled",
                Boolean::class.javaPrimitiveType,
            )
        return method.invoke(extension, enabled) == true
    }

    fun state(): Bundle? {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return null
        val extension = adapter.nfcOemExtension
        return extension.javaClass.getMethod("getNfcATagEmulationState").invoke(extension)
            as? Bundle
    }

    fun dump(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("captured=${Instant.now()}\nsource=nfc Binder.dumpAsync\n".toByteArray())
        val service =
            ServiceManager.checkService("nfc") ?: return "NFC service unavailable\n".toByteArray()
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            service.dumpAsync(pipe[1].fileDescriptor, emptyArray())
            pipe[1].close()
            val fd =
                StructPollfd().apply {
                    this.fd = pipe[0].fileDescriptor
                    events = OsConstants.POLLIN.toShort()
                }
            val deadline = SystemClock.elapsedRealtime() + 8_000
            val buffer = ByteArray(8192)
            val limit = SystemDiagnosticStore.SNAPSHOT_BYTES - 256
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    output.write("\nSNAPSHOT_INCOMPLETE: 8-second timeout\n".toByteArray())
                    break
                }
                if (Os.poll(arrayOf(fd), minOf(remaining, 250).toInt()) == 0) continue
                if (fd.revents.toInt() and OsConstants.POLLIN == 0) {
                    if (fd.revents.toInt() and OsConstants.POLLHUP != 0) break
                    output.write("\nSNAPSHOT_INCOMPLETE: pipe error\n".toByteArray())
                    break
                }
                val count =
                    Os.read(
                        pipe[0].fileDescriptor,
                        buffer,
                        0,
                        minOf(buffer.size, limit - output.size()),
                    )
                if (count <= 0) break
                output.write(buffer, 0, count)
                if (output.size() >= limit) {
                    output.write("\nSNAPSHOT_INCOMPLETE: size limit\n".toByteArray())
                    break
                }
            }
        } finally {
            pipe.forEach { runCatching { it.close() } }
        }
        return output.toByteArray()
    }
}
