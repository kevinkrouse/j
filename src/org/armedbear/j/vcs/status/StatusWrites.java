/*
 * StatusWrites.java
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

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import org.armedbear.j.Editor;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.CommandLog;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;

/** A write from the status, or the log, in the background. */
final class StatusWrites {
    private StatusWrites() {}

    /**
     * Does write in the background, the status bar saying what it is doing,
     * then done or failed, or for a null result that the backend can't;
     * the repository read again. One at a time in a status.
     */
    static void write(
            Editor editor,
            VcsRepository r,
            String doing,
            String cant,
            Supplier<VcsResult> write,
            Function<VcsResult, String> done,
            Function<VcsResult, String> failed) {
        final VcsStatusBuffer status = VcsStatusMode.owner(editor);
        if (status != null && status.isWriting()) {
            editor.status("Wait: the last change is still being made");
            return;
        }
        if (status != null)
            status.startWriting();
        editor.status(doing + "…");
        Background.then("vcs " + doing.toLowerCase(Locale.ROOT), write, result -> {
            if (status != null)
                status.wrote();
            r.invalidate();
            r.refresh();
            if (result == null)
                editor.status(r.backend().name() + " can't " + cant);
            else if (result.succeeded())
                editor.status(done.apply(result));
            else {
                editor.status(failed.apply(result));
                if (editor.getFrame() != null)
                    CommandLog.vcsCommandLog();
            }
        });
    }
}
