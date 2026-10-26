// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.tools

/** Direct logd access; never starts logcat, a shell or a root process. */
object NativeNfcLogs {
    init {
        System.loadLibrary("accesscard_diagnostics")
    }

    @JvmStatic external fun close()

    @JvmStatic external fun read(sinceNanos: Long, output: ByteArray): LongArray
}
