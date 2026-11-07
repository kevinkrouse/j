/*
 * CommitMessageBuffer.java
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

import static org.armedbear.j.Constants.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.armedbear.j.Buffer;
import org.armedbear.j.CommentRing;
import org.armedbear.j.Line;
import org.armedbear.j.Property;
import org.armedbear.j.vcs.CommitOption;
import org.armedbear.j.vcs.VcsRepository;

/**
 * A commit message being written, in a window of its own: Ctrl Enter
 * commits it, Escape gives it up. Lines starting with # are left out, as
 * git's own editor leaves them.
 */
public final class CommitMessageBuffer extends Buffer {
    // What it commits to: one repository, or with a status of several each
    // with something staged; after some fail, those left.
    private List<VcsRepository> repositories;
    private final Set<CommitOption> options;
    private final VcsStatusBuffer status;
    private boolean committing;
    // Where Ctrl P and Ctrl N are in the comment ring; -1 before either.
    int ringIndex = -1;

    CommitMessageBuffer(
            List<VcsRepository> repositories,
            Set<CommitOption> options,
            VcsStatusBuffer status,
            String text) {
        this.repositories = List.copyOf(repositories);
        this.options = options.isEmpty() ? EnumSet.noneOf(CommitOption.class) : EnumSet.copyOf(options);
        this.status = status;
        // What it commits from kept read while it is open, the status closed or not.
        if (status != null)
            status.model().hold(this, null);
        initializeUndo();
        type = TYPE_OUTPUT;
        mode = CommitMessageMode.getMode();
        formatter = mode.getFormatter(this);
        lineSeparator = "\n";
        setProperty(Property.VERTICAL_RULE, 72);
        setProperty(Property.SHOW_LINE_NUMBERS, false);
        setProperty(Property.SHOW_CHANGE_MARKS, false);
        setProperty(Property.WRAP, false);
        title = title();
        withWriteLock(() -> {
            append(text);
            if (getFirstLine() == null)
                appendLine("");
            renumber();
        });
        setLoaded(true);
        setInitialized(true);
    }

    /** The repository it commits to, or the first of several. */
    public VcsRepository getRepository() {
        return repositories.get(0);
    }

    /** The repositories it commits to, one commit each. */
    public List<VcsRepository> getRepositories() {
        return repositories;
    }

    /** What it commits to now, as after it committed to some of them. */
    void setRepositories(List<VcsRepository> repositories) {
        this.repositories = List.copyOf(repositories);
        title = title();
    }

    // "git commit: x", or "git commit: 3 repositories: x, y, z"; for part
    // of the changes, "git commit changelist Feature: x".
    private String title() {
        if (partial != null)
            return getRepository().backend().name() + " commit " + partial.what() + ": "
                    + CommitCommands.names(repositories);
        final String what = options.contains(CommitOption.MESSAGE_ONLY) ? " reword: "
                : options.contains(CommitOption.AMEND) ? " amend: "
                : " commit: ";
        return getRepository().backend().name() + what + CommitCommands.names(repositories);
    }

    public Set<CommitOption> getOptions() {
        return options;
    }

    /** The status buffer it was written from, or null. */
    public VcsStatusBuffer getStatus() {
        return status;
    }

    // What it commits alone, a changelist or the picks, through a private
    // index; null for what is staged.
    private PartialCommit partial;
    // What it last found partial takes.
    private PartialCommit.Plan plan;
    // Whether all it was to commit is committed, so there's no draft to keep.
    private boolean committedAll;

    PartialCommit getPartial() {
        return partial;
    }

    PartialCommit.Plan getPlan() {
        return plan;
    }

    void setPartial(PartialCommit partial, PartialCommit.Plan plan) {
        this.partial = partial;
        this.plan = plan;
        title = title();
    }

    void committedAll() {
        committedAll = true;
    }

    // The message it started with, which Ctrl N comes back to.
    private String started = "";

    String getStarted() {
        return started;
    }

    void setStarted(String s) {
        started = s;
    }

    // A verbose message's diff, kept for the comment ring's templates.
    private String diff;

    String getDiff() {
        return diff;
    }

    void setDiff(String diff) {
        this.diff = diff;
    }

    /**
     * The scissors line, over a verbose message's diff, or null. Matched by
     * its text, as git matches it: a message with that line of its own ends
     * there.
     */
    Line scissors() {
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l.getText().equals(CommitCommands.SCISSORS))
                return l;
        return null;
    }

    /**
     * The message down to any scissors line, its # lines left out unless it
     * keeps them: what git will be given.
     */
    public String getMessage() {
        final boolean verbatim = options.contains(CommitOption.VERBATIM);
        final Line scissors = scissors();
        final StringBuilder sb = new StringBuilder();
        for (Line l = getFirstLine(); l != null && l != scissors; l = l.next())
            if (verbatim || !l.getText().startsWith("#"))
                sb.append(l.getText()).append('\n');
        return sb.toString().strip();
    }

    // Committed or given up: it goes without asking.
    private boolean closing;

    boolean isClosing() {
        return closing;
    }

    void setClosing() {
        closing = true;
    }

    // Closed some other way, as when j quits: its message to the comment ring.
    @Override
    public void dispose() {
        if (!closing && !getMessage().isEmpty())
            CommentRing.getInstance().appendNew(getMessage());
        // Given up, however closed: what was written kept as its list's draft.
        if (partial != null && !committedAll)
            partial.giveUp(getText());
        if (status != null)
            status.model().release(this, null);
        closing = true;
        super.dispose();
    }

    boolean isCommitting() {
        return committing;
    }

    void setCommitting(boolean b) {
        committing = b;
    }

    // Its text is in the comment ring if it is given up; there is no file
    // to save it to.
    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public int load() {
        return LOAD_COMPLETED;
    }

    @Override
    public String getFileNameForDisplay() {
        return title;
    }
}
