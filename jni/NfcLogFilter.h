// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <algorithm>
#include <cctype>
#include <string>
#include <string_view>

namespace accesscard {
inline bool isNfcLog(unsigned uid, std::string_view tag) {
  if (uid % 100000 == 1027)
    return true; // Android's NFC uid, including secondary users.
  std::string lower(tag);
  std::transform(lower.begin(), lower.end(), lower.begin(),
                 [](unsigned char c) { return std::tolower(c); });
  return lower.find("nfc") != std::string::npos || lower.starts_with("nci") ||
         lower.starts_with("libnci") || lower.starts_with("nxpnci") ||
         lower.starts_with("nxp_nci") || lower.starts_with("stnci") ||
         lower.starts_with("tmsnci") ||
         lower.find("accesscard") != std::string::npos ||
         lower == "routingmanager" || lower == "aidroutingmanager" ||
         lower == "hostemulationmanager";
}
} // namespace accesscard
