/*
 * TargetTest.java
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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.junit.jupiter.api.Test;

public class TargetTest {
    @Test
    public void partialSelectionsOwnTheirBitsAndExposeOnlyCopies() {
        BitSet bits = new BitSet();
        bits.set(3);
        Hunk hunk = new Hunk(1, 1, 1, 1, "", List.of());
        Target.Part part = Target.Part.of(null, "working", null, hunk, bits);
        bits.clear();
        assertTrue(part.lines().get(3));
        part.lines().clear();
        assertTrue(part.lines().get(3));
        BitSet moved = part.lines();
        moved.set(4);
        Target.Part next = part.withLines(moved);
        assertFalse(part.lines().get(4));
        assertTrue(next.lines().get(4));
        assertEquals(new LineSet(part.lines()), part.selection());
    }

    @Test
    public void wholeHunksAndEmptyLineSelectionsStayDistinct() {
        Target.Part whole = Target.Part.of(null, "working", null, null, null);
        Target.Part empty = Target.Part.of(null, "working", null, null, new BitSet());
        assertNull(whole.lines());
        assertTrue(empty.lines().isEmpty());
        assertNotEquals(whole, empty);
    }

    @Test
    public void fileGroupsAndListMembershipCannotBeChangedAfterCapture() {
        FileItem file = new FileItem(null, 'M', false, null);
        ArrayList<FileItem> files = new ArrayList<>(List.of(file));
        Target.Files target = new Target.Files(null, "working", files);
        files.clear();
        assertEquals(List.of(file), target.files());
        assertThrows(UnsupportedOperationException.class, () -> target.files().clear());
        BitSet bits = new BitSet();
        bits.set(2);
        var only = new LinkedHashMap<String, BitSet>();
        only.put("hunk", bits);
        Target.Member member = new Target.Member(file, only);
        bits.clear();
        only.clear();
        member.only().get("hunk").clear();
        assertTrue(member.only().get("hunk").get(2));
        assertNull(new Target.Member(file, null).only());
    }
}
