/*
 * VimTextObjectTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.armedbear.j.EditorHarness;
import org.junit.After;
import org.junit.Test;

/**
 * Text objects: iw aw, the quote and bracket pairs, ip ap.
 *
 * The CodeMirror-compatible value() view throughout, so the strings read the
 * same as the nvim runs every expectation here was checked against.
 */
public class VimTextObjectTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text)
    {
        h = EditorHarness.create().vim();
        h.value(text);
        return h;
    }

    private void is(String expected)
    {
        assertEquals(expected, h.value());
    }

    private void at(int line, int offset)
    {
        assertEquals("line", line, h.lineNumber());
        assertEquals("offset", offset, h.offset());
    }

    // --------------------------------------------------------------- word

    @Test
    public void iwTakesTheWordTheCaretIsIn()
    {
        vim("alpha bravo charlie").cursor(0, 7).keys("diw");
        is("alpha  charlie");
        at(0, 6);
    }

    @Test
    public void awAlsoTakesTheSpaceAfterIt()
    {
        vim("alpha bravo charlie").cursor(0, 7).keys("daw");
        is("alpha charlie");
    }

    @Test
    public void iwOnASpaceTakesTheSpace()
    {
        vim("a   b c").cursor(0, 2).keys("diw");
        is("ab c");
    }

    @Test
    public void awOnASpaceTakesTheWordItLeadsTo()
    {
        vim("alpha bravo").cursor(0, 5).keys("daw");
        is("alpha");
        h.close();
        vim("a   b c").cursor(0, 1).keys("daw");
        is("a c");
    }

    @Test
    public void awFallsBackToTheBlanksBeforeAtTheEndOfALine()
    {
        vim("alpha bravo").cursor(0, 7).keys("daw");
        is("alpha");
    }

    @Test
    public void punctuationIsItsOwnWordButNotItsOwnWORD()
    {
        vim("foo.bar").cursor(0, 0).keys("diw");
        is(".bar");
        h.close();
        vim("foo.bar").cursor(0, 0).keys("diW");
        is("");
    }

    @Test
    public void aCountTakesMoreChunks()
    {
        vim("alpha bravo charlie").cursor(0, 7).keys("d2iw");
        is("alpha charlie");
    }

    @Test
    public void awWithACountTakesWholeWordsNotChunks()
    {
        // iw counts chunks, so 2iw is a word and the space after it. aw
        // counts words, so 2aw is two words and their spaces.
        vim("alpha bravo charlie delta").cursor(0, 7).keys("d2aw");
        is("alpha delta");
        h.close();
        vim("alpha bravo charlie delta").cursor(0, 5).keys("d2aw");
        is("alpha delta");
    }

    // -------------------------------------------------------------- quote

    @Test
    public void iQuoteTakesWhatIsBetweenThem()
    {
        vim("say \"hello there\" ok").cursor(0, 6).keys("di\"");
        is("say \"\" ok");
    }

    @Test
    public void aQuoteTakesTheQuotesAndTheSpaceAfter()
    {
        vim("say \"hello there\" ok").cursor(0, 6).keys("da\"");
        is("say ok");
    }

    @Test
    public void aQuoteObjectIsFoundFromBeforeIt()
    {
        // vim scans the line rather than requiring the caret to be inside.
        vim("say \"hello there\" ok").cursor(0, 0).keys("di\"");
        is("say \"\" ok");
    }

    @Test
    public void singleQuotesWorkToo()
    {
        vim("it's 'a' thing").cursor(0, 6).keys("di'");
        is("it's '' thing");
    }

    @Test
    public void anUnpairedQuoteDoesNothing()
    {
        vim("no quotes").cursor(0, 3).keys("di\"");
        is("no quotes");
    }

    @Test
    public void anApostropheDoesNotThrowThePairingOff()
    {
        // Three quotes on the line, so pairing from the start would put the
        // caret outside every pair.
        vim("it's 'a' thing").cursor(0, 6).keys("di'");
        is("it's '' thing");
    }

    // Both quotes of a pair belong to that pair, and a caret in the gap
    // between two pairs takes the quotes either side of it. Every position
    // below was checked against nvim.

    @Test
    public void aCaretOnEitherQuoteTakesThatPair()
    {
        final String v = "   \"string1\":  \"string2\";";
        for (int caret : new int[] {3, 4, 11}) {
            vim(v).cursor(0, caret).keys("di\"");
            assertEquals("caret " + caret, "   \"\":  \"string2\";", h.value());
            h.close();
        }
        for (int caret : new int[] {15, 23}) {
            vim(v).cursor(0, caret).keys("di\"");
            assertEquals("caret " + caret, "   \"string1\":  \"\";", h.value());
            h.close();
        }
        h = null;
    }

    @Test
    public void aCaretBetweenTwoPairsTakesTheGap()
    {
        final String v = "   \"string1\":  \"string2\";";
        for (int caret : new int[] {12, 14}) {
            vim(v).cursor(0, caret).keys("di\"");
            assertEquals("caret " + caret, "   \"string1\"\"string2\";",
                         h.value());
            h.close();
        }
        h = null;
    }

    // ------------------------------------------------------------ bracket

    @Test
    public void iParenTakesTheInnermostPair()
    {
        vim("f(a, g(b), c)").cursor(0, 7).keys("di(");
        is("f(a, g(), c)");
    }

    @Test
    public void aParenTakesTheBracketsAsWell()
    {
        vim("f(a, g(b), c)").cursor(0, 7).keys("da(");
        is("f(a, g, c)");
    }

    @Test
    public void aCountStepsOutThroughNestedPairs()
    {
        vim("f(a, g(b), c)").cursor(0, 7).keys("d2i(");
        is("f()");
    }

    @Test
    public void theCaretMayBeOnOrBeforeTheOpeningBracket()
    {
        vim("f(a, g(b), c)").cursor(0, 1).keys("di(");
        is("f()");
        h.close();
        vim("f(a, g(b), c)").cursor(0, 0).keys("di(");
        is("f()");
    }

    @Test
    public void theOtherBracketShapesAndTheirAliases()
    {
        vim("a [x] b").cursor(0, 3).keys("di[");
        is("a [] b");
        h.close();
        vim("a {x} b").cursor(0, 3).keys("diB");
        is("a {} b");
        h.close();
        vim("a (x) b").cursor(0, 3).keys("dib");
        is("a () b");
    }

    @Test
    public void noPairAtAllDoesNothing()
    {
        vim("no brackets").cursor(0, 3).keys("di(");
        is("no brackets");
    }

    // A bracket block that starts and ends its lines is whole lines, and a
    // closing bracket alone on its line gives that line back. Both rules are
    // why ci( on a multi-line call opens a line instead of squeezing the
    // caret between the brackets.

    @Test
    public void aBlockAloneOnItsLinesIsLinewise()
    {
        vim("f(\n  a\n)").cursor(1, 2).keys("di(");
        is("f(\n)");
        at(1, 0);
    }

    @Test
    public void textAfterTheOpeningBracketKeepsItCharwise()
    {
        vim("f(a\n  b\n)").cursor(1, 2).keys("di(");
        is("f(\n)");
        at(0, 1);
    }

    @Test
    public void textBeforeTheClosingBracketKeepsItCharwise()
    {
        vim("f(\n  a\nx)").cursor(1, 2).keys("di(");
        is("f(\n)");
    }

    @Test
    public void aBracketObjectOnOneLineIsUnaffected()
    {
        vim("f(a)").cursor(0, 2).keys("di(");
        is("f()");
    }

    @Test
    public void aParenAcrossLinesTakesTheBracketsToo()
    {
        vim("f(\n  a\n)").cursor(1, 2).keys("da(");
        is("f");
    }

    // ---------------------------------------------------------- paragraph

    @Test
    public void ipTakesTheRunOfNonBlankLines()
    {
        vim("a\nb\n\nc\nd").cursor(0, 0).keys("dip");
        is("\nc\nd");
    }

    @Test
    public void apAlsoTakesTheBlankLinesAfter()
    {
        vim("a\nb\n\nc\nd").cursor(0, 0).keys("dap");
        is("c\nd");
    }

    @Test
    public void ipOnABlankLineTakesTheBlankRun()
    {
        vim("a\nb\n\nc\nd").cursor(2, 0).keys("dip");
        is("a\nb\nc\nd");
    }

    @Test
    public void apOnTheLastParagraphTakesTheBlanksBefore()
    {
        // There are none after, so the gap that separated it goes instead --
        // otherwise deleting the last paragraph leaves a trailing blank line.
        vim("a\nb\n\nc\nd").cursor(3, 0).keys("dap");
        is("a\nb");
    }

    @Test
    public void paragraphObjectsTakeACount()
    {
        vim("a\n\nb\n\nc").cursor(0, 0).keys("d2ap");
        is("c");
        h.close();
        vim("a\n\nb\n\nc").cursor(0, 0).keys("d2ip");
        is("b\n\nc");
    }

    // ------------------------------------------------------------- visual

    @Test
    public void aTextObjectInVisualModeSelectsIt()
    {
        vim("alpha bravo").cursor(0, 7).keys("viwd");
        is("alpha ");
        h.close();
        vim("alpha bravo").cursor(0, 7).keys("vawd");
        is("alpha");
    }

    @Test
    public void ipInVisualModeSelectsTheParagraph()
    {
        vim("a\nb\n\nc").cursor(0, 0).keys("vipd");
        is("\nc");
    }

    @Test
    public void shrinkingASelectionWithATextObjectAsksForARepaint()
    {
        // A text object can make a selection smaller as easily as bigger, and
        // the lines it no longer covers have to be painted back.
        vim("alpha\nbravo\ncharlie").cursor(0, 0).keys("v2j");
        h.clearRepaintPending();
        h.keys("iw");
        assertTrue(h.repaintPending());
    }

    @Test
    public void changeThroughATextObjectLeavesInsertMode()
    {
        vim("a \"x\" b").cursor(0, 0).keys("ci\"Y<Esc>");
        is("a \"Y\" b");
        assertEquals(VimMode.NORMAL, h.vimState().getMode());
    }
}
