/*
 * Blame.java
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

package org.armedbear.j.vcs.blame;

import static org.armedbear.j.Constants.BINARY_MODE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Capability;
import org.armedbear.j.vcs.FileRevision;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;

/**
 * A file's buffer's blame, read while something wants it: the column, the
 * current line's. A FileRevision's blame is its file at its revision,
 * read once it succeeds. It is read again 500 ms after a save or a change
 * of HEAD or the refs, the last one shown until the new one has finished,
 * and kept if it fails; a failed one is tried again then even with nothing
 * changed. Whitespace is ignored as diffs ignore it, and read again when
 * that changes. On the event thread.
 */
public final class Blame implements Buffer.Follower, DiffWhitespace.Listener {
    static final int DELAY_MILLIS = 500;

    private final Buffer buffer;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Timer timer;
    private final AtomicBoolean told = new AtomicBoolean();
    private BlameData shown;
    private BlameData reading;
    private BlameData failed;
    private boolean again;
    // The repository's refs and the file as the last read started; null after one failed.
    private String readFor;
    // What the last read blamed: the file, or the revision, and its repository.
    private Target target;
    // A revision's repository's changes, while it is read: a failed read tried again.
    private final Consumer<VcsRepository> repositoryChanged = r -> vcsChecked();
    // Each commit's whole message, by hash, once read.
    private final Map<String, String> messages = new HashMap<>();

    private Blame(Buffer buffer, Target target) {
        this.buffer = buffer;
        this.target = target;
        timer = new Timer(DELAY_MILLIS, e -> read());
        timer.setRepeats(false);
    }

    /** Whether buffer can be blamed: its file, or a revision of one, in a repository that blames. */
    public static boolean canBlame(Buffer buffer) {
        return buffer.getBlame() != null || target(buffer) != null;
    }

    /** buffer's blame, made the first time it is asked for; null if its file can have none. */
    public static Blame of(Buffer buffer) {
        if (buffer.getBlame() != null)
            return buffer.getBlame();
        final Target t = target(buffer);
        if (t == null)
            return null;
        buffer.setBlame(new Blame(buffer, t));
        return buffer.getBlame();
    }

    // The repository that blames buffer's file, and the file's path in it; null if none.
    // A FileRevision blames its file at its revision, with no file of its own.
    private record Target(VcsRepository repository, String path, File file, String revision) {}

    private static Target target(Buffer buffer) {
        if (buffer instanceof FileRevision fr) {
            if (buffer.getModeId() == BINARY_MODE
                    || !fr.repository().backend().capabilities().contains(Capability.BLAME))
                return null;
            return new Target(fr.repository(), fr.path(), null, fr.revision());
        }
        final File file = buffer.getFile();
        if (file == null
                || !file.isLocal()
                || buffer.getType() != SystemBuffer.TYPE_NORMAL
                || buffer.getModeId() == BINARY_MODE)
            return null;
        final VcsRepository r = Repositories.forFile(file);
        if (r == null || !r.backend().capabilities().contains(Capability.BLAME))
            return null;
        return new Target(r, r.relativePath(file), file, null);
    }

    /** The repository the blame is read from. */
    VcsRepository repository() {
        return target.repository();
    }

    /** The file's path in the repository, as last read. */
    String path() {
        return target.path();
    }

    /** The revision blamed, a FileRevision's; null for the working tree. */
    String revision() {
        return target.revision();
    }

    /** hash's whole message if read already; null if not. */
    String message(String hash) {
        return messages.get(hash);
    }

    /** hash's whole message, read in the background if not yet, given to then on the event thread; null if it can't be read. */
    void readMessage(String hash, Consumer<String> then) {
        final String m = messages.get(hash);
        if (m != null) {
            then.accept(m);
            return;
        }
        final VcsRepository r = target.repository();
        Background.then("vcs blame message", () -> r.backend().readMessage(r, hash), read -> {
            if (read != null)
                messages.put(hash, read);
            then.accept(read);
        });
    }

