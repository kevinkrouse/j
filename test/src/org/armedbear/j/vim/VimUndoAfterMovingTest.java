/*
 * VimUndoAfterMovingTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.ArrayList;
import java.util.List;

import org.armedbear.j.EditorHarness;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Every editing command, then motions away from it, then u: the text has to
 * come back exactly, and CTRL-R from somewhere else again has to redo it.
 *
 * j's undo records used to find the lines they restore from wherever the
 * caret was when undo ran, and vim motions are not undo steps, so this went
 * wrong for nearly every edit: x, j, u put the old line back over the one
 * below. They now find them by line number.
 */
public class VimUndoAfterMovingTest
{
    private static final String TEXT =
        "first line here\n" +
        "  (alpha) beta 42\n" +
        "gamma {delta} \"eps\"\n" +
        "  zeta eta 7 theta\n" +
        "iota kappa\n" +
        "\n" +
        "lambda mu nu\n" +
        "last one";

    private static final String[] EDITS = {
        "rq", "3rq", "x", "3x", "X", "D", "dd", "3dd", "dw", "d2w", "dj",
        "dk", "dG", "dgg", "d$", "d0", "di(", "da(", "di{", "di\"", "dap",
        "diw", "daw", "J", "3J", "gJ", "~", "3~", "<C-a>", "5<C-x>",
        "guu", "gUU", "g~w", "gUiw", ">>", "<<", "3>>", ">j", ">ap",
        "yyp", "yyP", "ywp", "ywP", "y2jp", "y2jP", "ddP",
        "iZZ<Esc>", "aZZ<Esc>", "IZZ<Esc>", "AZZ<Esc>", "oZZ<Esc>",
        "OZZ<Esc>",         "RZZZ<Esc>", "cwZZ<Esc>", "ccZZ<Esc>", "CZZ<Esc>",
        "sZZ<Esc>", "SZZ<Esc>", "ci(ZZ<Esc>", "c2jZZ<Esc>",
        "iZZ<CR>YY<Esc>", "oZZ<CR>YY<Esc>", "A<CR><Esc>", "i<BS><BS><Esc>",
        "A<C-w><Esc>", "A<C-u><Esc>", "i<C-t><Esc>", "i<C-d><Esc>",
        "yiwA<C-r>\"<Esc>",
        "vlld", "vjd", "Vd", "Vjd", "<C-v>jld", "vjrq", "Vjrq",
        "<C-v>jlrq", "vjJ", "VjJ", "vjU", "Vj~", "Vj>", "Vj<",
        "<C-v>jIZZ<Esc>", "<C-v>jAZZ<Esc>", "<C-v>j$AZZ<Esc>",
        "vjcZZ<Esc>", "VjcZZ<Esc>", "<C-v>jlcZZ<Esc>", "VjD", "vjX",
        "vjC ZZ<Esc>", "<C-v>jD", "yiwvep", "yyVjp", "yiwVjp",
        "yy<C-v>jlp", "Vj<C-a>", "Vjg<C-a>",
        // Two changes, so two undos.
        "2u:x.", "2u:dd.", "2u:rq.", "2u:AZZ<Esc>.", "2u:Vj>.", "2u:xp",
        "2u:ddp",
        // Typed at the : prompt.
        "ex:s/a/Q/g", "ex:d", "ex:2,4d", "ex:2,3t$", "ex:>", "ex:%sort",
        "ex:2,4j", "ex:normal Ax", "ex:t.", "ex:m0", "ex:m+1",
        "ex:%s/a/Q/g", "ex:%normal Ax", "ex:2,4g/a/d",
    };

    private static final String[] AWAY = {
        "", "G", "gg", "jj", "kk", "G$", "3j$",
    };

    @Test
    public void everyEditUndoesAndRedoesFromAnywhere()
    {
        final List<String> failures = new ArrayList<>();
        for (String edit : EDITS) {
            for (String away : AWAY) {
                for (int startLine : new int[] {1, 3}) {
                    final boolean twice = edit.startsWith("2u:");
                    final String keys = twice ? edit.substring(3) : edit;
                    final String u = twice ? "2u" : "u";
                    final String r = twice ? "2<C-r>" : "<C-r>";
                    final String label = "start " + startLine + ": " + keys
                        + " | " + away + " | " + u;
                    EditorHarness h = null;
                    try {
                        h = EditorHarness.create().vim();
                        h.value(TEXT).cursor(startLine, 2);
                        if (keys.startsWith("ex:"))
                            h.keys(":").exCommand(keys.substring(3));
                        else
                            h.keys(keys);
                        final String edited = h.value();
                        if (edited.equals(TEXT))
                            continue; // nothing to undo from here
                        h.keys(away);
                        h.keys(u);
                        if (!TEXT.equals(h.value())) {
                            failures.add(label + "\n    got: "
                                         + h.value().replace("\n", "\\n"));
                            continue;
                        }
                        h.keys(away);
                        h.keys(r);
                        if (!edited.equals(h.value()))
                            failures.add(label + " | " + away + " | " + r
                                         + "\n    want: "
                                         + edited.replace("\n", "\\n")
                                         + "\n    got:  "
                                         + h.value().replace("\n", "\\n"));
                    }
                    catch (Throwable t) {
                        failures.add(label + "\n    threw " + t);
                    }
                    finally {
                        if (h != null)
                            h.close();
                    }
                }
            }
        }
        assertTrue(failures.size() + " failures:\n" + String.join("\n", failures),
                   failures.isEmpty());
    }
}
