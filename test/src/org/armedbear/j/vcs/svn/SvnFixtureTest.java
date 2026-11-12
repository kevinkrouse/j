/*
 * SvnFixtureTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.svn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.armedbear.j.vcs.VcsCommand;
import org.armedbear.j.vcs.VcsResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

public class SvnFixtureTest {
    @TempDir
    Path tmp;

    @AfterEach
    void reset() {
        SvnFixture.reset();
    }

    @Test
    public void commitsAFileAndReadsInfoThroughSvnCli() throws Exception {
        final SvnFixture f = SvnFixture.init(tmp);
        f.write("a.txt", "one\n");
        f.add("a.txt");
        assertEquals(1, f.commit("first"));

        final VcsCommand info = SvnCli.read(f.root, "info", "--xml", "--", "a.txt");
        assertEquals(
            List.of("--non-interactive", "--config-dir", tmp.toRealPath().resolve("config").toString()),
            info.options());
        final VcsResult r = SvnCli.run(info);
        assertTrue(r.succeeded(), r.stderr());
        final Element entry = SvnCli.children(SvnCli.xml(r.stdout()).getDocumentElement(), "entry").get(0);
        assertEquals("file", entry.getAttribute("kind"));
        assertEquals("1", entry.getAttribute("revision"));
        assertEquals(f.url + "/a.txt", SvnCli.childText(entry, "url"));
        final Element commit = SvnCli.children(entry, "commit").get(0);
        assertEquals(SvnFixture.USER, SvnCli.childText(commit, "author"));
    }

    @Test
    public void aPathWithAnAtAndADatedCommit() throws Exception {
        final SvnFixture f = SvnFixture.init(tmp);
        f.write("icon@2x.png", "x\n");
        f.add("icon@2x.png");
        final int r = f.commitAt("dated", Instant.parse("2026-01-02T03:04:05Z"), "icon@2x.png");
        final String log = f.svn("log", "--xml", "-r", String.valueOf(r), "--", SvnCli.target("icon@2x.png"));
        final Element entry = SvnCli.children(SvnCli.xml(log.getBytes()).getDocumentElement(), "logentry").get(0);
        assertEquals("2026-01-02T03:04:05.000000Z", SvnCli.childText(entry, "date"));
    }

    @Test
    public void aSecondWorkingCopySeesTheFirstsCommits() throws Exception {
        final SvnFixture f = SvnFixture.init(tmp);
        f.write("a.txt", "one\n");
        f.add("a.txt");
        f.commit("first");
        final SvnFixture g = f.second();
        assertEquals("one\n", g.read("a.txt"));
        assertEquals(tmp.toRealPath().resolve("wc2"), g.root);
    }
}
