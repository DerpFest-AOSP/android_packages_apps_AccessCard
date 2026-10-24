// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WalletSnapshot {
    private final List<AccessCardProfile> cards;
    private final String defaultId;
    public WalletSnapshot(List<AccessCardProfile> cards, String defaultId) {
        if (cards.size() > 512) throw new IllegalArgumentException("Too many cards");
        Set<String> ids = new HashSet<>();
        Set<String> uids = new HashSet<>();
        for (AccessCardProfile card : cards) {
            if (!ids.add(card.getId()) || !uids.add(card.getIdentity().getUidHex())) {
                throw new IllegalArgumentException("Duplicate card identity");
            }
        }
        if (defaultId != null && !ids.contains(defaultId)) {
            throw new IllegalArgumentException("Unknown default card");
        }
        this.cards = Collections.unmodifiableList(new ArrayList<>(cards));
        this.defaultId = defaultId;
    }
    public List<AccessCardProfile> getCards() { return cards; }
    public String getDefaultId() { return defaultId; }
    public static WalletSnapshot empty() { return new WalletSnapshot(Collections.emptyList(), null); }
}
