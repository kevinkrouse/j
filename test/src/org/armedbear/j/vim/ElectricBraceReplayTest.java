/*
 * ElectricBraceReplayTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.armedbear.j.EditCommands;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Property;
import org.armedbear.j.Registers;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.perl.PerlMode;
import org.armedbear.j.mode.text.PlainTextMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The electric-brace coordinator under vim's dot command: blank-line
 * preparation, pair decision and reindentation form one compound edit, so
 * the live keystroke and the {@code .} replay run the same sequence.
 * Pins parity on contexts the old ad-hoc commands got wrong: a
 * column-zero method header gains the same indentation typed or replayed,
 * and a typed-over closer reindents in both paths.
 */
public class ElectricBraceReplayTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness java(String text) {
        h = EditorHarness.create(text).mode(JavaMode.getMode()).vim();
        return h;
    }

    // ------------------------------------------------------- blank line

    @Test
    public void blankLineBracePairsAndReindentsTheSameLiveAndReplayed() {
        java("class A {\n\n\n}\n");
        h.cursor(1, 0).keys("i{<Esc>");
        // Blank cleared, pair generated, line indented to the class body.
        h.assertText("class A {\n    {}\n\n}\n");
        // Normal mode parks the caret on the closer after Escape.
        h.assertCursorAt(1, 4);
        h.cursor(2, 0).keys(".<Esc>");
        h.assertText("class A {\n    {}\n    {}\n}\n");
    }

    @Test
    public void perlCloseBraceOutdents() {
        // Perl formats braces electrically: a typed } outdents the
        // over-indented line to the block's indent.
        h = EditorHarness.create("sub f {\n    x;\n        \n").mode(PerlMode.getMode()).vim();
        h.cursor(2, 8).keys("a}<Esc>");
        assertEquals("}", h.buffer().getLine(2).getText());
    }

    @Test
    public void blankLineBraceIsOneUndoStep() {
        java("class A {\n\n}\n");
        h.cursor(1, 0).keys("i{<Esc>");
        h.assertText("class A {\n    {}\n}\n");
        EditCommands.undo(h.editor());
        // Clear, pair and reindent are one step.
        h.assertText("class A {\n\n}\n");
    }

    // ------------------------------------------- under-indented nonblank

    @Test
    public void underIndentedMethodHeaderGainsIndentationLiveAndReplayed() {
        java("class A {\nvoid f() \n\n}\n");
        // Header at column zero: typing { reindents the whole line, not just pairs.
        h.cursor(1, 9).keys("i{<Esc>");
        h.assertText("class A {\n    void f() {}\n\n}\n");
        // Replay on the blank line below indents the same as a fresh line.
        h.cursor(2, 0).keys(".<Esc>");
        h.assertText("class A {\n    void f() {}\n    {}\n}\n");
    }

    @Test
    public void underIndentedBraceUndoRestoresTheHeader() {
        java("class A {\nvoid f() \n}\n");
        h.cursor(1, 9).keys("i{<Esc>");
        h.assertText("class A {\n    void f() {}\n}\n");
        EditCommands.undo(h.editor());
        h.assertText("class A {\nvoid f() \n}\n");
    }

    // -------------------------------------------------- closer type-over

    @Test
    public void closerTypeOverReindentsLiveAndReplayed() {
        java("class A {\nvoid f() {}\nvoid g() {}\n}\n");
        // } already under the caret: step over, then reindent this line only.
        h.cursor(1, 10).keys("i}<Esc>");
        h.assertText("class A {\n    void f() {}\nvoid g() {}\n}\n");
        h.assertCursorAt(1, 14);
        h.cursor(2, 10).keys(".<Esc>");
        h.assertText("class A {\n    void f() {}\n    void g() {}\n}\n");
    }

    @Test
    public void closerTypeOverWithTrailingContentKeepsTheCaretAfterTheBrace() {
        java("class A {\nvoid f() {}x\n}\n");
        h.cursor(1, 10).keys("i}<Esc>");
        // The trailing x survives; caret sits right after the stepped-over brace.
        h.assertText("class A {\n    void f() {}x\n}\n");
        h.assertCursorAt(1, 14);
    }

    @Test
    public void closerTypeOverUndoRestoresTheIndentation() {
        java("class A {\nvoid f() {}\n}\n");
        h.cursor(1, 10).keys("i}<Esc>");
        h.assertText("class A {\n    void f() {}\n}\n");
        // The only buffer change is the reindent.
        EditCommands.undo(h.editor());
        h.assertText("class A {\nvoid f() {}\n}\n");
    }

    // ------------------------------------------------------ auto-newline

    @Test
    public void autoNewlineSplitsTheGeneratedPairFromTheInside() {
        java("class A {\n\n}\n");
        h.buffer().setProperty(Property.AUTO_NEWLINE, true);
        h.cursor(1, 0).keys("i{<Esc>");
        // Caret never passed the generated closer: Enter opened between the braces.
        h.assertText("class A {\n    {\n        \n    }\n}\n");
    }

    // ------------------------------------------------- pairs off

    @Test
    public void pairsOffStillReindentsTheBraceLine() {
        java("class A {\n\n}\n");
        h.buffer().setProperty(Property.ELECTRIC_PAIRS, false);
        h.cursor(1, 0).keys("i{<Esc>");
        // No closer, but blank-line prep and reindent still run.
        h.assertText("class A {\n    {\n}\n");
    }

    @Test
    public void openLineOffStillPairsAndReindents() {
        java("class A {\n\n}\n");
        h.buffer().setProperty(Property.ELECTRIC_PAIRS_OPEN_LINE, false);
        h.cursor(1, 0).keys("i{<Esc>");
        // openLine only governs Enter-between; the brace pair is unaffected.
        h.assertText("class A {\n    {}\n}\n");
    }

    // ------------------------------------- generic mode without the hook

    @Test
    public void plainTextPairsBracesWithoutReindentation() {
        h = EditorHarness.create("\n\n").mode(PlainTextMode.getMode()).vim();
        h.cursor(0, 0).keys("i{<Esc>");
        // PlainTextMode has no electric-brace hook: pair generated, never reindented.
        h.assertText("{}\n\n");
        h.cursor(1, 0).keys(".<Esc>");
        h.assertText("{}\n{}\n");
    }

    // ------------------------------------------- literal bypass

    @Test
    public void literalRegisterBraceBypassesTheCoordinator() {
        java("class A {\n\n}\n");
        Registers.setText("a", "{");
        h.cursor(1, 0).keys("i<C-r>a<Esc>");
        // The literal { neither pairs nor reindents: it lands as typed.
        h.assertText("class A {\n{\n}\n");
    }

    @Test
    public void literalRegisterBraceReplayStaysLiteral() {
        java("class A {\n\n\n}\n");
        Registers.setText("a", "{");
        h.cursor(1, 0).keys("i<C-r>a<Esc>");
        h.assertText("class A {\n{\n\n}\n");
        // The replay carries the literal token, so the second line stays
        // unpaired and unindented too.
        h.cursor(2, 0).keys(".<Esc>");
        h.assertText("class A {\n{\n{\n}\n");
    }
}
