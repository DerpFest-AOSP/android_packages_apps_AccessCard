// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.model;

import java.util.Objects;
import java.util.UUID;

/** A user's observation, never a controller or access-reader certification. */
public final class CardTestRecord {
    public enum Result { ACCEPTED, REJECTED, INCONCLUSIVE }
    private final String id, location, note;
    private final long testedAt;
    private final Result result;
    public CardTestRecord(String id, String location, String note, long testedAt, Result result) {
        UUID.fromString(id);
        this.location = Objects.requireNonNull(location).trim();
        this.note = Objects.requireNonNull(note).trim();
        if (this.location.isEmpty() || this.location.length() > 80 || this.note.length() > 256
                || testedAt < 0) throw new IllegalArgumentException("Invalid test record");
        this.id = id; this.testedAt = testedAt; this.result = Objects.requireNonNull(result);
    }
    public String getId() { return id; }
    public String getLocation() { return location; }
    public String getNote() { return note; }
    public long getTestedAt() { return testedAt; }
    public Result getResult() { return result; }
}
