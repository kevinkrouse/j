/*
 * FindFileTextFieldHandlerTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.armedbear.j.mode.text.PlainTextMode;
import org.armedbear.j.util.FuzzyMatcher.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FindFileTextFieldHandlerTest {
    @TempDir
    Path tmp;

    private EditorHarness h;
    private HistoryTextField field;
    private FindFileTextFieldHandler finder;

    @AfterEach
    public void tearDown() {
        if (finder != null)
            finder.detached();
        if (h != null)
            h.close();
    }

    private void touch(String... paths) throws Exception {
        for (String p : paths) {
            Files.createDirectories(tmp.resolve(p).getParent());
            Files.writeString(tmp.resolve(p), "");
        }
    }

    // The finder on a buffer of proj/src/a/Main.java, proj its project.
    private void finder() throws Exception {
        touch(
            "proj/src/a/Main.java",
            "proj/src/a/Alpha.java",
            "proj/src/b/Alpha.java",
            "proj/src/a/build/Gen.java",
            "other/Outside.txt");
        final File root = File.getInstance(tmp.resolve("proj").toString());
        final ProjectFiles pf = ProjectFiles.forRoot(root);
        pf.rescan();
        ProjectFiles.awaitScanner();
        h = EditorHarness.create();
        final Buffer buffer = new Buffer(File.getInstance(tmp.resolve("proj/src/a/Main.java").toString()));
        buffer.setMode(PlainTextMode.getMode());
        buffer.setProperty(Property.PROJECT_ROOT, root.canonicalPath());
        h.editor().setBufferDirectly(buffer);
        field = new HistoryTextField(h.editor(), 20);
        finder = new FindFileTextFieldHandler(h.editor(), field);
        field.setHandler(finder);
        finder.start();
    }

    // What text lists, best first, by the text each matches.
    private List<String> listed(String text) {
        field.setText(text);
        final String query = finder.queryText(text);
        final List<FinderItem> items = query.isEmpty() ? finder.emptyQueryItems() : finder.candidates();
        final List<String> texts = new ArrayList<>();
        for (FinderItem.Row row : FinderTextFieldHandler.rank(items, Query.parse(query)))
            texts.add(row.item().matchText());
        return texts;
    }

    @Test
    public void aNameFindsTheBuffersDirectorysFilesFirst() throws Exception {
        finder();
        assertEquals(List.of("src/a/Alpha.java", "src/b/Alpha.java"), listed("Alpha"));
    }

    @Test
    public void nothingTypedListsTheBuffersAndRecentFilesNotTheProjects() throws Exception {
        finder();
        assertFalse(listed("").contains("src/b/Alpha.java"));
    }

    @Test
    public void aRelativePathListsWhatTheProjectLeavesOut() throws Exception {
        finder();
        // build is one of the directories the project's list skips.
        assertEquals(List.of("build/Gen.java"), listed("build/Gen"));
        assertTrue(listed("bu").contains("build/"), "a directory under the buffer's is listed");
    }

    @Test
    public void aFileTheProjectsListLacksIsListedBesideOnesItHas() throws Exception {
        finder();
        // As one past finderMaxFiles, or made since the scan.
        touch("proj/src/a/Late.java");
        assertEquals(List.of("Late.java"), listed("Late"));
        assertEquals(List.of("src/a/Alpha.java", "src/b/Alpha.java"), listed("Alpha"), "and Alpha.java once");
    }

    @Test
    public void anAbsolutePathListsThatDirectoryAlone() throws Exception {
        finder();
        final String other = tmp.resolve("other").toRealPath() + "/";
        assertEquals(List.of(other + "Outside.txt"), listed(other + "Out"));
        assertEquals(other, listed(other).get(0), "the directory itself first");
    }

    @Test
    public void dotSlashListsTheBuffersDirectoryAlone() throws Exception {
        finder();
        final List<String> listed = listed("./");
        assertEquals("./", listed.get(0), "the directory itself first");
        assertEquals(Set.of("./", "./Alpha.java", "./build/", "./Main.java"), Set.copyOf(listed));
        assertEquals(List.of("../b/Alpha.java"), listed("../b/Al"));
    }

    @Test
    public void aUrlListsNothing() throws Exception {
        finder();
        assertEquals(List.of(), listed("https://example.com/Alpha"));
    }
}
