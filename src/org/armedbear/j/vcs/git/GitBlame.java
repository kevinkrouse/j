/*
 * GitBlame.java
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.armedbear.j.Log;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.blame.BlameCommit;

/**
 * git blame --incremental, read as it streams: a run of lines a group, its
 * commit's fields the first time the commit is seen.
 */
final class GitBlame {
    // The file of revisions to pass over that GitHub and others read.
    static final String IGNORE_REVS = ".git-blame-ignore-revs";
    // --ignore-revs-file came in 2.23.
    private static final int[] IGNORE_REVS_FILE = { 2, 23 };

    private GitBlame() {}

    static VcsResult blame(VcsRepository repository, String path, byte[] contents, BlameCommit.Sink sink) {
        final List<String> args = new ArrayList<>(List.of("blame", "--incremental"));
        // git reads the file only when blame.ignoreRevsFile names it.
        if (Files.isRegularFile(repository.root().resolve(IGNORE_REVS))
                && GitCli.versionAtLeast(IGNORE_REVS_FILE)
                && !ignoreRevsFileSet(repository))
            args.addAll(List.of("--ignore-revs-file", IGNORE_REVS));
        args.addAll(List.of("--contents", "-", "--", path));
        final Parser parser = new Parser(sink);
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), contents, args.toArray(new String[0])), chunk -> {
            try {
                parser.feed(chunk);
            }
            catch (RuntimeException e) {
                Log.error(e);
            }
        });
        parser.finish();
        // Not in HEAD, as a file not committed yet: none of it is.
        if (!r.succeeded() && r.stderr().contains("no such path")) {
            sink.lines(0, Integer.MAX_VALUE, BlameCommit.uncommitted(path));
            return new VcsResult(r.command(), 0, r.stdout(), "", r.millis());
        }
        return r;
    }

    // Whether blame.ignoreRevsFile is set, as of the repository's config now.
    private record Configured(long refs, boolean set) {}

    private static final Map<VcsRepository, Configured> configured = new ConcurrentHashMap<>();

    private static boolean ignoreRevsFileSet(VcsRepository repository) {
        final long refs = repository.refsGeneration();
        final Configured c = configured.get(repository);
        if (c != null && c.refs() == refs)
            return c.set();
        final boolean set =
                GitCli.run(GitCli.read(repository.root(), "config", "--get", "blame.ignoreRevsFile")).succeeded();
        configured.put(repository, new Configured(refs, set));
        return set;
    }

    /** Reads --incremental's output a chunk at a time, telling sink of each group as it ends. */
    static final class Parser {
        private static final Pattern HEADER = Pattern.compile("^([0-9a-f]{40,64}) \\d+ (\\d+) (\\d+)$");

        private final BlameCommit.Sink sink;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        // Each commit's fields as read, by hash; then the commit, once one of its groups has ended.
        private final Map<String, Map<String, String>> fields = new HashMap<>();
        private final Map<String, BlameCommit> commits = new HashMap<>();
        private String hash;
        private int first;
        private int count;
        private String previous;
        private boolean boundary;

        Parser(BlameCommit.Sink sink) {
            this.sink = sink;
        }

        // The output's end: a last line without its newline read, and a group it cut short told.
        void finish() {
            if (pending.size() > 0) {
                line(pending.toString(StandardCharsets.UTF_8));
                pending.reset();
            }
            if (hash != null) {
                sink.lines(first, count, commit(""));
                hash = null;
            }
        }

        void feed(byte[] chunk) {
            int start = 0;
            for (int i = 0; i < chunk.length; i++) {
                if (chunk[i] != '\n')
                    continue;
                pending.write(chunk, start, i - start);
                line(pending.toString(StandardCharsets.UTF_8));
                pending.reset();
                start = i + 1;
            }
            pending.write(chunk, start, chunk.length - start);
        }

        private void line(String line) {
            final Matcher m = HEADER.matcher(line);
            if (m.matches()) {
                hash = m.group(1);
                first = Integer.parseInt(m.group(2)) - 1;
                count = Integer.parseInt(m.group(3));
                previous = null;
                boundary = false;
                fields.computeIfAbsent(hash, h -> new HashMap<>());
                return;
            }
            if (hash == null)
                return;
            final int space = line.indexOf(' ');
            final String key = space < 0 ? line : line.substring(0, space);
            final String value = space < 0 ? "" : line.substring(space + 1);
            switch (key) {
                case "previous" -> previous = value;
                case "boundary" -> boundary = true;
                case "filename" -> {
                    sink.lines(first, count, commit(value));
                    hash = null;
                }
                default -> fields.get(hash).putIfAbsent(key, value);
            }
        }

        // The group's commit: made when its first group ends, then shared.
        private BlameCommit commit(String path) {
            final BlameCommit c = commits.get(hash);
            if (c != null)
                return c;
            final Map<String, String> f = fields.get(hash);
            String previousHash = null;
            String previousPath = null;
            if (previous != null) {
                final int space = previous.indexOf(' ');
                previousHash = space < 0 ? previous : previous.substring(0, space);
                previousPath = space < 0 ? null : previous.substring(space + 1);
            }
            long time = 0;
            try {
                time = Long.parseLong(f.getOrDefault("author-time", "0"));
            }
            catch (NumberFormatException e) {
                // Left at 0.
            }
            final String mail = f.getOrDefault("author-mail", "");
            final BlameCommit made = new BlameCommit(hash,
                    f.getOrDefault("author", ""),
                    mail.startsWith("<") && mail.endsWith(">") ? mail.substring(1, mail.length() - 1) : mail,
                    time,
                    f.getOrDefault("summary", ""),
                    path,
                    previousHash,
                    previousPath,
                    boundary);
            commits.put(hash, made);
            return made;
        }
    }
}
