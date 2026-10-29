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
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Signs;
import org.armedbear.j.mode.compare.Side;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.Bases;
import org.armedbear.j.vcs.BasesCache;
import org.armedbear.j.vcs.VcsRepository;

/**
 * A tracked file's buffer's signs: worked out again 100 ms after its text
 * stops changing, or when its repository does, the bases read in the
 * background and kept until the repository changes.
 */
public final class SignsUpdater implements Buffer.Follower {
    static final int DELAY_MILLIS = 100;

    private final Buffer buffer;
    private final BasesCache bases;
    private final Timer timer;
    private boolean running;
    private boolean again;

    public SignsUpdater(Buffer buffer, VcsRepository repository, String path) {
        this.buffer = buffer;
        bases = new BasesCache(repository, path, null);
        timer = new Timer(DELAY_MILLIS, e -> update());
        timer.setRepeats(false);
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
        running = true;
        Background.then("vcs signs", () -> {
            final Bases b = bases();
            if (b.index() == null)
                return null;
            final Charset cs = buffer.getCharset();
            final Side now = Side.ofBuffer(texts, cs, false);
            final Side index = Side.of(b.index(), cs);
            final Side head = b.head() == null ? null : Side.of(b.head(), cs);
            return SignsMath.compute(lines, now.keys(), index.keys(), head == null ? null : head.keys());
        }, signs -> done(signs, modCount));
    }

    private void done(Signs signs, int modCount) {
        running = false;
        if (buffer.getSignsUpdater() != this)
            return;
        // Changed meanwhile: these lines are not the buffer's now.
        if (again || buffer.getModCount() != modCount) {
            again = false;
            timer.restart();
            return;
        }
        buffer.setSigns(signs);
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == buffer)
                ed.repaintDisplay();
    }
}