    /** Tells listener whenever more is read; the first starts reading, and keeps it read again. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
        if (listeners.size() == 1) {
            buffer.addFollower(this);
            // A revision's buffer has no file to be checked: its repository's changes instead.
            if (target.revision() != null)
                target.repository().addListener(repositoryChanged);
            DiffWhitespace.addListener(this);
            read();
        }
    }

    /** The last one gone, it is no longer read nor kept. */
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            buffer.removeFollower(this);
            target.repository().removeListener(repositoryChanged);
            DiffWhitespace.removeListener(this);
            timer.stop();
            if (buffer.getBlame() == this)
                buffer.setBlame(null);
        }
    }

    /** The blame shown: the last read that succeeded, or the first while it streams; null before any. */
    public BlameData data() {
        return shown;
    }

    /** The last read finished, if it failed; null if it succeeded. */
    public BlameData failure() {
        return failed;
    }

    @Override
    public void textChanged() {}

    @Override
    public void diffWhitespaceChanged() {
        vcsChecked();
    }

    // A save, the repository read again, or the whitespace ignored changed:
    // blamed again if the file, the refs or the whitespace changed, or the
    // last read failed.
    @Override
    public void vcsChecked() {
        SwingUtilities.invokeLater(() -> {
            final Target t = target(buffer);
            if (t != null && !listeners.isEmpty() && !key(t).equals(readFor))
                timer.restart();
        });
    }

    private static String key(Target t) {
        // A revision is fixed: read once, never again, but for the whitespace ignored.
        return DiffWhitespace.ignored() + " "
                + (t.revision() != null
                        ? t.revision() + " " + t.path()
                        : t.repository().refsGeneration() + " " + t.path() + " " + t.file().lastModified());
    }

    // The buffer's lines now, blamed in the background.
    private void read() {
        // Not the buffer's, or the buffer closed, though a listener was left behind.
        if (listeners.isEmpty() || buffer.getBlame() != this || !Editor.getBufferList().contains(buffer))
            return;
        if (reading != null && !reading.isDone()) {
            again = true;
            return;
        }
        final Target t = target(buffer);
        if (t == null)
            return;
        target = t;
        final List<Line> lines = new ArrayList<>();
        final ByteArrayOutputStream text = new ByteArrayOutputStream();
        try {
            buffer.lockRead();
        }
        catch (InterruptedException e) {
            return;
        }
        try {
            for (Line l = buffer.getFirstLine(); l != null; l = l.next())
                lines.add(l);
            // As a save writes it, so git compares like with like. A
            // revision is read as it is, with nothing on stdin.
            if (t.revision() == null)
                buffer.write(text, buffer.getSaveEncoding());
        }
        catch (IOException e) {
            Log.error(e);
            return;
        }
        finally {
            buffer.unlockRead();
        }
        readFor = key(t);
        final BlameData data = new BlameData(lines);
        reading = data;
        if (shown == null)
            shown = data;
        final byte[] contents = t.revision() == null ? text.toByteArray() : null;
        final Whitespace ignored = DiffWhitespace.ignored();
        // The data's own sink, so a read with no history reaches it as
        // plainly as a run of lines does.
        final BlameCommit.Sink sink = new BlameCommit.Sink() {
            @Override
            public void lines(int first, int count, BlameCommit c, int source) {
                data.lines(first, count, c, source);
                tell();
            }

            @Override
            public void noHistory() {
                data.noHistory();
            }
        };
        Background.then(
            "vcs blame",
            () -> t.repository().backend().blame(t.repository(), t.path(), t.revision(), contents, ignored, sink),
            r -> done(data, r));
    }

    private void done(BlameData data, VcsResult r) {
        data.finished(r == null ? "can't blame" : r.succeeded() ? null : r.lastError());
        failed = data.error() != null ? data : null;
        // Tried again at the next save or change, though nothing it reads changed.
        if (failed != null)
            readFor = null;
        // A failed read again leaves the last good one shown.
        if (reading == data && failed == null)
            shown = data;
        tell();
        if (again) {
            again = false;
            read();
        }
    }

    // The listeners told on the event thread, once for however many runs came meanwhile.
    private void tell() {
        if (told.compareAndSet(false, true))
            SwingUtilities.invokeLater(() -> {
                told.set(false);
                for (Runnable l : listeners)
                    l.run();
            });
    }
}
