// SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include <log/log_read.h>

#include "LogStream.h"
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "NfcLogFilter.h"

namespace {
std::mutex mutex;
accesscard::LogStream stream;
jlong cursor = 0;
bool systemNfcSeen = false;
bool hasPending = false;
log_msg pending;

int connectLogd(jlong since) {
  int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
  if (fd < 0)
    return -errno;
  sockaddr_un address{};
  address.sun_family = AF_UNIX;
  strcpy(address.sun_path, "/dev/socket/logdr");
  timeval timeout{1, 0};
  setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
  char command[192];
  int length = snprintf(command, sizeof(command),
                        "stream lids=0,1,3,4 start=%lld.%09lld",
                        static_cast<long long>(since / 1000000000LL),
                        static_cast<long long>(since % 1000000000LL));
  if (connect(fd, reinterpret_cast<sockaddr *>(&address), sizeof(address)) <
          0 ||
      send(fd, command, length, MSG_NOSIGNAL) != length) {
    int error = errno ? -errno : -EIO;
    ::close(fd);
    return error;
  }
  stream.adopt(fd);
  cursor = since;
  systemNfcSeen = false;
  hasPending = false;
  return 0;
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_org_derpfest_accesscard_tools_NativeNfcLogs_close(JNIEnv *, jclass) {
  std::lock_guard guard(mutex);
  stream.close();
  hasPending = false;
  systemNfcSeen = false;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_derpfest_accesscard_tools_NativeNfcLogs_read(JNIEnv *env, jclass,
                                                      jlong since,
                                                      jbyteArray output) {
  std::lock_guard guard(mutex);
  // cursor, status (0=caught up, 1=more, negative=errno), scanned, selected,
  // bytes, NFC uid seen.
  jlong result[] = {since, 0, 0, 0, 0, 0};
  const size_t capacity = env->GetArrayLength(output);
  std::string text;
  if (stream.connected() && since != cursor)
    stream.close();
  if (!stream.connected())
    result[1] = connectLogd(since);
  if (stream.connected()) {
    const auto deadline =
        std::chrono::steady_clock::now() + std::chrono::seconds(1);
    while (true) {
      log_msg message{};
      int read;
      if (hasPending) {
        message = pending;
        read = message.len();
        hasPending = false;
      } else {
        read = stream.read(message.buf, LOGGER_ENTRY_MAX_LEN, 50);
      }
      if (read < 0) {
        result[1] = read;
        stream.close();
        break;
      }
      if (read == 0)
        break;
      ++result[2];
      const char *payload = message.msg();
      const size_t length = message.entry.len;
      if (!payload || message.len() > static_cast<unsigned>(read) || length < 3)
        continue;
      const char *tagEnd =
          static_cast<const char *>(memchr(payload + 1, 0, length - 1));
      if (!tagEnd)
        continue;
      std::string_view tag(payload + 1, tagEnd - payload - 1);
      if (accesscard::isNfcLog(message.entry.uid, tag)) {
        const char *body = tagEnd + 1;
        size_t bodyLength = length - (body - payload);
        while (bodyLength && body[bodyLength - 1] == 0)
          --bodyLength;
        char header[160];
        snprintf(header, sizeof(header),
                 "%u.%09u uid=%u pid=%d tid=%u buffer=%u p=%u ",
                 message.entry.sec, message.entry.nsec, message.entry.uid,
                 message.entry.pid, message.entry.tid, message.entry.lid,
                 static_cast<unsigned char>(payload[0]));
        std::string line = std::string(header) + std::string(tag) + ": " +
                           std::string(body, bodyLength) + "\n";
        if (text.size() + line.size() > capacity) {
          result[1] = 1;
          pending = message;
          hasPending = true;
          break; // Keep the packet for the next bounded sample.
        }
        text += line;
        ++result[3];
        if (message.entry.uid % 100000 == 1027)
          systemNfcSeen = true;
      }
      result[0] = std::max(result[0], static_cast<jlong>(message.nsec() + 1));
      if (result[2] >= 8192 || std::chrono::steady_clock::now() >= deadline) {
        result[1] = 1;
        break;
      }
    }
  }
  cursor = result[0];
  result[4] = text.size();
  result[5] = systemNfcSeen ? 1 : 0;
  env->SetByteArrayRegion(output, 0, text.size(),
                          reinterpret_cast<const jbyte *>(text.data()));
  jlongArray array = env->NewLongArray(6);
  if (array)
    env->SetLongArrayRegion(array, 0, 6, result);
  return array;
}
