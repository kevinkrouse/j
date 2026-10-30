/*
 * VcsRunnerTest.java
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.armedbear.j.vcs.CommandLog;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsCommand;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.VcsRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Running git: output, errors, the command log, and the watcher. */
public class VcsRunnerTest {
    @TempDir
    Path tmp;

    @AfterEach
    public void resetGit() {
        GitFixture.reset();
    }

    @Test
    public void gitNeverAsksForCredentials() throws IOException {
        // Else a fetch from a remote wanting a password waits on a terminal it hasn't.
        final GitFixture f = GitFixture.init(tmp);
        final String[] said = { "-c", "alias.prompt=!printf %s \"$GIT_TERMINAL_PROMPT\"", "prompt" };
        assertEquals("0", GitCli.run(GitCli.read(f.root, said)).out());
        assertEquals("0", GitCli.run(GitCli.write(f.root, null, said)).out());
    }

    @Test
    public void keepsOutputAndErrorApart() throws IOException {
        GitFixture f = GitFixture.init(tmp);
        VcsResult ok = GitCli.run(GitCli.read(f.root, "rev-parse", "--is-inside-work-tree"));
        assertEquals("true\n", ok.out());
        assertEquals("", ok.stderr());
        VcsResult bad = GitCli.run(GitCli.read(f.root, "rev-parse", "--verify", "no-such-ref"));
        assertFalse(bad.succeeded());
        assertTrue(bad.stderr().contains("fatal"), bad.stderr());
        assertEquals(0, bad.stdout().length);
    }

    @Test
    public void reportsAProgramThatWillNotStart() {
        VcsResult r = VcsRunner
                .run(new VcsCommand(tmp, List.of("no-such-program-j-vcs"), List.of(), Map.of(), null, false, null));
        assertEquals(-1, r.exitCode());
        assertFalse(r.stderr().isEmpty());
    }

    @Test
    public void stopsAProgramThatRunsTooLong() {
        VcsResult r = VcsRunner.run(
            new VcsCommand(tmp, List.of("sleep", "10"), List.of(), Map.of(), null, false, Duration.ofMillis(200)));
        assertEquals(-1, r.exitCode());
        assertTrue(r.stderr().contains("timed out"), r.stderr());
        assertTrue(r.millis() < 5000);
    }

    @Test
    public void logsEachCommand() throws IOException {
        GitFixture f = GitFixture.init(tmp);
        GitCli.run(GitCli.read(f.root, "status", "--porcelain=v2"));
        List<CommandLog.Entry> entries = CommandLog.entries();
        CommandLog.Entry last = entries.get(entries.size() - 1);
        // Without the options every git command runs with, said once above it.
        assertEquals("git status --porcelain=v2", last.commandLine());
        assertTrue(last.options().startsWith("-c color.ui=false"), last.options());
        final String text = CommandLog.format(List.of(last, last));
        assertTrue(text.startsWith("[git runs with -c color.ui=false"), text);
        assertEquals(1, text.split("runs with", -1).length - 1, text);
        assertEquals(f.root.toString(), last.directory());
        assertFalse(last.failed());
    }

    @Test
    public void quotesArgumentsAsAShellWould() {
        VcsCommand c = new VcsCommand(tmp,
                List.of("git", "commit", "-m", "it's done", "a b", ""),
                List.of(),
                Map.of(),
                null,
                true,
                null);
        assertEquals("git commit -m 'it'\\''s done' 'a b' ''", c.commandLine());
    }

    @Test
    public void refreshesARepositoryChangedOutsideJ() throws Exception {
        GitFixture f = GitFixture.init(tmp);
        f.write("a.txt", "a\n");
        f.commitAll("one");
        assertRefreshesWhen(f, () -> {
            f.write("a.txt", "b\n");
            // The index changes; the working tree alone is not watched.
            f.git("add", "a.txt");
        }, r -> r.snapshot().change("a.txt") != null);
    }

    @Test
    public void refreshesAReftableRepository() throws Exception {
        assumeTrue(GitCli.versionAtLeast(new int[] { 2, 45 }), "reftable needs git 2.45");
        GitFixture f = GitFixture.init(tmp);
        f.git("init", "-q", "--ref-format=reftable", "rt");
        GitFixture rt = GitFixture.at(f.root.resolve("rt"));
        rt.write("a.txt", "a\n");
        rt.commitAll("one");
        rt.commitAll("two");
        String head = ((GitSnapshot) Repositories.forDirectory(rt.root).snapshotNow()).head();
        assertRefreshesWhen(
            rt,
            () -> rt.git("reset", "-q", "--soft", "HEAD~1"),
            r -> !head.equals(((GitSnapshot) r.snapshot()).head()));
    }

    private interface Change {
        void run() throws Exception;
    }

    private static void assertRefreshesWhen(
            GitFixture f,
            Change change,
            java.util.function.Predicate<VcsRepository> done) throws Exception {
        VcsRepository repo = Repositories.forDirectory(f.root);
        repo.snapshotNow();
        CountDownLatch refreshed = new CountDownLatch(1);
        Consumer<VcsRepository> listener = r -> {
            if (done.test(r))
                refreshed.countDown();
        };
        repo.addListener(listener);
        try {
            change.run();
            assertTrue(refreshed.await(10, TimeUnit.SECONDS), "no refresh after the change");
            assertFalse(repo.isStale());
        }
        finally {
            repo.removeListener(listener);
        }
    }
}
