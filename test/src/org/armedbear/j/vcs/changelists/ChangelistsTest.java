/*
 * ChangelistsTest.java
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

package org.armedbear.j.vcs.changelists;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.Anchors.Placed;
import org.armedbear.j.vcs.changelists.Changelists.Assignment;
import org.armedbear.j.vcs.changelists.Changelists.Change;
import org.armedbear.j.vcs.changelists.Changelists.Part;
import org.armedbear.j.vcs.diff.DiffParser;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.git.GitFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ChangelistsTest {
    @TempDir
    Path tmp;

    @AfterEach
    public void tearDown() {
        GitFixture.reset();
    }

    // a.txt's diff: line 3 changed to THREE, and X added after line 8.
    private static final String DIFF = """
                                       diff --git a/a.txt b/a.txt
                                       --- a/a.txt
                                       +++ b/a.txt
                                       @@ -1,10 +1,11 @@
                                        1
                                        2
                                       -3
                                       +THREE
                                        4
                                        5
                                        6
                                        7
                                        8
                                       +X
                                        9
                                        10
                                       """;

    private static FileDiff diff(String text) {
        return DiffParser.parse(text).get(0);
    }

    // The lists placed puts its changes in, in order.
    private static List<String> lists(List<Placed> placed) {
        return placed.stream().map(Placed::list).toList();
    }

    // DIFF's two changes kept, THREE's in A but its line in B, X's in B.
    private static Changelists kept() {
        final List<Placed> now = Anchors.place(Changelists.initial(), "a.txt", null, diff(DIFF));
        final Change three = Anchors.assignment(now, null).changes().get(0);
        final Change x = Anchors.assignment(now, null).changes().get(1);
        return Changelists.initial()
                .withList("A")
                .withList("B")
                .withActive("A")
                .withComment("A", "Fix three")
                .withFile(
                    "a.txt",
                    Assignment.of(List.of(with(three, "A", List.of(new Part(0, 1, "B"))), with(x, "B", List.of()))))
                .withFile("b.txt", Assignment.whole("B"));
    }

    // c in list, but for parts.
    private static Change with(Change c, String list, List<Part> parts) {
        return new Change(c.hash(), c.row(), c.rows(), c.oldRow(), c.oldRows(), list, parts);
    }

    @Test
    public void keptListsReadBackTheSame() throws Exception {
        final ChangelistStore store = new ChangelistStore(tmp.resolve("j/changelists.xml"));
        assertEquals(Changelists.initial(), store.read(), "nothing kept: the default alone");
        assertTrue(store.write(kept()));
        assertEquals(kept(), store.read());
        assertTrue(Files.readString(store.file()).contains("<comment>Fix three</comment>"));
    }

    @Test
    public void aChangeIsFoundByItsLinesAfterEditsAboveIt() {
        // Two lines added at the top: both changes two rows down.
        final String moved = """
                             diff --git a/a.txt b/a.txt
                             --- a/a.txt
                             +++ b/a.txt
                             @@ -0,0 +1,2 @@
                             +new
                             +lines
                             @@ -1,10 +3,11 @@
                              1
                              2
                             -3
                             +THREE
                              4
                              5
                              6
                              7
                              8
                             +X
                              9
                              10
                             """;
        final List<Placed> placed = Anchors.place(kept(), "a.txt", null, diff(moved));
        // The lines added at the top are new: the active list's.
        assertEquals(List.of("A", "A", "B"), lists(placed));
        assertEquals(List.of(new Part(0, 1, "B")), placed.get(1).parts(), "found by its lines, its parts kept");
        assertEquals(4, Anchors.assignment(placed, null).changes().get(1).row(), "kept again where it is now");
    }

    @Test
    public void aChangeWithOtherLinesGoesToTheListOfTheOneItOverlaps() {
        // X edited to Y in place: no hash matches, its rows do.
        final List<Placed> placed = Anchors.place(kept(), "a.txt", null, diff(DIFF.replace("+X", "+Y")));
        assertEquals(List.of("A", "B"), lists(placed));
    }

    @Test
    public void aChangeGoneIsNotKept() {
        // THREE staged: only X is left unstaged.
        final String left = """
                            diff --git a/a.txt b/a.txt
                            --- a/a.txt
                            +++ b/a.txt
                            @@ -7,4 +7,5 @@
                             7
                             8
                            +X
                             9
                             10
                            """;
        final List<Placed> placed = Anchors.place(kept(), "a.txt", null, diff(left));
        assertEquals(List.of("B"), lists(placed));
        assertEquals(1, Anchors.assignment(placed, null).changes().size());
    }

    @Test
    public void aFileDeletedOrRenamedKeepsItsLists() {
        final String deleted = """
                               diff --git a/a.txt b/a.txt
                               deleted file mode 100644
                               --- a/a.txt
                               +++ /dev/null
                               @@ -1,11 +0,0 @@
                               -1
                               -2
                               -THREE
                               -4
                               -5
                               -6
                               -7
                               -8
                               -X
                               -9
                               -10
                               """;
        // Deleted: the list of most of its lines kept.
        final Changelists most = kept().withFile(
            "c.txt",
            Assignment.of(
                List.of(new Change("x", 0, 5, 0, 5, "B", List.of()), new Change("y", 9, 1, 9, 1, "A", List.of()))));
        assertEquals(List.of("B"), lists(Anchors.place(most, "c.txt", null, diff(deleted.replace("a.txt", "c.txt")))));
        // Renamed: found under the path it had.
        assertEquals(List.of("A", "B"), lists(Anchors.place(kept(), "d.txt", "a.txt", diff(DIFF))));
        assertEquals(kept().assignment("a.txt"), kept().withFileRenamed("a.txt", "d.txt").assignment("d.txt"));
        // Whole: every change its list's.
        assertEquals(List.of("B", "B"), lists(Anchors.place(kept(), "b.txt", null, diff(DIFF))));
        // Not kept: the active list's.
        assertEquals(List.of("A", "A"), lists(Anchors.place(kept(), "e.txt", null, diff(DIFF))));
    }

    @Test
    public void aFileThatCantBeReadIsMovedAside() throws Exception {
        final Path file = tmp.resolve("j/changelists.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<changelists><list></changelists>");
        assertEquals(Changelists.initial(), new ChangelistStore(file).read());
        assertTrue(Files.exists(file.resolveSibling("changelists.xml.bad")));
        // An entity, as an attack would use, refused as well.
        Files.writeString(
            file,
            "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><changelists>&e;</changelists>");
        assertEquals(Changelists.initial(), new ChangelistStore(file).read());
        assertTrue(Files.exists(file.resolveSibling("changelists.xml.bad.1")), "the first moved aside kept");
        // A list unknown, or the active one missing: the default.
        Files.writeString(file, """
                                <changelists version="1" active="Gone">
                                  <file path="a.txt" list="Gone"/>
                                </changelists>
                                """);
        final Changelists read = new ChangelistStore(file).read();
        assertEquals(Changelists.DEFAULT, read.active());
        assertEquals(Assignment.whole(Changelists.DEFAULT), read.assignment("a.txt"));
    }

    @Test
    public void aFileUnreadableForNowOrALaterJsIsNotWrittenOver() throws Exception {
        final Path file = tmp.resolve("j/changelists.xml");
        Files.createDirectories(file);
        final ChangelistStore now = new ChangelistStore(file);
        assertEquals(Changelists.initial(), now.read());
        assertFalse(now.write(kept()), "can't be read: not written over");
        Files.delete(file);
        final String later = """
                             <changelists version="2" active="Default">
                               <list name="Default"/>
                               <later/>
                             </changelists>
                             """;
        Files.writeString(file, later);
        final ChangelistStore store = new ChangelistStore(file);
        assertEquals(Changelists.DEFAULT, store.read().active(), "what it knows of a later j's read");
        assertFalse(store.write(kept()));
        assertEquals(later, Files.readString(file), "kept as it was");
    }

    @Test
    public void aWriteOverAnotherWritersChangesIsRefused() throws Exception {
        final Path file = tmp.resolve("j/changelists.xml");
        final ChangelistStore one = new ChangelistStore(file);
        final ChangelistStore two = new ChangelistStore(file);
        one.read();
        two.read();
        assertTrue(one.write(kept()));
        assertFalse(two.write(Changelists.initial().withList("Z")), "changed since two read it");
        assertEquals(kept(), two.read());
        assertTrue(two.write(two.read().withList("Z")));
        assertTrue(one.read().list("Z") != null);
    }

    @Test
    public void filesAreKeptInTheOrderGiven() throws Exception {
        final Changelists lists = Changelists.initial()
                .withFile("z.txt", Assignment.whole(Changelists.DEFAULT))
                .withFile("a.txt", Assignment.whole(Changelists.DEFAULT));
        assertEquals(List.of("z.txt", "a.txt"), List.copyOf(lists.files().keySet()));
        final ChangelistStore store = new ChangelistStore(tmp.resolve("j/changelists.xml"));
        assertTrue(store.write(lists));
        final String xml = Files.readString(store.file());
        assertTrue(xml.indexOf("z.txt") < xml.indexOf("a.txt"), xml);
    }

    @Test
    public void aFileKeptWholeIsKeptWholeAgain() {
        final List<Placed> placed = Anchors.place(kept(), "b.txt", null, diff(DIFF));
        assertEquals(Assignment.whole("B"), Anchors.assignment(placed, kept().assignment("b.txt")));
        assertEquals(List.of(), Anchors.place(kept(), "a.txt", null, null), "no diff: no change");
    }

    @Test
    public void aChangeEditedBelowEditsAboveGoesToItsList() {
        // Two lines added at the top, and X edited to Y: its rows in the
        // work tree moved, in the index not.
        final String both = """
                            diff --git a/a.txt b/a.txt
                            --- a/a.txt
                            +++ b/a.txt
                            @@ -0,0 +1,2 @@
                            +new
                            +lines
                            @@ -1,10 +3,11 @@
                             1
                             2
                            -3
                            +THREE
                             4
                             5
                             6
                             7
                             8
                            +Y
                             9
                             10
                            """;
        assertEquals(List.of("A", "A", "B"), lists(Anchors.place(kept(), "a.txt", null, diff(both))));
    }

    @Test
    public void twinsAreFoundInOrderDownTheFile() {
        // X added after lines 2 and 8, the first in A, the second in B.
        final String twins = """
                             diff --git a/a.txt b/a.txt
                             --- a/a.txt
                             +++ b/a.txt
                             @@ -1,10 +1,12 @@
                              1
                              2
                             +X
                              3
                              4
                              5
                              6
                              7
                              8
                             +X
                              9
                              10
                             """;
        final List<Placed> first = Anchors.place(Changelists.initial(), "a.txt", null, diff(twins));
        final List<Change> cs = Anchors.assignment(first, null).changes();
        final Changelists lists = Changelists.initial()
                .withList("A")
                .withList("B")
                .withFile(
                    "a.txt",
                    Assignment.of(List.of(with(cs.get(0), "A", List.of()), with(cs.get(1), "B", List.of()))));
        // Another X added at the top: new, the active list's; the two kept
        // in theirs, in order.
        final String three = """
                             diff --git a/a.txt b/a.txt
                             --- a/a.txt
                             +++ b/a.txt
                             @@ -0,0 +1 @@
                             +X
                             @@ -1,10 +2,12 @@
                              1
                              2
                             +X
                              3
                              4
                              5
                              6
                              7
                              8
                             +X
                              9
                              10
                             """;
        assertEquals(List.of(Changelists.DEFAULT, "A", "B"), lists(Anchors.place(lists, "a.txt", null, diff(three))));
    }

    @Test
    public void aListRenamedOrDeletedTakesItsChangesWithIt() {
        final Changelists renamed = kept().renamed("B", "C");
        assertEquals("C", renamed.assignment("b.txt").whole());
        assertEquals("C", renamed.assignment("a.txt").changes().get(0).parts().get(0).list());
        final Changelists gone = kept().without("A");
        assertEquals(Changelists.DEFAULT, gone.active());
        assertEquals(Changelists.DEFAULT, gone.assignment("a.txt").changes().get(0).list());
        assertThrows(IllegalArgumentException.class, () -> kept().without(Changelists.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> kept().renamed(Changelists.DEFAULT, "X"));
        assertThrows(IllegalArgumentException.class, () -> kept().renamed("A", "B"));
    }

    @Test
    public void eachWorktreeKeepsItsOwn() throws Exception {
        final GitFixture git = GitFixture.init(Files.createDirectories(tmp.resolve("main")));
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.git("worktree", "add", "-q", "-b", "other", tmp.resolve("other").toString());
        final VcsRepository main = Repositories.forDirectory(git.root);
        final VcsRepository other = Repositories.forDirectory(tmp.resolve("other").toRealPath());
        final ChangelistStore mainStore = ChangelistStore.of(main);
        final ChangelistStore otherStore = ChangelistStore.of(other);
        assertNotEquals(mainStore.file(), otherStore.file());
        assertTrue(mainStore.write(kept()));
        assertEquals(kept(), mainStore.read());
        assertEquals(Changelists.initial(), otherStore.read(), "the other worktree's lists its own");
        assertEquals(git.root.resolve(".git/j/changelists.xml"), mainStore.file());
    }
}
