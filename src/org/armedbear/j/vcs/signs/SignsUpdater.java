/*
 * SignsUpdater.java
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

package org.armedbear.j.vcs.signs;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.Line;
import org.armedbear.j.Signs;
import org.armedbear.j.mode.compare.Side;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Diff.Edit;
import org.armedbear.j.util.PickState;
import org.armedbear.j.vcs.Bases;
import org.armedbear.j.vcs.BasesCache;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.ChangeTracker;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.diff.Hunk;

/**
 * A tracked file's buffer's signs: worked out again 100 ms after its text
 * stops changing, or when its repository does, the bases read in the
 * background and kept until the repository changes. While an open git
 * status has the file's diff read, and it is still the file's, by that
 * diff, so the signs are where its boxes are, and stay put as it picks.
 */
public final class SignsUpdater implements Buffer.Follower {
    static final int DELAY_MILLIS = 100;

    private final Buffer buffer;
    private final BasesCache bases;
    private final Timer timer;
    private boolean running;
    private boolean again;
    // The signs last worked out, before picks mark them; git's edits its
    // status offered them, null for none, and the edits they took.
    private Signs computed;
    private List<Edit> offered;
    private List<Edit> used;

    public SignsUpdater(Buffer buffer, VcsRepository repository, String path) {
        this.buffer = buffer;
        bases = new BasesCache(repository, path, null);
        timer = new Timer(DELAY_MILLIS, e -> update());
        timer.setRepeats(false);
    }

    /** The buffer whose signs it works out. */
    public Buffer getBuffer() {
        return buffer;
    }

    public VcsRepository getRepository() {
        return bases.getRepository();
    }

    /** The path in the repository, '/'-separated. */
    public String getPath() {
        return bases.getPath();
    }

    /** The bases it reads, which a comparison of the same file shares. */
    public BasesCache getBases() {
        return bases;
    }

    /** Works the signs out again, soon. */
    public void changed() {
        SwingUtilities.invokeLater(timer::restart);
    }

    @Override
    public void textChanged() {
        changed();
    }

    // What is staged or committed may have changed: the bases read again.
    @Override
    public void vcsChecked() {
        bases.invalidate();
        changed();
    }

    // The bases, or none where git could not say: read again next time.
    private Bases bases() {
        final Bases b = bases.get();
        return b != null ? b : new Bases(null, null);
    }

    // On the event thread: the buffer's lines now, then the rest in the background.
    private void update() {
        if (buffer.getSignsUpdater() != this || !Editor.getBufferList().contains(buffer))
            return;
        if (running) {
            again = true;
            return;
        }
        final List<Line> lines = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        try {
            buffer.lockRead();
        }
        catch (InterruptedException e) {
            return;
        }
        final int modCount;
        try {
            for (Line l = buffer.getFirstLine(); l != null; l = l.next()) {
                lines.add(l);
                texts.add(l.getText());
            }
            modCount = buffer.getModCount();
        }
        finally {
            buffer.unlockRead();
        }
        // While a status has its diff read, its signs are git's, if that is
        // still the file's.
        final List<Edit> git = gitEdits();
        final boolean tracking = tracker() != null;
        running = true;
        Background.then("vcs signs", () -> {
            final Bases b = bases();
            if (b.index() == null)
                return null;
            final Charset cs = buffer.getCharset();
            final Side now = Side.ofBuffer(texts, cs, false);
            final Side index = Side.of(b.index(), cs);
            final Side head = b.head() == null ? null : Side.of(b.head(), cs);
            final List<Edit> unstaged = SignsMath.unstaged(index.keys(), now.keys(), git);
            return new Computed(
                    SignsMath.compute(lines, now.keys(), index.keys(), head == null ? null : head.keys(), unstaged),
                    unstaged,
                    tracking ? changes(lines, index, now, unstaged) : null);
        }, c -> done(c, git, modCount));
    }

    // Signs worked out, the buffer's edits against the index they took, and
    // those as changes for its changelists, or null.
    private record Computed(Signs signs, List<Edit> unstaged, List<ChangeTracker.Now> changes) {}

