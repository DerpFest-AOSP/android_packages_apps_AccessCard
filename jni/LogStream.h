// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cerrno>
#include <cstddef>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

namespace accesscard {
// Keep one approved logd stream open instead of requesting access for every
// sample.
class LogStream {
public:
  ~LogStream() { close(); }
  bool connected() const { return fd_ >= 0; }
  void adopt(int fd) {
    close();
    fd_ = fd;
  }
  void close() {
    if (fd_ >= 0)
      ::close(fd_);
    fd_ = -1;
  }
  int read(void *buffer, size_t capacity, int timeoutMs) {
    if (fd_ < 0)
      return -ENOTCONN;
    pollfd descriptor{fd_, POLLIN, 0};
    int ready = ::poll(&descriptor, 1, timeoutMs);
    if (ready < 0)
      return errno == EINTR ? 0 : -errno;
    if (ready == 0)
      return 0;
    ssize_t count = ::recv(fd_, buffer, capacity, MSG_DONTWAIT | MSG_TRUNC);
    if (count < 0)
      return errno == EAGAIN || errno == EINTR ? 0 : -errno;
    if (count == 0)
      return -ECONNRESET;
    if (static_cast<size_t>(count) > capacity)
      return -EMSGSIZE;
    return count;
  }

private:
  int fd_ = -1;
};
} // namespace accesscard
