/*
 * SvnIgnoresTest.java
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.armedbear.j.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SvnIgnoresTest {
    private static final Path ROOT = Path.of("/wc");

    // propget -R --xml output for one property.
    private static String xml(String... pathsAndValues) {
        final StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<properties>\n");
        for (int i = 0; i < pathsAndValues.length; i += 2)
            sb.append("<target path=\"")
                    .append(pathsAndValues[i])
                    .append("\">\n<property name=\"p\">")
                    .append(pathsAndValues[i + 1])
                    .append("</property>\n</target>\n");
        return sb.append("</properties>\n").toString();
    }

    @Test
    public void svnIgnoreAppliesToItsDirectorysOwnEntries() {
        final SvnIgnores ignores = new SvnIgnores(ROOT, "", xml("/wc", "build\n*.log\n", "/wc/sub", "out"), xml());
        assertTrue(ignores.ignored(Path.of("/wc/build"), true));
        assertTrue(ignores.ignored(Path.of("/wc/a.log"), false));
        assertFalse(ignores.ignored(Path.of("/wc/sub/a.log"), false), "not its subdirectories'");
        assertTrue(ignores.ignored(Path.of("/wc/sub/out"), true));
        assertFalse(ignores.ignored(Path.of("/wc/out"), true));
    }

    @Test
    public void globalIgnoresApplyUnderTheirDirectory() {
        final SvnIgnores ignores = new SvnIgnores(ROOT, "", xml(), xml("/wc/sub", "*.tmp"));
        assertTrue(ignores.ignored(Path.of("/wc/sub/a.tmp"), false));
        assertTrue(ignores.ignored(Path.of("/wc/sub/deep/b.tmp"), false));
        assertFalse(ignores.ignored(Path.of("/wc/c.tmp"), false));
    }

    @Test
    public void theConfigsGlobalIgnoresOrSvnsOwn() {
        final SvnIgnores own = new SvnIgnores(ROOT, null, xml(), xml());
        assertTrue(own.ignored(Path.of("/wc/x.o"), false));
        assertTrue(own.ignored(Path.of("/wc/sub/notes~"), false));
        assertTrue(own.ignored(Path.of("/wc/lib.so.1"), false));
        assertFalse(own.ignored(Path.of("/wc/x.c"), false));
        final SvnIgnores configured = new SvnIgnores(ROOT, "*.c", xml(), xml());
        assertTrue(configured.ignored(Path.of("/wc/x.c"), false));
        assertFalse(configured.ignored(Path.of("/wc/x.o"), false));
    }

    @Test
    public void theConfigsValueGoesOnOverIndentedLines() {
        assertEquals(
            "*.o *.class *~",
            SvnIgnores.configGlobalIgnores(
                List.of(
                    "[auth]",
                    "global-ignores = no",
                    "[miscellany]",
                    "# global-ignores = commented",
                    "global-ignores = *.o",
                    "   *.class *~",
                    "enable-auto-props = yes")));
        assertNull(SvnIgnores.configGlobalIgnores(List.of("[miscellany]", "# global-ignores = *.o")));
    }

    @Test
    public void aGlobThatIsntOneIsLeftOut() {
        assertEquals(1, SvnIgnores.patterns("*.[!] *.o").size());
        final SvnIgnores ignores = new SvnIgnores(ROOT, "*.[!] *.o", xml(), xml());
        assertTrue(ignores.ignored(Path.of("/wc/x.o"), false));
    }

    @Test
    public void whatIsInAnIgnoredDirectoryIsIgnored() {
        final SvnIgnores ignores = new SvnIgnores(ROOT, "", xml("/wc", "build"), xml());
        assertTrue(ignores.ignored(Path.of("/wc/build/x/y.c"), false));
        assertFalse(ignores.ignored(Path.of("/wc/src/y.c"), false));
    }

    @Test
    public void rulesStayUntilAWhileAfterTheyWereRead() {
        final SvnIgnores ignores = new SvnIgnores(ROOT, "", xml(), xml());
        final long now = System.currentTimeMillis();
        assertTrue(ignores.fresh(now, 10_000));
        assertFalse(ignores.fresh(now + 10_001, 10_000), "read again after that");
    }

    @Test
    public void onlyTheTopOfAnOldWorkingCopyIsItsRoot(@TempDir Path tmp) throws Exception {
        // Before svn 1.7, every directory has a .svn.
        Files.createDirectories(tmp.resolve("wc/.svn"));
        Files.createDirectories(tmp.resolve("wc/sub/.svn"));
        Files.createDirectories(tmp.resolve("new/.svn"));
        Files.createDirectories(tmp.resolve("new/sub"));
        final SvnBackend svn = new SvnBackend();
        assertTrue(svn.isRoot(File.getInstance(tmp.resolve("wc").toString())));
        assertFalse(svn.isRoot(File.getInstance(tmp.resolve("wc/sub").toString())));
        assertTrue(svn.isRoot(File.getInstance(tmp.resolve("new").toString())));
        assertFalse(svn.isRoot(File.getInstance(tmp.resolve("new/sub").toString())));
    }
}
