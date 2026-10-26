// SPDX-License-Identifier: Apache-2.0
#include "LogStream.h"
#include <gtest/gtest.h>
#include <sys/socket.h>

TEST(LogStream, RetainsConnectionAcrossQuietSamples) {
  int sockets[2];
  ASSERT_EQ(0, socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets));
  accesscard::LogStream stream;
  stream.adopt(sockets[0]);
  char bytes[32];
  EXPECT_EQ(0, stream.read(bytes, sizeof(bytes), 1));
  ASSERT_EQ(6, send(sockets[1], "first", 6, 0));
  EXPECT_EQ(6, stream.read(bytes, sizeof(bytes), 10));
  EXPECT_STREQ("first", bytes);
  EXPECT_EQ(0, stream.read(bytes, sizeof(bytes), 1));
  ASSERT_EQ(7, send(sockets[1], "second", 7, 0));
  EXPECT_EQ(7, stream.read(bytes, sizeof(bytes), 10));
  EXPECT_STREQ("second", bytes);
  close(sockets[1]);
}

TEST(LogStream, DetectsDisconnectAndExplicitClose) {
  int sockets[2];
  ASSERT_EQ(0, socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets));
  accesscard::LogStream stream;
  stream.adopt(sockets[0]);
  close(sockets[1]);
  char bytes[32];
  EXPECT_EQ(-ECONNRESET, stream.read(bytes, sizeof(bytes), 10));
  stream.close();
  EXPECT_EQ(-ENOTCONN, stream.read(bytes, sizeof(bytes), 10));
  EXPECT_FALSE(stream.connected());
}

TEST(LogStream, RejectsTruncatedPackets) {
  int sockets[2];
  ASSERT_EQ(0, socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets));
  accesscard::LogStream stream;
  stream.adopt(sockets[0]);
  ASSERT_EQ(16, send(sockets[1], "oversized entry", 16, 0));
  char bytes[4];
  EXPECT_EQ(-EMSGSIZE, stream.read(bytes, sizeof(bytes), 10));
  close(sockets[1]);
}
