// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.model;

import java.util.*;

public final class AccessCardProfile {
    public static final int MAX_TESTS = 20;
    private final String id, name, notes;
    private final CardIdentity identity;
    private final int color, icon;
    private final long createdAt;
    private final List<CardTestRecord> tests;

    public AccessCardProfile(String id, String name, CardIdentity identity,
            int color, int icon, long createdAt) {
        this(id, name, identity, color, icon, createdAt, "", Collections.emptyList());
    }
    public AccessCardProfile(String id, String name, CardIdentity identity,
            int color, int icon, long createdAt, String notes, List<CardTestRecord> tests) {
        UUID.fromString(id);
        String trimmed = Objects.requireNonNull(name).trim();
        if (trimmed.isEmpty() || trimmed.length() > 64 || color < 0 || color > 5
                || icon < 0 || icon > 4 || createdAt < 0 || notes == null || notes.length() > 512
                || tests.size() > MAX_TESTS) throw new IllegalArgumentException("Invalid card metadata");
        Set<String> ids = new HashSet<>();
        for (CardTestRecord test : tests) if (!ids.add(test.getId()))
            throw new IllegalArgumentException("Duplicate test record");
        this.id = id; this.name = trimmed; this.identity = Objects.requireNonNull(identity);
        this.color = color; this.icon = icon; this.createdAt = createdAt; this.notes = notes.trim();
        this.tests = Collections.unmodifiableList(new ArrayList<>(tests));
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public CardIdentity getIdentity() { return identity; }
    public int getColor() { return color; }
    public int getIcon() { return icon; }
    public long getCreatedAt() { return createdAt; }
    public String getNotes() { return notes; }
    public List<CardTestRecord> getTests() { return tests; }
    public AccessCardProfile withAppearance(String name, int color, int icon) {
        return new AccessCardProfile(id, name, identity, color, icon, createdAt, notes, tests);
    }
    public AccessCardProfile withNotes(String notes) {
        return new AccessCardProfile(id, name, identity, color, icon, createdAt, notes, tests);
    }
    public AccessCardProfile withTest(CardTestRecord test) {
        List<CardTestRecord> updated = new ArrayList<>(tests);
        updated.removeIf(item -> item.getId().equals(test.getId()));
        updated.add(0, test);
        if (updated.size() > MAX_TESTS) updated.remove(updated.size() - 1);
        return new AccessCardProfile(id, name, identity, color, icon, createdAt, notes, updated);
    }
    public AccessCardProfile withoutTest(String testId) {
        List<CardTestRecord> updated = new ArrayList<>(tests);
        updated.removeIf(item -> item.getId().equals(testId));
        return new AccessCardProfile(id, name, identity, color, icon, createdAt, notes, updated);
    }
}
