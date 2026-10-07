/*
 * FindTagFinderTextFieldHandler.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.Icon;
import org.armedbear.j.util.Icons;

/**
 * Finds a tag (a definition) by fuzzy matching: the current buffer's tags
 * first, then those in the tag files of its directory and tag path. With
 * nothing typed, the buffer's own tags in order. Enter goes to the selected
 * tag; with no match, it looks the text up as the Tag prompt always has.
 */
public final class FindTagFinderTextFieldHandler extends FinderTextFieldHandler {
    // The buffer's own tags outrank those elsewhere of about the same match.
    private static final int BOOST_LOCAL = 20;

    private List<FinderItem> localItems = List.of();
    private List<FinderItem> candidates = List.of();

    public FindTagFinderTextFieldHandler(Editor editor, HistoryTextField textField) {
        super(editor, textField);
    }

    @Override
    protected String command() {
        return "findTag";
    }

    /** Collects the tags and lists them. */
    @Override
    public void start() {
        if (!isActive())
            return;
        final Buffer buffer = editor.getBuffer();
        List<FinderItem> local = new ArrayList<>();
        if (buffer.getTags() == null) {
            Tagger tagger = buffer.getMode().getTagger(buffer);
            if (tagger != null)
                tagger.run();
        }
        List<LocalTag> tags = buffer.getTags();
        if (tags != null) {
            for (LocalTag tag : tags)
                local.add(new LocalTagItem(tag));
        }
        List<FinderItem> all = new ArrayList<>(local);
        File dir = buffer.getCurrentDirectory();
        addGlobalTags(all, dir);
        List<String> dirs = TagCommands.getDirectoriesInTagPath(buffer);
        if (dirs != null) {
            for (String s : dirs) {
                File d = File.getInstance(s);
                if (d != null && !d.equals(dir))
                    addGlobalTags(all, d);
            }
        }
        localItems = Collections.unmodifiableList(local);
        candidates = Collections.unmodifiableList(all);
        editor.status(local.size() + " tags in " + buffer + ", " + (all.size() - local.size()) + " in tag files");
        refilter();
    }

    private void addGlobalTags(List<FinderItem> items, File dir) {
        if (dir == null)
            return;
        List<GlobalTag> tags = Editor.getTagFileManager().getTags(dir, editor.getMode());
        if (tags != null) {
            for (GlobalTag tag : tags)
                items.add(new GlobalTagItem(tag));
        }
    }

    @Override
    protected List<FinderItem> candidates() {
        return candidates;
    }

    @Override
    protected List<FinderItem> emptyQueryItems() {
        return localItems;
    }

    @Override
    protected void accept(FinderItem item, boolean otherWindow) {
        closePrompt();
        editor.recordJump();
        item.accept(editor, otherWindow);
    }

    @Override
    public void enter() {
        FinderItem.Row row = selection();
        if (row != null) {
            accept(row.item(), false);
            return;
        }
        String pattern = textField.getText().trim();
        if (pattern.isEmpty())
            return;
        closePrompt();
        new FindTagTextFieldHandler(editor, textField).findTag(pattern);
    }

    private static final class LocalTagItem implements FinderItem {
        private final LocalTag tag;

        LocalTagItem(LocalTag tag) {
            this.tag = tag;
        }

        @Override
        public String matchText() {
            return tag.getName();
        }

        @Override
        public String label() {
            return tag.getName();
        }

        @Override
        public int labelOffset() {
            return 0;
        }

        @Override
        public String note() {
            return "line " + (tag.lineNumber() + 1);
        }

        @Override
        public Icon icon() {
            return tag.getIcon();
        }

        @Override
        public int boost() {
            return BOOST_LOCAL;
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {
            TagCommands.gotoLocalTag(editor, tag, otherWindow);
        }
    }

    private static final class GlobalTagItem implements FinderItem {
        private final GlobalTag tag;

        GlobalTagItem(GlobalTag tag) {
            this.tag = tag;
        }

        @Override
        public String matchText() {
            return tag.getName();
        }

        @Override
        public String label() {
            return tag.getName();
        }

        @Override
        public int labelOffset() {
            return 0;
        }

        @Override
        public String note() {
            return File.getInstance(tag.getFileName()).getName();
        }

        @Override
        public Icon icon() {
            return Icons.getIconFromFile(tag.getClassName() != null ? "method" : "leaf");
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {
            TagCommands.gotoGlobalTag(editor, tag, otherWindow);
        }
    }
}
