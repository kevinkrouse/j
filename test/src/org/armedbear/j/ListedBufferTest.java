/*
 * ListedBufferTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Going through the buffers as :bnext does: transient ones are skipped. */
public class ListedBufferTest {
    private final List<Buffer> made = new ArrayList<>();
    private final BufferList list = new BufferList();

    private static class Plain extends Buffer {}

    private static final class Secondary extends Buffer {
        private final Buffer primary;

        Secondary(Buffer primary) {
            this.primary = primary;
        }

        @Override
        public boolean isPrimary() {
            return false;
        }

        @Override
        public boolean isSecondary() {
            return true;
        }

        @Override
        public Buffer getPrimary() {
            return primary;
        }
    }

    // The constructor puts it in j's own list too; it comes out again after.
    private <T extends Buffer> T add(T buffer, boolean isTransient) {
        made.add(buffer);
        buffer.setTransient(isTransient);
        list.add(buffer);
        return buffer;
    }

    @AfterEach
    public void tearDown() {
        for (Buffer b : made)
            Editor.getBufferList().remove(b);
    }

    @Test
    public void transientBuffersAreSkippedBothWays() {
        final Buffer a = add(new Plain(), false);
        add(new Plain(), true);
        final Buffer c = add(new Plain(), false);
        assertSame(c, list.getNextListedBuffer(a));
        assertSame(a, list.getPreviousListedBuffer(c));
    }

    @Test
    public void onlyTransientOnesLeftIsNone() {
        final Buffer a = add(new Plain(), false);
        add(new Plain(), true);
        assertNull(list.getNextListedBuffer(a));
        assertNull(list.getPreviousListedBuffer(a));
    }

    @Test
    public void fromASecondaryWhosePrimaryIsTransientTheWalkEnds() {
        // The walk goes by primaries and never reaches the secondary it
        // started from: it must still stop.
        final Buffer primary = add(new Plain(), true);
        final Buffer secondary = add(new Secondary(primary), false);
        add(new Plain(), true);
        assertNull(list.getNextListedBuffer(secondary));
    }
}
