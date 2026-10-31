/*
 * RevisionBuffer.java
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

import java.util.function.Consumer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.FileRevision;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.status.SideBySide;

/** A file as a commit has it, read-only, in its file's mode: "Display.java @ 1a2b3c4". */
public final class RevisionBuffer extends Buffer implements FileRevision {
    private final VcsRepository repository;
    private final String path;
    private final String revision;

    public RevisionBuffer(VcsRepository repository, String path, String revision, String text) {
        supportsUndo = false;
        type = TYPE_OUTPUT;
        readOnly = true;
        this.repository = repository;
        this.path = path;
        this.revision = revision;
        final String name = path.substring(path.lastIndexOf('/') + 1);
        setModeFromFilename(name);
        title = name + " @ " + LogCommit.shortId(revision);
        setText(text);
        setInitialized(true);
    }

    /**
     * path at revision, read in the background and decoded as a comparison
     * decodes it, given to then on the event thread; editor told if it
     * can't be read.
     */
    public static void read(
            Editor editor,
            VcsRepository r,
            String path,
            String revision,
            Consumer<RevisionBuffer> then) {
        Background.then("vcs revision", () -> r.backend().readFileAt(r, revision, path), text -> {
            if (text == null)
                editor.status("Can't read " + path + " at " + LogCommit.shortId(revision));
            else
                then.accept(new RevisionBuffer(r, path, revision, new String(text, SideBySide.charset())));
        });
    }

    @Override
    public VcsRepository repository() {
        return repository;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String revision() {
        return revision;
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
