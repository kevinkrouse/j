/*
 * EachRepository.java
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
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.SwingUtilities;
import org.armedbear.j.Editor;
import org.armedbear.j.Log;
import org.armedbear.j.transients.Transients;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.CommandLog;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.log.LogBuffer;

/**
 * Doing a popup's command in every repository of a status or log of
 * several, with the popup's -a on, as IntelliJ does in all roots: one
 * repository after another in the background, the status bar counting
 * them, then saying where it was done, where not and why, and where it
 * failed; one that fails leaves the others done.
 */
final class EachRepository {
    private EachRepository() {}

    /** The popups' switch. */
    static final String ALL = "--all-repositories";

    /** The repositories editor's status or log has, if the popup id's -a is on and there are several; else null. */
    static List<VcsRepository> of(Editor editor, String id) {
        final List<VcsRepository> rs = VcsStatusMode.repositoriesFor(editor.getBuffer());
        return rs.size() > 1 && Transients.args(editor, id).isOn(ALL) ? rs : null;
    }

    /** Whether editor's status or log has several repositories, for a popup's -a. */
    static boolean several(Editor editor) {
        return VcsStatusMode.repositoriesFor(editor.getBuffer()).size() > 1;
    }

    /** What one repository did: its result, null if its backend can't; or why it was left alone. */
    record Done(VcsRepository repository, VcsResult result, String skipped, String error) {
        Done(VcsRepository repository, VcsResult result, String skipped) {
            this(repository, result, skipped, null);
        }

        /** Run and not done. */
        boolean failed() {
            return skipped == null && (error != null || result == null || !result.succeeded());
        }
    }

    /**
     * Runs write for each of each, a repository's or something of one,
     * that it gives a result or a reason to skip
     * for, one after another in the background, the status bar counting
     * them, then says each one's outcome: done ones together, then those
     * left alone, then those that failed, the command log opening for
     * those.
     */
    static <T> void write(
            Editor editor,
            List<T> each,
            Function<T, VcsRepository> repository,
            String doing,
            String did,
            Function<T, Done> write) {
        write(editor, each, repository, doing, did, write, done -> {});
    }

    /** As write, giving after what each did once all have. */
    static <T> void write(
            Editor editor,
            List<T> each,
            Function<T, VcsRepository> repository,
            String doing,
            String did,
            Function<T, Done> write,
            Consumer<List<Done>> after) {
        final VcsStatusBuffer status = VcsStatusMode.owner(editor);
        if (status != null && status.isWriting()) {
            editor.status("Wait: the last change is still being made");
            return;
        }
        if (status != null)
            status.startWriting();
        editor.status(doing + "…");
        Background.then("vcs " + doing.toLowerCase(Locale.ROOT), () -> {
            final List<Done> out = new ArrayList<>();
            for (int i = 0; i < each.size(); i++) {
                final int n = i + 1;
                SwingUtilities.invokeLater(() -> editor.status(doing + ": " + n + " of " + each.size() + "…"));
                final T t = each.get(i);
                try {
                    out.add(write.apply(t));
                }
                catch (RuntimeException e) {
                    // This one alone: the others' outcomes are kept.
                    Log.error(e);
                    out.add(new Done(repository.apply(t), null, null, "see the log"));
                }
            }
            return out;
        }, done -> {
            if (status != null)
                status.wrote();
            for (T t : each) {
                final VcsRepository r = repository.apply(t);
                r.invalidate();
                r.refresh();
                LogBuffer.refreshAll(r);
            }
            if (done == null) {
                editor.status(doing + " failed: see the log");
                return;
            }
            editor.status(said(doing, did, done));
            if (done.stream().anyMatch(Done::failed) && editor.getFrame() != null)
                CommandLog.vcsCommandLog();
            after.accept(done);
        });
    }

    /** "Checked out x in a, b; not in c (no x); failed in d: why". */
    static String said(String doing, String did, List<Done> done) {
        final List<String> ok = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        for (Done d : done) {
            final String name = d.repository().name();
            if (d.skipped() != null)
                skipped.add(name + " (" + d.skipped() + ")");
            else if (d.error() != null)
                failed.add(name + ": " + d.error());
            else if (d.result() == null)
                failed.add(name + ": " + d.repository().backend().name() + " can't");
            else if (d.result().succeeded())
                ok.add(name);
            else
                failed.add(name + ": " + why(doing, BranchCommands.failed(doing, d.result())));
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(ok.isEmpty() ? did + " in none" : did + " in " + String.join(", ", ok));
        if (!skipped.isEmpty())
            sb.append("; not in ").append(String.join(", ", skipped));
        if (!failed.isEmpty())
            sb.append("; failed in ").append(String.join("; ", failed));
        return sb.toString();
    }

    // What failed says after doing, as "conflicted: …"; its "failed: " left out.
    private static String why(String doing, String failed) {
        final String why = failed.substring(doing.length() + 1);
        return why.startsWith("failed: ") ? why.substring("failed: ".length()) : why;
    }
}
