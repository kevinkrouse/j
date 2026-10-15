/*
 * GitSnapshotTest.java
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

package org.armedbear.j.vcs.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.armedbear.j.File;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VersionControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitSnapshotTest {
    @TempDir
    Path tmp;

    @AfterEach
    public void resetGit() {
        GitFixture.reset();
    }

    private static GitSnapshot parse(String output) {
        return GitStatusParser.parse(output.getBytes(StandardCharsets.UTF_8), GitSnapshot.Operation.NONE, true);
    }

    @Test
    public void readsHeaders() {
        GitSnapshot s = parse(
            "# branch.oid abc123\0# branch.head main\0# branch.upstream origin/main\0"
                    + "# branch.ab +2 -3\0# stash 4\0");
        assertEquals("abc123", s.head());
        assertEquals("main", s.branch());
        assertEquals("origin/main", s.upstream());
        assertEquals(2, s.ahead());
        assertEquals(3, s.behind());
        assertEquals(4, s.stashes());
        assertTrue(s.changes().isEmpty());
    }

    @Test
    public void countsNoStashesOnlyIfGitWouldSay() {
        assertEquals(0, parse("# branch.head main\0").stashes());
        GitSnapshot old = GitStatusParser
                .parse("# branch.head main\0".getBytes(StandardCharsets.UTF_8), GitSnapshot.Operation.NONE, false);
        assertEquals(-1, old.stashes());
    }

    @Test
    public void readsAnEmptyRepositoryAndADetachedHead() {
        GitSnapshot s = parse("# branch.oid (initial)\0# branch.head (detached)\0");
        assertNull(s.head());
        assertNull(s.branch());
        assertTrue(s.isDetached());
    }

    @Test
    public void readsEntries() {
        GitSnapshot s = parse(
            "1 M. N... 100644 100644 100644 aaa bbb staged.txt\0"
                    + "1 .M N... 100644 100644 100644 aaa aaa some dir/a file.txt\0"
                    + "2 R. N... 100644 100644 100644 aaa aaa R100 new name.txt\0old name.txt\0"
                    + "u UU N... 100644 100644 100644 100644 a b c both.txt\0" + "? untracked.txt\0" + "! build/\0"
                    + "1 .A N... 000000 000000 100644 0 0 intent.txt\0");
        assertEquals(7, s.changes().size());
        FileChange staged = s.change("staged.txt");
        assertEquals("M ", staged.code());
        assertEquals(Kind.MODIFIED, staged.staged());
        assertNull(staged.unstaged());
        assertEquals(" M", s.change("some dir/a file.txt").code());
        FileChange renamed = s.change("new name.txt");
        assertEquals("old name.txt", renamed.origPath());
        assertEquals(Kind.RENAMED, renamed.staged());
        assertTrue(s.change("both.txt").isConflicted());
        assertEquals("UU", s.change("both.txt").code());
        assertTrue(s.change("untracked.txt").isUntracked());
        assertTrue(s.change("build/").isIgnored());
        assertEquals(Kind.ADDED, s.change("intent.txt").unstaged());
        // The source of a rename is not an entry of its own.
        assertNull(s.change("old name.txt"));
    }

    @Test
    public void toleratesNothingAndTruncation() {
        assertTrue(parse("").changes().isEmpty());
        // A rename cut off before its source.
        GitSnapshot s = parse("2 R. N... 100644 100644 100644 aaa aaa R100 new.txt");
        assertNull(s.change("new.txt").origPath());
    }

    @Test
    public void readsARealRepository() throws IOException {
        GitFixture f = GitFixture.init(tmp);
        f.write("a.txt", "a\n");
        f.write("b.txt", "b\n");
        f.commitAll("one");
        f.write("a.txt", "A\n");
        f.git("add", "a.txt");
        f.write("b.txt", "B\n");
        f.write("new.txt", "n\n");
        f.git("stash", "push", "-q", "--keep-index", "--", "b.txt");
        f.write("b.txt", "B again\n");
        VcsRepository repo = Repositories.forDirectory(f.root);
        assertNotNull(repo);
        assertTrue(repo.backend() instanceof GitBackend);
        GitSnapshot s = (GitSnapshot) repo.snapshotNow();
        assertNull(s.error(), s.error());
        assertEquals("main", s.branch());
        assertNotNull(s.head());
        assertEquals(1, s.stashes());
        assertEquals("M ", s.change("a.txt").code());
        assertEquals(" M", s.change("b.txt").code());
        assertEquals("??", s.change("new.txt").code());
        assertEquals(GitSnapshot.Operation.NONE, s.operation());
    }

    @Test
    public void seesAMergeInProgress() throws IOException {
        GitFixture f = GitFixture.init(tmp);
        f.write("c.txt", "base\n");
        f.commitAll("base");
        f.git("checkout", "-q", "-b", "other");
        f.write("c.txt", "other\n");
        f.commitAll("other");
        f.git("checkout", "-q", "main");
        f.write("c.txt", "main\n");
        f.commitAll("main");
        assertFalse(f.run("merge", "-q", "other").succeeded());
        GitSnapshot s = (GitSnapshot) Repositories.forDirectory(f.root).snapshotNow();
        assertEquals(GitSnapshot.Operation.MERGE, s.operation());
        assertTrue(s.change("c.txt").isConflicted());
    }

    @Test
    public void statusCacheAnswersStaleThenFresh() throws Exception {
        GitFixture f = GitFixture.init(tmp);
        f.write("a.txt", "a\n");
        f.write(".gitignore", "build/\n");
        f.commitAll("one");
        f.write("build/out.class", "x");
        File a = File.getInstance(f.root.resolve("a.txt").toString());
        assertNull(GitStatusCache.statusFor(a));
        // A file in an ignored directory is ignored, though git lists only the directory.
        assertEquals("!!", GitStatusCache.statusFor(File.getInstance(f.root.resolve("build/out.class").toString())));
        f.write("a.txt", "changed\n");
        // After a save: the stale answer at once, the fresh one once read in the background.
        VersionControl.invalidate();
        assertNull(GitStatusCache.statusFor(a));
        VcsRepository repo = Repositories.forDirectory(f.root);
        for (int i = 0; i < 100 && repo.isStale(); i++)
            Thread.sleep(50);
        assertEquals(" M", GitStatusCache.statusFor(a));
    }

    @Test
    public void readsThroughTheUsersColorSettings() throws IOException {
        GitFixture f = GitFixture.init(tmp);
        f.write("a.txt", "a\n");
        f.commitAll("one");
        f.git("config", "color.diff", "always");
        f.git("config", "color.status", "always");
        f.write("a.txt", "b\n");
        assertEquals(1, f.unstaged().size());
        assertEquals("a.txt", f.unstaged().get(0).path());
        Snapshot s = Repositories.forDirectory(f.root).snapshotNow();
        assertEquals(" M", s.change("a.txt").code());
    }

    @Test
    public void snapshotOfABrokenRepositorySaysWhy() throws IOException {
        GitFixture f = GitFixture.init(tmp);
        Files.writeString(f.root.resolve(".git/HEAD"), "garbage\n");
        Snapshot s = Repositories.forDirectory(f.root).snapshotNow();
        assertNotNull(s.error());
        assertTrue(s.changes().isEmpty());
    }
}
