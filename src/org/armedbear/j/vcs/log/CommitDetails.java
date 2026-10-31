/*
 * CommitDetails.java
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

package org.armedbear.j.vcs.log;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Frame;
import org.armedbear.j.FrameView;
import org.armedbear.j.Log;
import org.armedbear.j.Mode;
import org.armedbear.j.WindowLayout;
import org.armedbear.j.WindowLayout.Split;
import org.armedbear.j.WindowLayout.Window;
import org.armedbear.j.mode.compare.CompareBuffer;
import org.armedbear.j.mode.compare.DiffAlignment;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.mode.compare.Side;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.status.SideBySide;

/**
 * A commit's details beside a list it is in, as the full log and the
 * status show them: its message and files over the diff of one, unified
 * or side by side.
 */
public final class CommitDetails {
    /**
     * The view the details are part of, the log's list or the status: the
     * view's buffer, which owns the panes.
     */
    public interface Host {
        /** Its details, or null while it shows none. */
        CommitDetails details();

        VcsRepository repositoryOf(LogCommit c);

        LogScope getScope();

        /** The color of c's lane, for its badges; -1 for none. */
        int laneColorOf(LogCommit c);

        String getTimeStyle();

        boolean isDisposed();

        /** =: the diff switched between unified and side by side. */
        void diffView(Frame frame, Buffer focus);

        /** q, or visiting a file: the details left, as the log closes. */
        void leave(Frame frame);

        /** Whether c is a stash, whose parents are labeled. */
        default boolean isStash(LogCommit c) {
            return false;
        }
    }

    private final Host host;
    private final Buffer owner;
    final LogFilesBuffer files;
    final LogDiffBuffer diff;
    // The diff's side-by-side form while it shows side by side.
    SideBySide sides;
    private LogCommit shown;
    // The file to pick out in the files list of commit id, once: a blame
    // click's file, which may be an older name after a rename.
    private String preferred;
    private String preferredFor;

    /** Its panes, owned by host; side by side to begin with or not. */
    public <H extends Buffer & Host> CommitDetails(H host, boolean sideBySide) {
        this.host = host;
        owner = host;
        files = new LogFilesBuffer(owner, this);
        diff = new LogDiffBuffer(owner, this);
        if (sideBySide)
            sides = newSides();
    }

    /** The next showing of commit id picks path in its files list, once. */
    public void prefer(String id, String path) {
        preferredFor = id;
        preferred = path;
    }

    /** The file preferred for commit id dropped, unless id is null or that commit's: a jump to it given up or another made. */
    void unprefer(String id) {
        if (id == null || !id.equals(preferredFor)) {
            preferred = null;
            preferredFor = null;
        }
    }

    // The file preferred for c, if it was and is taken now.
    String takePreferred(LogCommit c) {
        if (preferred == null || !c.id().equals(preferredFor))
            return null;
        final String p = preferred;
        preferred = null;
        preferredFor = null;
        return p;
    }

    Host host() {
        return host;
    }

    /** The host whose details buffer is part of, or null. */
    static Host hostOf(Buffer buffer) {
        for (Buffer b = buffer; b != null; b = b.isPrimary() ? null : b.getPrimary())
            if (b instanceof Host h)
                return h;
        return null;
    }

    /** The commit shown, or null. */
    public LogCommit shown() {
        return shown;
    }

    /** Shows c's details, unless they are shown. */
    public void show(LogCommit c) {
        if (c == null || c == shown)
            return;
        shown = c;
        files.show(c);
    }

    /** Forgets the commit shown, so that it is shown again. */
    public void forgetShown() {
        shown = null;
    }

    /** Its unified diff read again, as with whitespace ignored otherwise. */
    public void diffWhitespaceChanged() {
        if (sides == null && files.shownChange() != null)
            diff.show(files.commit(), files.shownChange(), () -> {});
    }

    /** The date written again, in the host's time style now. */
    public void restyle() {
        files.restyle();
    }

    public boolean isSideBySide() {
        return sides != null;
    }

    /** The comparison side by side, or null. */
    public CompareBuffer comparison() {
        return sides != null ? sides.comparison() : null;
    }

    /** The files pane, and the diff or the two sides. */
    public List<Buffer> panes() {
        return sides != null ? List.of(files, sides.comparison(), sides.comparison().getOld()) : List.of(files, diff);
    }

    /** Whether buffer is one of its panes, a side's included. */
    public boolean has(Buffer buffer) {
        return buffer == files || buffer == diff || sides != null && CompareBuffer.of(buffer) == sides.comparison();
    }

    /** The unified diff, laid out or not. */
    public Buffer unified() {
        return diff;
    }

    /** The diff, unified or side by side: where a diff's commands run. */
    public Buffer diffShown() {
        return sides != null ? sides.comparison() : diff;
    }

    /**
     * list at the left, the files over the diff at the right; side by side,
     * the list and the files over the two sides, across the frame. focus's
     * window focused.
     */
    public WindowLayout.Node layout(Buffer list, Buffer focus) {
        if (sides != null) {
            final CompareBuffer c = sides.comparison();
            final boolean inSides = CompareBuffer.of(focus) == c;
            final Split top = new Split(true,
                    List.of(Window.showing(list, focus != files && !inSides), Window.showing(files, focus == files)),
                    List.of(0.55, 0.45));
            return new Split(false, List.of(top, c.panes(focus)), List.of(0.4, 0.6));
        }
        final Window l = Window.showing(list, focus != files && focus != diff);
        final Window f = Window.showing(files, focus == files);
        final Window d = Window.showing(diff, focus == diff);
        // The files on top, a third of the height at most.
        final Split details = new Split(false, List.of(f, d), List.of(1 / 3.0, 2 / 3.0));
        return new Split(true, List.of(l, details), List.of(0.5, 0.5));
    }

