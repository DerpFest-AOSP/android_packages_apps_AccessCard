// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <algorithm>
#include <cctype>
#include <string>
#include <string_view>

namespace accesscard {
inline bool isNfcLog(unsigned uid, std::string_view tag) {
  std::string lower(tag);
  std::transform(lower.begin(), lower.end(), lower.begin(),
                 [](unsigned char c) { return std::tolower(c); });
  // Host emulation logs can carry payment AIDs and APDU traffic.
  if (lower == "hostemulationmanager")
    return false;
  if (lower.find("accesscard") != std::string::npos)
    return true;
  // FIRST_APPLICATION_UID. Other apps can choose a tag that looks like NFC.
  const unsigned appId = uid % 100000;
  if (appId >= 10000)
    return false;
  if (appId == 1027)
    return true; // Android's NFC uid, including secondary users.
  return lower.find("nfc") != std::string::npos || lower.starts_with("nci") ||
         lower.starts_with("libnci") || lower.starts_with("nxpnci") ||
         lower.starts_with("nxp_nci") || lower.starts_with("stnci") ||
         lower.starts_with("tmsnci") || lower == "routingmanager" ||
         lower == "aidroutingmanager";
}
} // namespace accesscard
