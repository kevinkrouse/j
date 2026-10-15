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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Property;
import org.armedbear.j.Signs;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.Bases;
import org.armedbear.j.vcs.VcsRepository;

/**
 * A tracked file's buffer's signs: worked out again 100 ms after its text
 * stops changing, or when its repository does, the bases read in the
 * background and kept until the repository changes.
 */
public final class SignsUpdater {
    static final int DELAY_MILLIS = 100;

    private final Buffer buffer;
    private final VcsRepository repository;
    private final String path;
    private final Timer timer;
    // The bases read last, until the repository changes; null to read them.
    private Bases bases;
    // Bumped when the bases change: a read begun before cannot keep its bases.
    private long basesGeneration;
    private boolean running;
    private boolean again;

    public SignsUpdater(Buffer buffer, VcsRepository repository, String path) {
        this.buffer = buffer;
        this.repository = repository;
        this.path = path;
        timer = new Timer(DELAY_MILLIS, e -> update());
        timer.setRepeats(false);
    }

    public VcsRepository getRepository() {
        return repository;
    }

    /** The path in the repository, '/'-separated. */
    public String getPath() {
        return path;
    }

    /** Reads the bases again next time: what is staged or committed changed. */
    public synchronized void basesChanged() {
        basesGeneration++;
        bases = null;
    }

    /** The buffer's text changed, or its bases: works the signs out again, soon. */
    public void changed() {
        SwingUtilities.invokeLater(timer::restart);
    }

    // The bases, read if they are not kept; null if there are none.
    private Bases bases() {
        final long generation;
        synchronized (this) {
            if (bases != null)
                return bases;
            generation = basesGeneration;
        }
        Bases b = repository.backend().readBases(repository, path);
        if (b == null)
            b = new Bases(null, null);
        synchronized (this) {
            if (basesGeneration == generation)
                bases = b;
        }
        return b;
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
        Background.start("vcs signs", () -> {
            Signs signs = null;
            try {
                final Bases b = bases();
                if (b.index() != null) {
                    final Charset cs = charset();
                    final EditorHunks.Side now = EditorHunks.Side.ofBuffer(texts, cs, false);
                    final EditorHunks.Side index = EditorHunks.Side.of(b.index(), cs);
                    final EditorHunks.Side head = b.head() == null ? null : EditorHunks.Side.of(b.head(), cs);
                    signs = SignsMath.compute(lines, now.keys(), index.keys(), head == null ? null : head.keys());
                }
            }
            catch (RuntimeException e) {
                Log.error(e);
            }
            final Signs result = signs;
            SwingUtilities.invokeLater(() -> done(result, modCount));
        });
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

    Charset charset() {
        String name = buffer.getFile() != null ? buffer.getFile().getEncoding() : null;
        if (name == null)
            name = Editor.preferences().getStringProperty(Property.DEFAULT_ENCODING);
        try {
            return Charset.forName(name);
        }
        catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