    // The edits from index to now, the buffer's lines, as changes: each a
    // run of a hunk, with the lines it adds, or the line where it removes.
    private static List<ChangeTracker.Now> changes(List<Line> lines, Side index, Side now, List<Edit> edits) {
        final List<ChangeTracker.Now> out = new ArrayList<>();
        for (Edit e : edits) {
            final Hunk h = EditorHunks.hunk(index, now, e, edits);
            for (Hunk.Run r : h.runs()) {
                final int from = Math.min(r.newRow(), lines.size());
                final int to = Math.min(r.newRow() + r.addedCount(), lines.size());
                // Lines only removed: under the line above where they were.
                final int above = Math.min(Math.max(0, r.newRow() - 1), lines.size() - 1);
                final Line under = r.addedCount() == 0 && above >= 0 ? lines.get(above) : null;
                out.add(new ChangeTracker.Now(h, r, List.copyOf(lines.subList(from, to)), under));
            }
        }
        return out;
    }

    // Its file's changelists, as it is edited; null where its version
    // control keeps none of j's.
    private ChangeTracker tracker;
    private boolean trackerSought;

    /** Its file's changes in their changelists, as it is edited; null if there are none. */
    public ChangeTracker tracker() {
        if (!trackerSought) {
            trackerSought = true;
            final RepositoryLists lists = RepositoryLists.of(getRepository());
            tracker = lists == null ? null : new ChangeTracker(lists, getPath());
        }
        return tracker;
    }

    /** The open statuses, in the order opened. */
    private static final List<StatusView.Source> sources = new CopyOnWriteArrayList<>();

    /** s, a status opened, gives its views of files from now. */
    public static void addSource(StatusView.Source s) {
        sources.add(s);
    }

    /** s, a status closed, gives none. */
    public static void removeSource(StatusView.Source s) {
        sources.remove(s);
    }

    /** The view of its file of the first status opened that has one; null for none. */
    public StatusView view() {
        for (StatusView.Source s : sources) {
            final StatusView v = s.viewOf(this);
            if (v != null)
                return v;
        }
        return null;
    }

    // git's edits of the file as its status's diff has them; else null.
    private List<Edit> gitEdits() {
        final StatusView v = view();
        return v == null ? null : v.edits();
    }

    private void done(Computed c, List<Edit> git, int modCount) {
        running = false;
        if (buffer.getSignsUpdater() != this)
            return;
        // Changed meanwhile: these lines are not the buffer's now.
        if (again || buffer.getModCount() != modCount) {
            again = false;
            timer.restart();
            return;
        }
        computed = c == null ? null : c.signs();
        offered = git;
        used = c == null ? null : c.unstaged();
        if (c != null && c.changes() != null)
            tracker.update(c.changes(), !buffer.isModified());
        show();
    }

    /**
     * Whether its signs are by git's edits as its status has them now,
     * taken, or the same as j's own, so the status's picks, by git's rows,
     * are where its signs are.
     */
    public boolean isGits() {
        return used != null && used.equals(gitEdits());
    }

    /**
     * Whether its status's diff, as it has it now, was found not to be the
     * file's, as with whitespace ignored, so nothing is picked by its rows.
     */
    public boolean isGitsRefused() {
        return offered != null && offered.equals(gitEdits()) && !offered.equals(used);
    }

    /**
     * How much of the change at row, from 0, its status has picked, as its
     * signs show it: all, the file picked whole.
     */
    public PickState pickStateAt(int row) {
        final StatusView v = view();
        final StatusView.Picked p = v == null || buffer.isModified() ? null : v.picked();
        if (p == null)
            return PickState.NONE;
        return p.whole() ? PickState.ALL : isGits() ? v.stateAt(row) : PickState.NONE;
    }

    /**
     * What its status has picked, or read, changed: the signs marked
     * again; and worked out again if its diff is not the one they took.
     */
    public void statusChanged() {
        if (buffer.getSignsUpdater() != this)
            return;
        show();
        if (!Objects.equals(gitEdits(), offered))
            changed();
    }

    // The signs, those of picked changes marked, as the file is saved, and
    // its status's column of boxes beside them while it has any of the
    // file picked, the column showing boxes only while its signs are by
    // its diff.
    private void show() {
        final StatusView v = view();
        final boolean placed = isGits();
        final StatusView.Picked p = computed == null || v == null || buffer.isModified() ? null : v.picked();
        // The file picked whole needs no rows: every sign is picked.
        final StatusView.Picked picked = p != null && (p.whole() || placed) ? p : null;
        buffer.setSigns(picked == null ? computed : computed.picked(picked));
        showColumn(v != null && v.hasPicks() ? v.column() : null);
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == buffer)
                ed.repaintDisplay();
    }

    // The status's column of boxes it shows, or null.
    private GutterColumn column;

    private void showColumn(GutterColumn c) {
        if (c == column)
            return;
        if (column != null)
            buffer.removeGutterColumn(column);
        if (c != null)
            buffer.addGutterColumn(c);
        column = c;
    }
}
