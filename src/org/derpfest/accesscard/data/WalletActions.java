// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.data;

import java.io.IOException;
import org.derpfest.accesscard.model.AccessCardProfile;
import org.derpfest.accesscard.model.CardIdentity;

/** Storage mutations which must be coordinated with controller state. */
public final class WalletActions {
    public interface Gateway {
        CardIdentity activeIdentity() throws IOException;
        boolean stop() throws IOException;
    }
    public static final class StopFailedException extends IOException {
        public StopFailedException() { super("Active identity could not be restored"); }
    }
    private final CardStore store;
    private final Gateway gateway;
    public WalletActions(CardStore store, Gateway gateway) {
        this.store = store;
        this.gateway = gateway;
    }
    public void remove(String id) throws IOException {
        AccessCardProfile card = store.load().getCards().stream()
                .filter(item -> item.getId().equals(id)).findFirst()
                .orElseThrow(() -> new IOException("Unknown card"));
        if (card.getIdentity().equals(gateway.activeIdentity()) && !gateway.stop()) {
            throw new StopFailedException();
        }
        store.remove(id);
    }
}