    /**
     * The diff switched between unified and side by side in view across
     * frame. The window focused stays where it was, or the diff in its new
     * form, at the same line of the file.
     */
    public void toggleSides(Frame frame, FrameView view, Buffer focus) {
        if (sides == null) {
            sides = newSides();
            sides.replace(frame, view, focus, diff, diffFile());
            compareChange(files.commit(), files.shownChange());
            return;
        }
        final SideBySide was = sides;
        sides = null;
        // Not read while side by side: read now, then placed.
        diff.show(files.commit(), files.shownChange(), was.restore(frame, view, focus, diff, comparedFile()));
    }

    /** Closes its panes. */
    public void close() {
        if (sides != null)
            sides.close();
        sides = null;
        for (Buffer b : new Buffer[] { diff, files })
            if (Editor.getBufferList().contains(b))
                b.kill();
    }

    // The side-by-side form, part of the host, its keys the diff's.
    private SideBySide newSides() {
        return new SideBySide(owner, km -> {
            km.mapKey('=', "vcsLogDiffView");
            km.mapKey('q', "vcsLogQuit");
            km.mapKey(KeyEvent.VK_ENTER, 0, "vcsLogVisit");
            km.mapKey(' ', "vcsLogScrollDiff");
            km.mapKey(KeyEvent.VK_SPACE, SHIFT_MASK, "vcsLogScrollDiffBack");
            km.mapKey(KeyEvent.VK_BACK_SPACE, 0, "vcsLogScrollDiffBack");
            km.mapKey(KeyEvent.VK_DOWN, ALT_MASK, "vcsLogNextFile");
            km.mapKey(KeyEvent.VK_UP, ALT_MASK, "vcsLogPreviousFile");
        });
    }

    // The key of the file the unified diff shows, or null.
    private String diffFile() {
        return fileKey(diff.commit(), diff.change());
    }

    // The key of the file the files pane asked for, or null.
    private String comparedFile() {
        return fileKey(files.commit(), files.shownChange());
    }

    private String fileKey(LogCommit c, CommitChange ch) {
        return c == null || ch == null ? null : host.repositoryOf(c).root() + "|" + c.id() + "|" + ch.path();
    }

    /** Side by side, the new side's line, from 1, at editor's caret to visit; -1, saying why, for none. */
    int comparedLineAt(Editor editor) {
        return sides == null ? -1 : sides.lineAt(editor, comparedFile());
    }

    /** Whether the comparison shows the file the files pane asked for, read. For tests. */
    boolean compares() {
        return sides != null && sides.shows(comparedFile());
    }

    /**
     * Side by side, the comparison shows c's change to ch's file, as its
     * first parent had the file and as c has it; nothing for null.
     */
    void compareChange(LogCommit c, CommitChange ch) {
        if (sides == null)
            return;
        final SideBySide ss = sides;
        final String file = fileKey(c, ch);
        if (file != null && ss.isAsked(file))
            return;
        final int asked = ss.ask(file);
        if (file == null) {
            ss.show(asked, SideBySide.plainText(), "", Side.NONE, "No file", Side.NONE, null);
            return;
        }
        final VcsRepository r = host.repositoryOf(c);
        final String parent = c.firstParent();
        final String oldPath = ch.origPath() != null ? ch.origPath() : ch.path();
        final boolean hasOld = parent != null && ch.kind() != FileChange.Kind.ADDED;
        final boolean hasNew = ch.kind() != FileChange.Kind.DELETED;
        final Mode mode = SideBySide.modeFor(Path.of(ch.path()).getFileName().toString());
        final Charset charset = SideBySide.charset();
        final Whitespace ignored = DiffWhitespace.ignored();
        Background.start("vcs log compare", () -> {
            String oldTitle = oldPath + " @ " + (parent == null ? "none" : LogCommit.shortId(parent));
            String newTitle = ch.path() + " @ " + LogCommit.shortId(c.id());
            Side oldSide = Side.NONE;
            Side newSide = Side.NONE;
            boolean read = true;
            try {
                // The two read at once; null for a side that has none, or a failed read.
                final CompletableFuture<byte[]> before = CompletableFuture.supplyAsync(
                    () -> hasOld ? r.backend().readFileAt(r, parent, oldPath) : null,
                    task -> Background.start("vcs log compare old", task));
                final byte[] after = hasNew ? r.backend().readFileAt(r, c.id(), ch.path()) : null;
                final byte[] old = before.join();
                if (hasOld && old == null) {
                    oldTitle += " (not read)";
                    read = false;
                }
                if (hasNew && after == null) {
                    newTitle += " (not read)";
                    read = false;
                }
                if (old != null && Side.isBinary(old) || after != null && Side.isBinary(after)) {
                    oldTitle += " (binary)";
                    newTitle += " (binary)";
                } else {
                    oldSide = old == null ? Side.NONE : Side.ofFile(old, charset);
                    newSide = after == null ? Side.NONE : Side.ofFile(after, charset);
                }
            }
            catch (RuntimeException e) {
                Log.error(e);
                oldTitle += " (not read)";
                read = false;
            }
            final DiffAlignment alignment = DiffAlignment.of(oldSide, newSide, ignored);
            final String ot = oldTitle;
            final Side os = oldSide;
            final String nt = newTitle;
            final Side ns = newSide;
            final boolean failed = !read;
            SwingUtilities.invokeLater(() -> {
                // A failed read is read again when asked for again.
                if (failed && ss.isCurrent(asked))
                    ss.forget();
                ss.show(asked, mode, ot, os, nt, ns, alignment);
            });
        });
    }
}
