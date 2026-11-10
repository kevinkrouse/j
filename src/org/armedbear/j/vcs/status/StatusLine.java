/*
 * StatusLine.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j.vcs.status;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.armedbear.j.Buffer;
import org.armedbear.j.Line;
import org.armedbear.j.TextLine;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.selection.Target.Member;
import org.armedbear.j.vcs.status.StatusData.CommitItem;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.StashItem;

/**
 * A line of a status buffer, with what it shows: a repository, a header
 * field, a section, a file, a hunk, a line of a diff, or a commit.
 */
public final class StatusLine extends TextLine {
    public enum Kind {
        REPOSITORY, FIELD, MESSAGE, BLANK, SECTION, LIST, FILE, HUNK, DIFF, COMMIT, TEXT
    }

    private final Kind kind;
    // Unique in the buffer and the same after a refresh, for keeping the caret.
    private final String key;
    // The node it heads, or for a diff line its hunk: what Tab folds.
    private final String node;
    private final VcsRepository repository;
    private final String section;
    private FileItem file;
    private Hunk hunk;
    private DiffLine diffLine;
    private int oldLine;
    private int diffIndex = -1;
    private int newLine;
    private CommitItem commit;
    private StashItem stash;
    private boolean expandable;
    private boolean open;
    private int repositoryColor = -1;
    // Changed words in a diff line, as {start, end} offsets into the text.
    private List<int[]> words = List.of();
    // Under a changelist: a file's or hunk's lines in it, by hunk id, null
    // for all of them; whether a diff line is another list's change; and a
    // list's files.
    private Map<String, BitSet> only;
    private boolean foreign;
    private List<Member> members = List.of();
    // A changelist heading's name, null for a section heading.
    private String list;

    /** The hunks' headings among buffer's lines, in order: where nextChange goes in a diff. */
    public static List<Line> hunks(Buffer buffer) {
        final List<Line> out = new ArrayList<>();
        for (Line l = buffer.getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == Kind.HUNK)
                out.add(l);
        return out;
    }

    /**
     * The line that heads the node line is in or heads: a hunk's heading for
     * a diff line, and a heading itself for a heading; null if none.
     */
    public static StatusLine head(StatusLine line) {
        if (line.node() == null)
            return null;
        for (Line l = line; l != null; l = l.previous())
            if (l instanceof StatusLine s && s.key().equals(line.node()))
                return s;
        return null;
    }

    /** A line of text alone: a label and its value (FIELD), a message, a blank, or plain text. */
    public static StatusLine of(Kind kind, String text, String key) {
        return new StatusLine(kind, text, key, null, null, null);
    }

    StatusLine(Kind kind, String text, String key, String node, VcsRepository repository, String section) {
        super(text);
        this.kind = kind;
        this.key = key;
        this.node = node;
        this.repository = repository;
        this.section = section;
    }

    public Kind kind() {
        return kind;
    }

    public String key() {
        return key;
    }

    /** The key of the node it heads or is in: a diff line's hunk; null for none. */
    public String node() {
        return node;
    }

    public VcsRepository repository() {
        return repository;
    }

    /** The id of the section it is in, or null. */
    public String section() {
        return section;
    }

    public FileItem file() {
        return file;
    }

    public Hunk hunk() {
        return hunk;
    }

    public DiffLine diffLine() {
        return diffLine;
    }

    /** A diff line's index in its hunk's lines, or -1. */
    public int diffIndex() {
        return diffIndex;
    }

    /** A diff line's number in the old file, 0 if it has none there. */
    public int oldLine() {
        return oldLine;
    }

    /** A diff line's number in the new file; an old line's, the new line it was before. */
    public int newLine() {
        return newLine;
    }

    public CommitItem commit() {
        return commit;
    }

    /** The stash it shows, a commit's line; null for none. */
    public StashItem stash() {
        return stash;
    }

    /** Whether it heads lines that Tab shows and hides. */
    public boolean isExpandable() {
        return expandable;
    }

    /** Whether it is expandable and shown open. */
    public boolean isOpen() {
        return open;
    }

    /** A repository heading's color index, or -1. */
    public int repositoryColor() {
        return repositoryColor;
    }

    public List<int[]> words() {
        return words;
    }

    StatusLine file(FileItem file) {
        this.file = file;
        return this;
    }

    StatusLine hunk(Hunk hunk) {
        this.hunk = hunk;
        return this;
    }

    StatusLine diff(DiffLine line, int index, int oldLine, int newLine) {
        this.diffLine = line;
        this.diffIndex = index;
        this.oldLine = oldLine;
        this.newLine = newLine;
        return this;
    }

    StatusLine commit(CommitItem commit) {
        this.commit = commit;
        return this;
    }

    StatusLine stash(StashItem stash) {
        this.stash = stash;
        return this;
    }

    /**
     * Under a changelist, of a file or a hunk split across lists, its lines
     * in this one, by hunk id; null for all of them.
     */
    public Map<String, BitSet> only() {
        return only;
    }

    /** Whether it is a diff line under a changelist of a change in another, shown but not taken. */
    public boolean isForeign() {
        return foreign;
    }

    /** A changelist's heading's files. */
    public List<Member> members() {
        return members;
    }

    /** A changelist heading's name, null for a section heading. */
    public String list() {
        return list;
    }

    StatusLine only(Map<String, BitSet> only) {
        this.only = only;
        return this;
    }

    StatusLine foreign(boolean b) {
        this.foreign = b;
        return this;
    }

    StatusLine members(List<Member> members) {
        this.members = List.copyOf(members);
        return this;
    }

    StatusLine list(String list) {
        this.list = list;
        return this;
    }

    StatusLine expandable(boolean b) {
        this.expandable = b;
        return this;
    }

    StatusLine open(boolean b) {
        this.open = b;
        return this;
    }

    StatusLine repositoryColor(int index) {
        this.repositoryColor = index;
        return this;
    }

    void setWords(List<int[]> words) {
        this.words = words;
    }
}
