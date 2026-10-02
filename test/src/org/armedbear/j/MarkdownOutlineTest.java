/*
 * MarkdownOutlineTest.java
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
import static org.junit.Assert.assertNull;

import java.util.List;
import javax.swing.tree.DefaultMutableTreeNode;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.armedbear.j.mode.markdown.MarkdownTag;
import org.armedbear.j.mode.markdown.MarkdownTagger;
import org.junit.After;
import org.junit.Test;

/** Markdown's headings as tags, the outline they make, and the context. */
public class MarkdownOutlineTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        if (h != null)
            h.close();
    }

    // Tagged without parsing first: the tagger must not need the flags.
    private List<LocalTag> tags(String text)
    {
        h = EditorHarness.create(text).mode(MarkdownMode.getMode());
        new MarkdownTagger(h.buffer()).run();
        return h.buffer().getTags();
    }

    // "level:name" for each.
    private static String describe(List<LocalTag> tags)
    {
        final StringBuilder sb = new StringBuilder();
        for (LocalTag tag : tags) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(((MarkdownTag) tag).getLevel()).append(':').append(tag.getName());
        }
        return sb.toString();
    }

    // The tree as "A(B(C) D) E".
    private static String outline(DefaultMutableTreeNode node)
    {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < node.getChildCount(); i++) {
            final DefaultMutableTreeNode child =
                (DefaultMutableTreeNode) node.getChildAt(i);
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(((LocalTag) child.getUserObject()).getName());
            if (child.getChildCount() > 0)
                sb.append('(').append(outline(child)).append(')');
        }
        return sb.toString();
    }

    @Test
    public void headingsAreTagged()
    {
        assertEquals("1:Title, 2:Setext, 3:Deep",
                     describe(tags("# Title\ntext\n\nSetext\n---\n\n### Deep ###\n")));
    }

    @Test
    public void notInFencesCommentsOrFrontMatter()
    {
        assertEquals("1:Real",
                     describe(tags("---\n# meta\n---\n```sh\n# a comment\n```\n"
                                   + "<!--\n# hidden\n-->\n# Real\n#\n#hashtag\n")));
    }

    @Test
    public void namesReadAsText()
    {
        assertEquals("1:Bold code link *x* snake_case",
                     describe(tags("# **Bold** `code` [link](http://x) \\*x\\* snake_case\n")));
    }

    @Test
    public void longNamesArePaths()
    {
        final List<LocalTag> tags = tags("# A\n## B\n#### C\n## D\n");
        assertEquals("A › B › C", tags.get(2).getLongName());
        assertEquals("A › D", tags.get(3).getLongName());
        assertEquals("B", tags.get(1).getName());
    }

    @Test
    public void theOutlineNests()
    {
        final List<LocalTag> tags = tags("## Before\n# A\n## B\n### C\n## D\n# E\n#### F\n");
        final DefaultMutableTreeNode root = SidebarTagTree.buildTree(tags,
            tag -> ((MarkdownTag) tag).getLevel());
        assertEquals("Before A(B(C) D) E(F)", outline(root));
    }

    @Test
    public void theStatusBarShowsThePath()
    {
        tags("intro\n# A\n## B\ntext\n");
        h.cursor(0, 0);
        assertNull(MarkdownMode.getMode().getContextString(h.editor(), false));
        h.cursor(3, 0);
        assertEquals("A › B", MarkdownMode.getMode().getContextString(h.editor(), false));
    }
}
