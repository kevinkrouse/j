/*
 * GitBases.java
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

package org.armedbear.j.vcs.git;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.armedbear.j.vcs.Bases;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;

/** A file's committed and staged texts, its blobs read once by id. */
final class GitBases {
    // Blobs never change, so a blob read is kept: the last few, by id.
    private static final int CACHED = 64;
    private static final Map<String, byte[]> blobs = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHED;
        }
    };

    private GitBases() {}

    static Bases read(VcsRepository repository, String path) {
        // "mode id stage\tpath": more than one entry, or a stage, is a conflict.
        final VcsResult staged =
                GitCli.run(GitCli.read(repository.root(), "--literal-pathspecs", "ls-files", "-s", "-z", "--", path));
        if (!staged.succeeded())
            return null;
        final String[] entries = staged.out().split("\0");
        String indexId = null;
        for (String e : entries) {
            if (e.isEmpty())
                continue;
            final String[] f = e.split("[ \t]", 4);
            if (f.length < 4 || !f[2].equals("0") || indexId != null)
                return null;
            indexId = f[1];
        }
        if (indexId == null)
            return new Bases(null, null); // Not tracked.
        // A file renamed since the last commit was committed as its old name.
        final Snapshot snapshot = repository.snapshot();
        final FileChange change = snapshot != null ? snapshot.change(path) : null;
        final String committed = change != null && change.origPath() != null ? change.origPath() : path;
        final VcsResult tree = GitCli
                .run(GitCli.read(repository.root(), "--literal-pathspecs", "ls-tree", "-z", "HEAD", "--", committed));
        String headId = null;
        if (tree.succeeded()) {
            // "mode type id\tpath"
            final String[] f = tree.out().split("\0")[0].split("[ \t]", 4);
            if (f.length == 4 && f[1].equals("blob"))
                headId = f[2];
        }
        final byte[] index = blob(repository, indexId);
        final byte[] head = headId == null ? null : headId.equals(indexId) ? index : blob(repository, headId);
        return index == null ? null : new Bases(head, index);
    }

    private static byte[] blob(VcsRepository repository, String id) {
        synchronized (blobs) {
            final byte[] b = blobs.get(id);
            if (b != null)
                return b;
        }
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "cat-file", "blob", id));
        if (!r.succeeded())
            return null;
        synchronized (blobs) {
            blobs.put(id, r.stdout());
        }
        return r.stdout();
    }

    /**
     * The index's stages 2 and 3 of path, ours and theirs, in one cat-file:
     * for each, "id blob size", the bytes and a newline, or "name missing";
     * null if git fails or says otherwise.
     */
    static VcsBackend.ConflictSides conflictSides(VcsRepository repository, String path) {
        final byte[] in = (":2:" + path + "\n:3:" + path + "\n").getBytes(StandardCharsets.UTF_8);
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), in, "cat-file", "--batch"));
        if (!r.succeeded())
            return null;
        final byte[] out = r.stdout();
        final byte[][] sides = new byte[2][];
        int at = 0;
        for (int i = 0; i < 2; i++) {
            final int nl = indexOf(out, (byte) '\n', at);
            if (nl < 0)
                return null;
            final String head = new String(out, at, nl - at, StandardCharsets.UTF_8);
            at = nl + 1;
            if (head.endsWith(" missing"))
                continue;
            final String[] f = head.split(" ");
            if (f.length != 3 || !f[1].equals("blob"))
                return null;
            final int size = Integer.parseInt(f[2]);
            if (at + size > out.length)
                return null;
            sides[i] = Arrays.copyOfRange(out, at, at + size);
            at += size + 1;
        }
        return new VcsBackend.ConflictSides(sides[0], sides[1]);
    }

    private static int indexOf(byte[] bytes, byte b, int from) {
        for (int i = from; i < bytes.length; i++)
            if (bytes[i] == b)
                return i;
        return -1;
    }
}
