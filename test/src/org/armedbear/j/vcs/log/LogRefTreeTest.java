/*
 * LogRefTreeTest.java
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

package org.armedbear.j.vcs.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.Test;

/** The refs tree's shape, apart from the sidebar. */
public class LogRefTreeTest {
    private static LogRefTip tip(String name, LogRef.Kind kind) {
        return new LogRefTip(new LogRef(name, kind, false), "id-" + name, 0, 0, 0, false);
    }

    // The tree as indented lines, two spaces a level.
    private static List<String> lines(DefaultMutableTreeNode node) {
        final List<String> out = new ArrayList<>();
        lines(node, -1, out);
        return out;
    }

    private static void lines(DefaultMutableTreeNode node, int depth, List<String> out) {
        if (depth >= 0)
            out.add("  ".repeat(depth) + node.getUserObject());
        for (int i = 0; i < node.getChildCount(); i++)
            lines((DefaultMutableTreeNode) node.getChildAt(i), depth + 1, out);
    }

    private static final List<LogRefTip> TIPS = List.of(
        tip("HEAD", LogRef.Kind.HEAD),
        tip("main", LogRef.Kind.BRANCH),
        tip("feature/b", LogRef.Kind.BRANCH),
        tip("feature/a", LogRef.Kind.BRANCH),
        tip("origin/main", LogRef.Kind.REMOTE),
        tip("upstream/feature/x", LogRef.Kind.REMOTE),
        tip("v2", LogRef.Kind.TAG),
        tip("v1", LogRef.Kind.TAG));

    @Test
    public void headThenBranchesInFoldersThenRemotesByRemoteThenTagsAsRead() {
        assertEquals(
            List.of(
                "HEAD",
                "Branches",
                "  feature",
                "    a",
                "    b",
                "  main",
                "Remotes",
                "  origin",
                "    main",
                "  upstream",
                "    feature",
                "      x",
                "Tags",
                "  v2",
                "  v1"),
            lines(LogRefTree.build(List.of(new LogRefTree.Part(null, "j", TIPS)), "", false, false)));
    }

    @Test
    public void typingNarrowsToTheNamesWithItIgnoringCase() {
        assertEquals(
            List.of("Branches", "  main", "Remotes", "  origin", "    main"),
            lines(LogRefTree.build(List.of(new LogRefTree.Part(null, "j", TIPS)), "MAI", false, false)));
        assertEquals(
            List.of(),
            lines(LogRefTree.build(List.of(new LogRefTree.Part(null, "j", TIPS)), "zzz", false, false)));
    }

    @Test
    public void theNewestTagsAndTheRestAsANodeUnlessAllAreAskedForOrItIsNarrowed() {
        final List<LogRefTip> tags = new ArrayList<>();
        for (int i = LogRefTree.TAGS + 5; i > 0; i--)
            tags.add(tip("t" + i, LogRef.Kind.TAG));
        final List<LogRefTree.Part> parts = List.of(new LogRefTree.Part(null, "j", tags));
        final List<String> some = lines(LogRefTree.build(parts, "", false, false));
        assertEquals("  t" + (LogRefTree.TAGS + 5), some.get(1), "newest first");
        assertEquals("  … 5 more", some.get(some.size() - 1));
        assertEquals(LogRefTree.TAGS + 2, some.size());
        assertEquals(LogRefTree.TAGS + 6, lines(LogRefTree.build(parts, "", true, false)).size(), "all");
        // Every tag with "t1" in it, narrowed, with no "more".
        final long ones = tags.stream().filter(t -> t.ref().name().contains("t1")).count();
        assertEquals(1 + ones, lines(LogRefTree.build(parts, "t1", false, false)).size(), "narrowed: all");
    }

    @Test
    public void severalRepositoriesEachUnderItsNameNoneWithNothingLeft() {
        final List<LogRefTree.Part> parts = List.of(
            new LogRefTree.Part(null, "a", List.of(tip("main", LogRef.Kind.BRANCH))),
            new LogRefTree.Part(null, "b", List.of(tip("dev", LogRef.Kind.BRANCH))));
        assertEquals(
            List.of("a", "  Branches", "    main", "b", "  Branches", "    dev"),
            lines(LogRefTree.build(parts, "", false, false)));
        assertEquals(List.of("b", "  Branches", "    dev"), lines(LogRefTree.build(parts, "dev", false, false)));
    }

    @Test
    public void byDateTheNewestBranchFirstAFolderWhereItsNewestIs() {
        final List<LogRefTip> tips = List.of(
            new LogRefTip(new LogRef("main", LogRef.Kind.BRANCH, false), "m", 100, 0, 0, false),
            new LogRefTip(new LogRef("feature/a", LogRef.Kind.BRANCH, false), "a", 300, 0, 0, false),
            new LogRefTip(new LogRef("feature/b", LogRef.Kind.BRANCH, false), "b", 50, 0, 0, false),
            new LogRefTip(new LogRef("zed", LogRef.Kind.BRANCH, false), "z", 200, 0, 0, false));
        assertEquals(
            List.of("Branches", "  feature", "    a", "    b", "  zed", "  main"),
            lines(LogRefTree.build(List.of(new LogRefTree.Part(null, "j", tips)), "", false, true)));
    }

    @Test
    public void theDefaultBranchThenTheOneCheckedOutFirstByTheirWholeNames() {
        final List<LogRefTip> tips = List.of(
            new LogRefTip(new LogRef("alpha", LogRef.Kind.BRANCH, false), "a", 300, 0, 0, false),
            new LogRefTip(new LogRef("release/2", LogRef.Kind.BRANCH, true), "r", 100, 0, 0, false),
            new LogRefTip(new LogRef("release/1", LogRef.Kind.BRANCH, false), "s", 50, 0, 0, false),
            new LogRefTip(new LogRef("main", LogRef.Kind.BRANCH, false), "m", 10, 0, 0, false, true));
        final List<LogRefTree.Part> parts = List.of(new LogRefTree.Part(null, "j", tips));
        final List<String> pinned = List.of("Branches", "  main", "  release/2");
        assertEquals(
            List.of("Branches", "  main", "  release/2", "  alpha", "  release", "    1"),
            lines(LogRefTree.build(parts, "", false, false)));
        assertEquals(pinned, lines(LogRefTree.build(parts, "", false, true)).subList(0, 3), "by date too");
        assertEquals(
            List.of("Branches", "  release/2", "  release", "    1"),
            lines(LogRefTree.build(parts, "rel", false, false)),
            "narrowed");
    }

    @Test
    public void byNameIgnoringCase() {
        final List<LogRefTip> tips = List
                .of(tip("Zed", LogRef.Kind.BRANCH), tip("alpha", LogRef.Kind.BRANCH), tip("JIRA", LogRef.Kind.BRANCH));
        assertEquals(
            List.of("Branches", "  alpha", "  JIRA", "  Zed"),
            lines(LogRefTree.build(List.of(new LogRefTree.Part(null, "j", tips)), "", false, false)));
    }
}
