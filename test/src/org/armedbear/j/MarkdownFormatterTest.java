/*
 * MarkdownFormatterTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.Assert.assertEquals;

import org.armedbear.j.mode.markdown.MarkdownFormatter;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.junit.After;
import org.junit.Test;

/**
 * What MarkdownFormatter colors each construct as. A line is shown as its
 * runs one after another, "format(text)", and text in plain text bare.
 */
public class MarkdownFormatterTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        Editor.preferences().removeProperty("MarkdownMode.conceal");
        if (h != null)
            h.close();
    }

    private void on(String text)
    {
        // The runs of formats alone: MarkdownHidingTest has what is hidden,
        // which splits them further.
        Editor.preferences().setProperty("MarkdownMode.conceal", "none");
        h = EditorHarness.create(text).mode(MarkdownMode.getMode());
        h.buffer().getFormatter().parseBuffer();
    }

    private Line line(int lineNumber)
    {
        Line line = h.buffer().getFirstLine();
        for (int i = 0; i < lineNumber; i++)
            line = line.next();
        return line;
    }

    private String runs(int lineNumber)
    {
        final Formatter formatter = h.buffer().getFormatter();
        final LineSegmentList segments = formatter.formatLine(line(lineNumber));
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            final LineSegment segment = segments.getSegment(i);
            final String name = formatter.getFormatTable()
                .lookup(segment.getFormat()).getName();
            if (name.equals("text"))
                sb.append(segment.getText());
            else
                sb.append(name).append('(').append(segment.getText()).append(')');
        }
        return sb.toString();
    }

    @Test
    public void atxHeadings()
    {
        on("# Title\n### Deep ###\n#hashtag\n#\n# C# rocks\n");
        assertEquals("headingMarker(# )heading1(Title)", runs(0));
        assertEquals("headingMarker(### )heading3(Deep)headingMarker( ###)", runs(1));
        assertEquals("#hashtag", runs(2));
        assertEquals("headingMarker(#)", runs(3));
        assertEquals("headingMarker(# )heading1(C# rocks)", runs(4));
        assertEquals(1, MarkdownFormatter.getHeadingLevel(line(0)));
        assertEquals(3, MarkdownFormatter.getHeadingLevel(line(1)));
        assertEquals(0, MarkdownFormatter.getHeadingLevel(line(2)));
    }

    @Test
    public void setextHeadings()
    {
        on("Title\n=====\n\nSub *it*\n---\n\n- item\n---\n");
        assertEquals("heading1(Title)", runs(0));
        assertEquals("headingMarker(=====)", runs(1));
        assertEquals("heading2(Sub *it*)", runs(3));
        assertEquals("headingMarker(---)", runs(4));
        assertEquals(2, MarkdownFormatter.getHeadingLevel(line(3)));
        // Under a list item, not a paragraph, --- is a rule.
        assertEquals("listMarker(-) item", runs(6));
        assertEquals("rule(---)", runs(7));
    }

    @Test
    public void rules()
    {
        on("***\n- - -\n___\n");
        assertEquals("rule(***)", runs(0));
        assertEquals("rule(- - -)", runs(1));
        assertEquals("rule(___)", runs(2));
    }

    @Test
    public void fencedCode()
    {
        // A fence that names no language; MarkdownFencedCodeTest has those.
        on("```\nint x = 1; // *x*\n```\nafter *em*\n~~~\n```\n~~~\n");
        assertEquals("fence(```)", runs(0));
        assertEquals("codeBlock(int x = 1; // *x*)", runs(1));
        assertEquals("fence(```)", runs(2));
        assertEquals("after markup(*)emphasis(em)markup(*)", runs(3));
        // Backticks do not close a tilde fence.
        assertEquals("fence(~~~)", runs(4));
        assertEquals("codeBlock(```)", runs(5));
        assertEquals("fence(~~~)", runs(6));
    }

    @Test
    public void indentedCodeFollowsABlankLine()
    {
        on("para\n    continued\n\n    code\n");
        assertEquals("    continued", runs(1));
        assertEquals("codeBlock(    code)", runs(3));
    }

    @Test
    public void emphasis()
    {
        on("**b** *i* ***bi*** ~~s~~ _u_\nsnake_case_name 2 * 3 * 4\n\\*not\\*\n");
        assertEquals("markup(**)strong(b)markup(**) markup(*)emphasis(i)markup(*) "
                     + "markup(***)strongEmphasis(bi)markup(***) "
                     + "markup(~~)strikethrough(s)markup(~~) "
                     + "markup(_)emphasis(u)markup(_)", runs(0));
        assertEquals("snake_case_name 2 * 3 * 4", runs(1));
        assertEquals("markup(\\)*notmarkup(\\)*", runs(2));
    }

    @Test
    public void codeSpans()
    {
        on("a `code` b ``x ` y`` *`no*`*\n");
        // The * in the code span neither closes nor opens.
        assertEquals("a codeMarker(`)code(code)codeMarker(`) b codeMarker(``)code(x ` y)codeMarker(``) "
                     + "markup(*)codeMarker(`)code(no*)codeMarker(`)markup(*)", runs(0));
    }

    @Test
    public void links()
    {
        on("see [the `docs`](http://x.y/z) now\n![alt](a.png)\n[a][b]\n"
           + "[id]: http://x.y \"title\"\n<https://a.b> https://a.b/c.\n");
        assertEquals("see markup([)linkText(the )codeMarker(`)code(docs)codeMarker(`)markup(]()"
                     + "url(http://x.y/z)markup()) now", runs(0));
        assertEquals("markup(![)linkText(alt)markup(]()url(a.png)markup())", runs(1));
        assertEquals("markup([)linkText(a)markup(][)url(b)markup(])", runs(2));
        assertEquals("markup([)linkText(id)markup(]:) url(http://x.y) \"title\"", runs(3));
        assertEquals("markup(<)url(https://a.b)markup(>) url(https://a.b/c).", runs(4));
    }

    @Test
    public void quotes()
    {
        on("> quoted *it*\n> > - [ ] nested\n");
        assertEquals("quoteMarker(> )quote(quoted )markup(*)emphasis(it)markup(*)",
                     runs(0));
        assertEquals("quoteMarker(> > )listMarker(-)quote( )todo([ ])quote( nested)",
                     runs(1));
    }

    @Test
    public void tasks()
    {
        on("- [ ] todo *it*\n  - [/] doing\n    - [x] done *it*\n* [-] cut\n"
           + "1. [X] numbered\n- [ ]\n- [?] not a task\n");
        assertEquals("listMarker(-) todo([ ]) todo markup(*)emphasis(it)markup(*)",
                     runs(0));
        assertEquals("  listMarker(-) inProgress([)inProgressMarker(/)inProgress(]) doing",
                     runs(1));
        assertEquals("    listMarker(-) done([x])doneText( done *it*)", runs(2));
        assertEquals("listMarker(*) cancelled([-])cancelledText( cut)", runs(3));
        assertEquals("listMarker(1.) done([X])doneText( numbered)", runs(4));
        assertEquals("listMarker(-) todo([ ])", runs(5));
        assertEquals("listMarker(-) [?] not a task", runs(6));
    }

    @Test
    public void tables()
    {
        on("| a | *b* |\n|---|:-:|\n| `x|` | y \\| z |\n");
        assertEquals("markup(|) a markup(|) markup(*)emphasis(b)markup(*) markup(|)",
                     runs(0));
        assertEquals("markup(|---|:-:|)", runs(1));
        assertEquals("markup(|) codeMarker(`)code(x|)codeMarker(`) markup(|) y markup(\\)| z "
                     + "markup(|)", runs(2));
    }

    @Test
    public void htmlCommentsAndTags()
    {
        on("a <!-- b\nc\n--> d <br/> <span class=\"x\">e</span>\n");
        assertEquals("a comment(<!-- b)", runs(0));
        assertEquals("comment(c)", runs(1));
        assertEquals("comment(-->) d htmlTag(<br/>) htmlTag(<span class=\"x\">)e"
                     + "htmlTag(</span>)", runs(2));
    }

    @Test
    public void frontMatter()
    {
        on("---\ntitle: *x*\n---\n# H\n");
        assertEquals("frontMatter(---)", runs(0));
        assertEquals("frontMatter(title: *x*)", runs(1));
        assertEquals("frontMatter(---)", runs(2));
        assertEquals("headingMarker(# )heading1(H)", runs(3));
    }

    @Test
    public void editingRecomputesState()
    {
        on("text\n```\ncode\n");
        assertEquals("codeBlock(code)", runs(2));
        h.buffer().setText("text\n\ncode\n");
        h.buffer().getFormatter().parseBuffer();
        assertEquals("code", runs(2));
    }
}
