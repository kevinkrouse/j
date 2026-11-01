/*
 * BraceFoldsTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.mode.java.JavaMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Java's folds by its braces, and the fold commands on them as vim's zc and zo. */
public class BraceFoldsTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness on(String text) {
        h = EditorHarness.create(text).mode(JavaMode.getMode());
        Editor.setCurrentEditor(h.editor());
        h.buffer().getFormatter().parseBuffer();
        return h;
    }

    private Line line(int n) {
        return h.buffer().getLine(n);
    }

    private boolean starts(int n) {
        return h.buffer().getMode().isFoldStart(h.buffer(), line(n));
    }

    private Line[] below(int n) {
        return h.buffer().getMode().getFoldBelow(h.buffer(), line(n));
    }

    // The lines to be seen, "|" between, "+" after one with a fold below.
    private String visible() {
        final StringBuilder sb = new StringBuilder();
        for (Line line = h.buffer().getFirstLine(); line != null; line = line.next()) {
            if (line.isHidden())
                continue;
            if (sb.length() > 0)
                sb.append('|');
            sb.append(line.getText().strip());
            if (line.next() != null && line.next().isHidden())
                sb.append('+');
        }
        return sb.toString();
    }

    private static final String TWO_METHODS = """
                                              class A {
                                                  void one() {
                                                      a();
                                                  }

                                                  void two() {
                                                      if (b) {
                                                          c();
                                                      }
                                                  }
                                              }
                                              """;

    @Test
    public void foldingAMethodsFirstLineAgainClosesTheClassNotTheMethodAbove() {
        on(TWO_METHODS).cursor(5, 4);
        FoldCommands.fold(h.editor());
        assertEquals("class A {|void one() {|a();|}||void two() {+|}|}", visible());
        FoldCommands.fold(h.editor());
        assertEquals("class A {+|}", visible());
    }

    @Test
    public void foldingInsideAMethodClosesTheMethodAndOpeningOpensIt() {
        on(TWO_METHODS).cursor(2, 8);
        FoldCommands.fold(h.editor());
        assertEquals("class A {|void one() {+|}||void two() {|if (b) {|c();|}|}|}", visible());
        assertEquals(1, h.editor().getDotLineNumber(), "the caret on the method's first line");
        FoldCommands.unfoldHere(h.editor());
        assertEquals("class A {|void one() {|a();|}||void two() {|if (b) {|c();|}|}|}", visible());
    }

    @Test
    public void onABlocksFirstLineThatBlockFolds() {
        on(TWO_METHODS).cursor(6, 8);
        FoldCommands.fold(h.editor());
        FoldCommands.unfoldHere(h.editor());
        h.cursor(6, 8);
        FoldCommands.toggleFold(h.editor());
        assertEquals("class A {|void one() {|a();|}||void two() {|if (b) {+|}|}|}", visible());
    }

    @Test
    public void aWrappedSignatureFoldsFromItsFirstLine() {
        on("""
           class A {
               void one(
                       int a,
                       int b) {
                   c();
               }
           }
           """);
        assertTrue(starts(1));
        assertFalse(starts(2), "a continuation");
        assertFalse(starts(3), "the line with the brace continues the statement");
        assertArrayEquals(new Line[] { line(2), line(4) }, below(1));
    }

    @Test
    public void aWrappedConditionOrStatementIsNoFoldUnlessItOpensABlock() {
        on("""
           class A {
               void one() {
                   if (a
                           && b) {
                       c();
                   }
                   d(e,
                           f);
               }
           }
           """);
        assertTrue(starts(2));
        assertArrayEquals(new Line[] { line(3), line(4) }, below(2));
        assertFalse(starts(6), "a wrapped call opens no block");
    }

    @Test
    public void anOpeningBraceOnItsOwnLineFoldsFromTheLineAbove() {
        on("""
           class A
           {
               void one()
               {
                   a();
               }
           }
           """);
        assertTrue(starts(2));
        assertFalse(starts(3));
        assertArrayEquals(new Line[] { line(3), line(4) }, below(2));
        assertArrayEquals(new Line[] { line(1), line(5) }, below(0));
    }

    @Test
    public void elseFoldsItsOwnBlock() {
        on("""
           void f() {
               if (a) {
                   b();
               } else {
                   c();
               }
           }
           """);
        assertArrayEquals(new Line[] { line(2), line(2) }, below(1), "the if's, to its else");
        assertArrayEquals(new Line[] { line(4), line(4) }, below(3), "the else's");
    }

    @Test
    public void bracesInStringsAndCommentsDontCount() {
        on("""
           void f() {
               s("{");
               t(); // }
           }
           """);
        assertFalse(starts(1));
        assertArrayEquals(new Line[] { line(1), line(2) }, below(0));
    }

    @Test
    public void aCommentOfSeveralLinesAndARunOfImportsFold() {
        on("""
           import a.B;
           import a.C;

           import d.E;

           /**
            * Doc { with a brace.
            */
           class A {
               /** One line. */
               int x;
           }
           """);
        assertArrayEquals(new Line[] { line(1), line(3) }, below(0), "the imports, the blank among them");
        assertFalse(starts(1));
        assertArrayEquals(new Line[] { line(6), line(7) }, below(5), "the comment");
        assertFalse(starts(6), "a brace in the comment");
        assertFalse(starts(9), "a comment of one line");
    }

    @Test
    public void anEmptyBlockHasNothingToFold() {
        on("void f() {\n}\n");
        assertFalse(starts(0) && below(0) != null);
        assertNull(below(0));
    }

    // The closed fold's count after line n, or null.
    private VirtualText.Run label(int n) {
        for (VirtualText v : h.buffer().getVirtualText().get(Buffer.FOLD_LABELS))
            if (v.line() == line(n))
                return v.rows().get(0).get(0);
        return null;
    }

    @Test
    public void aClosedFoldShowsHowManyLinesItHidesAndAClickOpensIt() {
        on(TWO_METHODS).cursor(2, 8);
        FoldCommands.fold(h.editor());
        assertEquals(Folds.label("1 line"), label(1).text());
        assertTrue(label(1).text().startsWith("\u2026 "), "the foldSymbol first");
        h.cursor(6, 8);
        FoldCommands.fold(h.editor());
        assertEquals(Folds.label("1 line"), label(6).text(), "a second, as many as are closed");
        h.cursor(5, 4);
        FoldCommands.fold(h.editor());
        assertNull(label(6), "inside one closed, counted by it");
        assertEquals(Folds.label("3 lines"), label(5).text());
        label(5).action().run();
        assertNull(label(5), "the click opened it");
        assertEquals(Folds.label("1 line"), label(6).text(), "the one inside still closed");
    }

    private static final String OPENED = """
                                         /*
                                          * License.
                                          */

                                         package a;

                                         import a.B;
                                         import a.C;

                                         /**
                                          * The class.
                                          */
                                         class A {
                                             int x;
                                         }
                                         """;

    @Test
    public void aFileOpensWithItsHeaderAndImportsFoldedAndItsClassDocAsPreferred() {
        on(OPENED);
        FoldCommands.foldOnOpen(h.editor());
        assertEquals("/*+||package a;||import a.B;+||/**|* The class.|*/|class A {|int x;|}", visible());
        FoldCommands.unfoldAll(h.editor());
        h.buffer().setProperty(Property.FOLD_HEADER_COMMENT, false);
        h.buffer().setProperty(Property.FOLD_IMPORTS, false);
        h.buffer().setProperty(Property.FOLD_CLASS_DOC, true);
        FoldCommands.foldOnOpen(h.editor());
        assertEquals("/*|* License.|*/||package a;||import a.B;|import a.C;||/**+|class A {|int x;|}", visible());
    }

    @Test
    public void aFoldTheCaretIsInStaysOpen() {
        on(OPENED).cursor(7, 0);
        FoldCommands.foldOnOpen(h.editor());
        assertEquals("/*+||package a;||import a.B;|import a.C;||/**|* The class.|*/|class A {|int x;|}", visible());
    }

    @Test
    public void lineCommentsAtTheTopAreAHeaderAndADocCommentBeforeTheClassIsNot() {
        on("// One.\n// Two.\n\nclass A {\n}\n");
        assertEquals(line(0), h.buffer().getMode().initialFold(h.buffer(), Folds.Initial.HEADER));
        assertArrayEquals(new Line[] { line(1), line(1) }, below(0));
        h.close();
        on("/**\n * The class.\n */\nclass A {\n}\n");
        assertNull(h.buffer().getMode().initialFold(h.buffer(), Folds.Initial.HEADER), "its doc, no header");
        assertEquals(line(0), h.buffer().getMode().initialFold(h.buffer(), Folds.Initial.CLASS_DOC));
    }

    @Test
    public void pythonsHeaderCommentsAndImportsFold() {
        h = EditorHarness.create("""
                                 #!/usr/bin/env python
                                 # License.

                                 import os
                                 from a import (
                                     b,
                                 )

                                 def f():
                                     pass
                                 """).mode(org.armedbear.j.mode.python.PythonMode.getMode());
        Editor.setCurrentEditor(h.editor());
        FoldCommands.foldOnOpen(h.editor());
        assertEquals("#!/usr/bin/env python+||import os+||def f():|pass", visible());
    }

    @Test
    public void foldAllFoldsEachMethodsBody() {
        on(TWO_METHODS);
        h.buffer().getTags(true);
        FoldCommands.foldMethods(h.editor());
        assertEquals("class A {|void one() {+|}||void two() {+|}|}", visible());
    }
}
