/*
 * VimConformanceTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

/**
 * How much of vim j actually emulates.
 *
 * Replays the corpus generated from CodeMirror's vim_test.js by
 * tools/vim-conformance.clj and reports how many cases pass. That number is
 * the project's fidelity metric.
 *
 * It is a ratchet rather than a target. {@code passing.txt} lists the cases a
 * milestone has made pass; if one of those stops passing the build fails.
 * Cases outside the list are free to fail -- the modal engine is being built
 * incrementally -- but a case that starts passing is reported so it can be
 * added to the list.
 */
public class VimConformanceTest
{
    @Test
    public void corpusIsPresentAndWellFormed() throws Exception
    {
        final List<VimConformance.Case> cases = corpus();
        assertFalse("the generated corpus is empty; run "
                    + "'bb tools/vim-conformance.clj <vim_test.js>'",
                    cases.isEmpty());
        for (VimConformance.Case c : cases) {
            assertFalse("a case with no steps: " + c.name, c.steps.isEmpty());
            boolean asserts = false;
            for (VimConformance.Step s : c.steps)
                if (s.directive.startsWith("expect-"))
                    asserts = true;
            assertTrue("a case that asserts nothing: " + c.name, asserts);
        }
    }

    @Test
    public void noPreviouslyPassingCaseRegresses() throws Exception
    {
        final List<VimConformance.Case> cases = corpus();
        final Set<String> expected =
            VimConformance.loadExpectedPassing(
                VimConformance.corpusDir().resolve("passing.txt"));

        final Set<String> passing = new TreeSet<String>();
        final Map<String, String> failures = new LinkedHashMap<String, String>();

        for (VimConformance.Case c : cases) {
            try {
                VimConformance.run(c);
                passing.add(c.name);
            }
            catch (Throwable t) {
                failures.put(c.name, String.valueOf(t.getMessage()));
            }
        }

        report(cases.size(), passing, expected);

        final List<String> regressed = new ArrayList<String>();
        for (String name : expected)
            if (!passing.contains(name))
                regressed.add(name + "  --  " + failures.get(name));

        assertTrue("cases that used to pass and no longer do:\n  "
                   + String.join("\n  ", regressed),
                   regressed.isEmpty());
    }

    private static void report(int total, Set<String> passing, Set<String> expected)
    {
        System.out.println();
        System.out.println("vim conformance: " + passing.size() + "/" + total
                           + " cases passing (" + expected.size()
                           + " expected by passing.txt)");

        final Set<String> unlisted = new TreeSet<String>(passing);
        unlisted.removeAll(expected);
        if (!unlisted.isEmpty()) {
            System.out.println("  " + unlisted.size()
                               + " case(s) now pass but are not yet listed in "
                               + VimConformance.CORPUS_DIR + "/passing.txt:");
            for (String name : unlisted)
                System.out.println("    " + name);
        }
        System.out.println();
    }

    private static List<VimConformance.Case> corpus() throws Exception
    {
        final Path file = VimConformance.corpusDir().resolve("codemirror.conf");
        assertTrue("no corpus at " + file.toAbsolutePath(), Files.exists(file));
        return VimConformance.load(file);
    }
}
