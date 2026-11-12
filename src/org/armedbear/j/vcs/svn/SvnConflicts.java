/*
 * SvnConflicts.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.svn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.Log;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsBackend.ConflictSides;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Text conflicts as svn update leaves them: markers in the file, and the
 * file as it was here (.mine) and as the update brought it (.rNEW) beside
 * it, which svn info names. A tree or property conflict isn't text: it is
 * refused, for svn resolve outside j.
 */
final class SvnConflicts {
    private SvnConflicts() {}

    /** path's sides: ours as it was here, theirs as the update brought it; null if not a text conflict. */
    static ConflictSides sides(VcsRepository repository, String path) {
        final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), "info", "--xml", "--", SvnCli.target(path)));
        if (!r.succeeded())
            return null;
        try {
            final NodeList conflicts = SvnCli.xml(r.stdout()).getElementsByTagName("conflict");
            for (int i = 0; i < conflicts.getLength(); i++) {
                final Element c = (Element) conflicts.item(i);
                if (!c.getAttribute("type").equals("text"))
                    continue;
                final String mine = SvnCli.childText(c, "prev-wc-file");
                final String theirs = SvnCli.childText(c, "cur-base-file");
                if (mine == null || theirs == null)
                    return null;
                return new ConflictSides(read(repository, mine), read(repository, theirs));
            }
        }
        catch (Exception e) {
            Log.debug(e);
        }
        return null;
    }

    private static byte[] read(VcsRepository repository, String file) throws IOException {
        return Files.readAllBytes(repository.root().resolve(Path.of(file)));
    }

    /** Takes a side of paths' text conflicts whole: svn resolve --accept mine-full or theirs-full. */
    static VcsResult takeSide(VcsRepository repository, List<String> paths, boolean ours) {
        final VcsResult refused = refuseNotText(repository, paths);
        return refused != null ? refused : resolve(repository, paths, ours ? "mine-full" : "theirs-full");
    }

    /** Marks paths' text conflicts resolved as the files are now: svn resolve --accept working. */
    static VcsResult markResolved(VcsRepository repository, List<String> paths) {
        final VcsResult refused = refuseNotText(repository, paths);
        return refused != null ? refused : resolve(repository, paths, "working");
    }

    private static VcsResult resolve(VcsRepository repository, List<String> paths, String accept) {
        final List<String> args = new ArrayList<>(List.of("resolve", "--accept", accept, "--"));
        for (String p : paths)
            args.add(SvnCli.target(p));
        return SvnCli.run(SvnCli.write(repository.root(), args.toArray(new String[0])));
    }

    // A failure for the first of paths in a tree or property conflict, or null for none.
    private static VcsResult refuseNotText(VcsRepository repository, List<String> paths) {
        final Snapshot snapshot = repository.snapshotNow();
        for (String p : paths) {
            final FileChange c = snapshot.change(p);
            final String code = c == null ? "" : c.code();
            final String what = switch (code) {
                case "tree-conflicted" -> "a tree conflict";
                case "props-conflicted", "text-props-conflicted" -> "a conflict of its properties";
                default -> null;
            };
            if (what != null)
                return new VcsResult(null, 1, new byte[0], p + " is in " + what + ": svn resolve it outside j", 0);
        }
        return null;
    }
}
