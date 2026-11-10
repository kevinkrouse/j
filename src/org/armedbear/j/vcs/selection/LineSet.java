/*
 * LineSet.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j.vcs.selection;

import java.util.BitSet;
import java.util.Objects;

/** An immutable set of indexes into a hunk's lines. */
public final class LineSet {
    private final BitSet bits;

    public LineSet(BitSet bits) {
        this.bits = (BitSet) Objects.requireNonNull(bits).clone();
    }

    public BitSet toBitSet() {
        return (BitSet) bits.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LineSet lines && bits.equals(lines.bits);
    }

    @Override
    public int hashCode() {
        return bits.hashCode();
    }
}
