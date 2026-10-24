// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.model;

import java.util.Objects;
import java.util.UUID;

public final class AccessCardProfile {
    private final String id;
    private final String name;
    private final CardIdentity identity;
    private final int color;
    private final int icon;
    private final long createdAt;

    public AccessCardProfile(String id, String name, CardIdentity identity,
            int color, int icon, long createdAt) {
        UUID.fromString(id);
        String trimmed = Objects.requireNonNull(name).trim();
        if (trimmed.isEmpty() || trimmed.length() > 64 || color < 0 || color > 5
                || icon < 0 || icon > 4 || createdAt < 0) {
            throw new IllegalArgumentException("Invalid card metadata");
        }
        this.id = id;
        this.name = trimmed;
        this.identity = Objects.requireNonNull(identity);
        this.color = color;
        this.icon = icon;
        this.createdAt = createdAt;
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public CardIdentity getIdentity() { return identity; }
    public int getColor() { return color; }
    public int getIcon() { return icon; }
    public long getCreatedAt() { return createdAt; }
    public AccessCardProfile withAppearance(String name, int color, int icon) {
        return new AccessCardProfile(id, name, identity, color, icon, createdAt);
    }
}
