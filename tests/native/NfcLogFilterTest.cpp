// SPDX-License-Identifier: Apache-2.0
#include "NfcLogFilter.h"
#include <gtest/gtest.h>

TEST(NfcLogFilter, IncludesNfcProcessesAndPlatformVendorTags) {
  EXPECT_TRUE(accesscard::isNfcLog(1027, "RegisteredAidCache"));
  EXPECT_TRUE(accesscard::isNfcLog(101027, "unknown-vendor-tag"));
  EXPECT_TRUE(accesscard::isNfcLog(1000, "NfcService"));
  EXPECT_TRUE(accesscard::isNfcLog(1000, "android.hardware.nfc-service"));
  EXPECT_TRUE(accesscard::isNfcLog(1000, "TmsNciHal"));
  EXPECT_TRUE(accesscard::isNfcLog(10000, "AccessCard"));
}

TEST(NfcLogFilter, ExcludesUnrelatedApplicationAndSystemLogs) {
  EXPECT_FALSE(accesscard::isNfcLog(10000, "ActivityManager"));
  EXPECT_FALSE(accesscard::isNfcLog(1000, "WindowManager"));
  EXPECT_FALSE(accesscard::isNfcLog(10000, "FinancialActivity"));
  EXPECT_FALSE(accesscard::isNfcLog(1000, "ATMS"));
  EXPECT_FALSE(accesscard::isNfcLog(10000, ""));
}
