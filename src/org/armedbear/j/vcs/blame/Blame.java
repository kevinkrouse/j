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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.Capability;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;

/**
 * A file's buffer's blame, read while something wants it: the column, the
 * current line's. It is read again 500 ms after a save or a change of HEAD
 * or the refs, the last one shown until the new one has finished, and
 * kept if it fails. On the event thread.
 */
public final class Blame implements Buffer.Follower {
    static final int DELAY_MILLIS = 500;

    private final Buffer buffer;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Timer timer;
    private final AtomicBoolean told = new AtomicBoolean();
    private BlameData shown;
    private BlameData reading;
    private BlameData failed;
    private boolean again;
    // The repository's refs and the file as the last read started.
    private String readFor;

    private Blame(Buffer buffer) {
        this.buffer = buffer;
        timer = new Timer(DELAY_MILLIS, e -> read());
        timer.setRepeats(false);
    }

    /** buffer's blame, made the first time it is asked for; null if its file can have none. */
    public static Blame of(Buffer buffer) {
        if (target(buffer) == null)
            return null;
        if (buffer.getBlame() == null)
            buffer.setBlame(new Blame(buffer));
        return buffer.getBlame();
    }

    // The repository that blames buffer's file, and the file's path in it; null if none.
    private record Target(VcsRepository repository, String path, File file) {}

    private static Target target(Buffer buffer) {
        final File file = buffer.getFile();
        if (file == null
                || !file.isLocal()
                || buffer.getType() != SystemBuffer.TYPE_NORMAL
                || buffer.getModeId() == BINARY_MODE)
            return null;
        final VcsRepository r = Repositories.forFile(file);
        if (r == null || !r.backend().capabilities().contains(Capability.BLAME))
            return null;
        return new Target(r, r.relativePath(file), file);
    }

    /** Tells listener whenever more is read; the first starts reading, and keeps it read again. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
        if (listeners.size() == 1) {
            buffer.addFollower(this);
            read();
        }
    }

    /** The last one gone, it is no longer read nor kept. */
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            buffer.removeFollower(this);
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

    // A save, or the repository read again: blamed again if the file or the refs changed.
    @Override
    public void vcsChecked() {
        SwingUtilities.invokeLater(() -> {
            final Target t = target(buffer);
            if (t != null && !listeners.isEmpty() && !key(t).equals(readFor))
                timer.restart();
        });
    }

    private static String key(Target t) {
        return t.repository().refsGeneration() + " " + t.path() + " " + t.file().lastModified();
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
            // As a save writes it, so git compares like with like.
            buffer.write(text, buffer.getSaveEncoding());
        }
        catch (IOException e) {
            Log.error(e);
            return;
        }
        finally {
            buffer.unlockRead();
        }
        // A failed read is tried again when the file or the refs change.
        readFor = key(t);
        final BlameData data = new BlameData(lines);
        reading = data;
        if (shown == null)
            shown = data;
        final byte[] contents = text.toByteArray();
        Background.then(
            "vcs blame",
            () -> t.repository().backend().blame(t.repository(), t.path(), contents, (first, count, c) -> {
                data.lines(first, count, c);
                tell();
            }),
            r -> done(data, r));
    }

    private void done(BlameData data, VcsResult r) {
        data.finished(r == null ? "can't blame" : r.succeeded() ? null : r.lastError());
        failed = data.error() != null ? data : null;
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
