/*
 * SvnLog.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.svn;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.armedbear.j.Log;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.diff.DiffParser;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.log.CommitChange;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogQuery;
import org.w3c.dom.Element;

/**
 * A working copy's history, from svn log --xml of its URL at HEAD: one
 * lane, each revision's parent the next older one in the history read,
 * filters aside. Ids are revision numbers. The working copy's own history
 * stops where its URL was copied, as where a branch was made.
 */
final class SvnLog {
    private SvnLog() {}

    // A log entry as read.
    private record Entry(String revision, String author, long time, String message) {
        // A revision may have no author.
        Entry {
            if (author == null)
                author = "";
        }
    }

    // The working copy's URL, its path in the repository ("/trunk"), and its revision.
    private record Where(String url, String path, String revision) {}

    private static Where where(VcsRepository repository) {
        final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), "info", "--xml", "--", "."));
        if (!r.succeeded())
            return null;
        try {
            final Element entry = SvnCli.children(SvnCli.xml(r.stdout()).getDocumentElement(), "entry").get(0);
            final String relative = SvnCli.childText(entry, "relative-url");
            // Escaped, as a URL, where log paths aren't.
            return new Where(SvnCli.childText(entry, "url"),
                    relative == null ? "" : unescape(relative.replaceFirst("^\\^", "").replaceFirst("/$", "")),
                    entry.getAttribute("revision"));
        }
        catch (Exception e) {
            Log.debug(e);
            return null;
        }
    }

    /** path's URL in the repository the working copy is of, or null if svn can't say. */
    static String url(VcsRepository repository, String path) {
        final Where w = where(repository);
        return w == null ? null : url(w, path);
    }

    // path's URL, the working copy's if path is null: a % in it escaped,
    // which svn would read as an escape; svn escapes the rest.
    private static String url(Where w, String path) {
        return path == null || path.isEmpty() ? w.url() : w.url() + "/" + path.replace("%", "%25");
    }

    // s with its %XX escapes, of UTF-8 bytes, decoded.
    static String unescape(String s) {
        if (s.indexOf('%') < 0)
            return s;
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final byte[] b = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < b.length; i++)
            if (b[i] == '%' && i + 2 < b.length && hex(b[i + 1]) >= 0 && hex(b[i + 2]) >= 0) {
                out.write(hex(b[i + 1]) * 16 + hex(b[i + 2]));
                i += 2;
            } else
                out.write(b[i]);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(byte c) {
        return Character.digit(c, 16);
    }

    /**
     * The revisions query takes, newest first, from the skip-th on, at most
     * max; null if svn can't say, none for a path with no history. svn's
     * --search limits what it searches, not what it finds, and is a glob:
     * author and text are matched here, the history read on until a page
     * is full.
     */
    static List<LogCommit> read(VcsRepository repository, LogQuery query, int skip, int max) {
        final Where w = where(repository);
        if (w == null)
            return null;
        final Instant since;
        try {
            since = query.since() == null
                    ? null
                    : since(query.since(), Instant.now()).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
        // One more than the page, for its last commit's parent.
        final int wanted = skip + max + 1;
        final String target = url(w, query.path());
        // Where HEAD hasn't it, as deleted there, as the working copy has it.
        String peg = "HEAD";
        // A revision given, as blame's commit, the history from it; a branch or tag, which svn hasn't, none.
        String from = "HEAD";
        for (String r : query.revisions())
            if (r.matches("\\d+")) {
                from = r;
                break;
            }
        int limit = Math.max(wanted, 100);
        final List<Entry> raw = new ArrayList<>();
        final List<Integer> passed = new ArrayList<>();
        while (true) {
            final List<String> args = new ArrayList<>(List.of("log", "--xml", "--limit", String.valueOf(limit)));
            args.addAll(List.of("-r", from + ":" + (since == null ? "0" : "{" + since + "}")));
            if (query.path() == null)
                args.add("--stop-on-copy");
            // The last @ is the peg revision, whatever the path has.
            args.addAll(List.of("--", target + "@" + peg));
            final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), args.toArray(new String[0])));
            if (!r.succeeded()) {
                if (raw.isEmpty() && peg.equals("HEAD") && !w.revision().isEmpty()) {
                    peg = w.revision();
                    from = w.revision();
                    continue;
                }
                return raw.isEmpty() && query.path() != null ? List.of() : null;
            }
            final List<Entry> read = entries(r.stdout());
            if (read == null)
                return null;
            for (Entry e : read) {
                raw.add(e);
                if (matches(e, query, since))
                    passed.add(raw.size() - 1);
            }
            final long last = read.isEmpty() ? 0 : Long.parseLong(read.get(read.size() - 1).revision());
            if (passed.size() >= wanted || read.size() < limit || last <= 1)
                break;
            from = String.valueOf(last - 1);
            limit = Math.min(limit * 2, 5000);
        }
        final List<LogCommit> out = new ArrayList<>();
        for (int i = skip; i < Math.min(passed.size(), skip + max); i++) {
            final int at = passed.get(i);
            final Entry e = raw.get(at);
            final List<String> parents = at + 1 < raw.size() ? List.of(raw.get(at + 1).revision()) : List.of();
            final List<String> graph =
                    i + 1 < passed.size() ? List.of(raw.get(passed.get(i + 1)).revision()) : List.of();
            out.add(
                new LogCommit(e.revision(),
                        parents,
                        e.author(),
                        "",
                        e.time(),
                        List.of(),
                        subject(e.message()),
                        graph,
                        e.time(),
                        repository));
        }
        return out;
    }

    // Whether e is the query's: by its author and message, and no older
    // than since, which svn's {date} reads as the last revision at or before it.
    private static boolean matches(Entry e, LogQuery query, Instant since) {
        if (since != null && e.time() < since.getEpochSecond())
            return false;
        if (query.author() != null && !contains(e.author(), query.author()))
            return false;
        return query.grep() == null || contains(e.message(), query.grep());
    }

    private static boolean contains(String s, String part) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    private static String subject(String message) {
        final String m = message == null ? "" : message.strip();
        final int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl).strip();
    }

    private static final Pattern AGO =
            Pattern.compile("(\\d+)\\s*(second|minute|hour|day|week|month|year)s?\\s+ago", Pattern.CASE_INSENSITIVE);

    /**
     * since as an instant: a date ("2024-01-31", taken at its start in the
     * system's zone), an instant, or "3 weeks ago"; refused otherwise.
     */
    static Instant since(String since, Instant now) {
        final String s = since.strip();
        final Matcher m = AGO.matcher(s);
        if (m.matches()) {
            final long n = Long.parseLong(m.group(1));
            final long days = switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "week" -> 7;
                case "month" -> 30;
                case "year" -> 365;
                default -> 0;
            };
            final Duration d = switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "second" -> Duration.ofSeconds(n);
                case "minute" -> Duration.ofMinutes(n);
                case "hour" -> Duration.ofHours(n);
                case "day" -> Duration.ofDays(n);
                default -> Duration.ofDays(n * days);
            };
            return now.minus(d);
        }
        try {
            return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant();
        }
        catch (DateTimeParseException e) {
            try {
                return Instant.parse(s);
            }
            catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("not a date svn can take: " + since);
            }
        }
    }

    private static List<Entry> entries(byte[] xml) {
        try {
            final List<Entry> out = new ArrayList<>();
            for (Element e : SvnCli.children(SvnCli.xml(xml).getDocumentElement(), "logentry")) {
                final String date = SvnCli.childText(e, "date");
                out.add(
                    new Entry(e.getAttribute("revision"),
                            SvnCli.childText(e, "author"),
                            date == null ? 0 : Instant.parse(date).getEpochSecond(),
                            SvnCli.childText(e, "msg")));
            }
            return out;
        }
        catch (Exception e) {
            Log.debug(e);
            return null;
        }
    }

    /** Revision id's whole message, or null. */
    static String message(VcsRepository repository, String id) {
        final Where w = where(repository);
        if (w == null)
            return null;
        final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), "log", "--xml", "-r", id, "--", "^/@" + id));
        final List<Entry> read = r.succeeded() ? entries(r.stdout()) : null;
        return read == null || read.isEmpty() ? null : read.get(0).message() == null ? "" : read.get(0).message();
    }

    /**
     * The files revision id changed under the working copy's URL, by their
     * paths in it: a file added as a copy of one it deleted, renamed.
     */
    static List<CommitChange> changes(VcsRepository repository, String id) {
        final Where here = where(repository);
        if (here == null)
            return null;
        final List<CommitChange> changes = changes(repository, id, here);
        // From before the working copy's URL was copied, as a branch was made: under where it came from.
        if (changes != null && changes.isEmpty()) {
            final String[] from = copiedFrom(repository, here);
            if (from != null && Long.parseLong(id) < Long.parseLong(from[1]))
                return changes(repository, id, new Where(here.url(), from[0], here.revision()));
        }
        return changes;
    }

    // Where the working copy's URL was copied from, and in what revision,
    // as its oldest revision says; null if it wasn't.
    private static String[] copiedFrom(VcsRepository repository, Where w) {
        final VcsResult r = SvnCli.run(
            SvnCli.read(
                repository.root(),
                "log",
                "-v",
                "--xml",
                "--stop-on-copy",
                "-r",
                "1:HEAD",
                "--limit",
                "1",
                "--",
                w.url() + "@HEAD"));
        if (!r.succeeded())
            return null;
        try {
            final org.w3c.dom.Document doc = SvnCli.xml(r.stdout());
            for (Element entry : nodes(doc.getElementsByTagName("logentry")))
                for (Element p : nodes(entry.getElementsByTagName("path")))
                    if (p.getTextContent().equals(w.path()) && !p.getAttribute("copyfrom-path").isEmpty())
                        return new String[] { p.getAttribute("copyfrom-path"), entry.getAttribute("revision") };
        }
        catch (Exception e) {
            Log.debug(e);
        }
        return null;
    }

    private static List<Element> nodes(org.w3c.dom.NodeList n) {
        final List<Element> out = new ArrayList<>();
        for (int i = 0; i < n.getLength(); i++)
            out.add((Element) n.item(i));
        return out;
    }

    private static List<CommitChange> changes(VcsRepository repository, String id, Where w) {
        final VcsResult r =
                SvnCli.run(SvnCli.read(repository.root(), "log", "-v", "--xml", "-r", id, "--", "^/@" + id));
        if (!r.succeeded())
            return null;
        try {
            final List<Element> entries = SvnCli.children(SvnCli.xml(r.stdout()).getDocumentElement(), "logentry");
            if (entries.isEmpty())
                return List.of();
            final List<Element> paths = new ArrayList<>();
            for (Element ps : SvnCli.children(entries.get(0), "paths"))
                paths.addAll(SvnCli.children(ps, "path"));
            final Map<String, String> deleted = new HashMap<>();
            for (Element p : paths)
                if (p.getAttribute("action").equals("D"))
                    deleted.put(p.getTextContent(), p.getTextContent());
            final List<CommitChange> out = new ArrayList<>();
            final List<String> renamedFrom = new ArrayList<>();
            for (Element p : paths) {
                // A directory as "d/", what is in a copied or deleted one not listed.
                final String slash = p.getAttribute("kind").equals("dir") ? "/" : "";
                final String action = p.getAttribute("action");
                final String from = p.getAttribute("copyfrom-path");
                final String path = relative(w, p.getTextContent());
                if (path == null || path.isEmpty())
                    continue;
                if (action.equals("A") && !from.isEmpty() && deleted.containsKey(from) && relative(w, from) != null) {
                    out.add(new CommitChange(path + slash, relative(w, from) + slash, Kind.RENAMED));
                    renamedFrom.add(relative(w, from) + slash);
                    continue;
                }
                final Kind kind = switch (action) {
                    case "A" -> Kind.ADDED;
                    case "D" -> Kind.DELETED;
                    default -> Kind.MODIFIED;
                };
                out.add(new CommitChange(path + slash, null, kind));
            }
            // A rename's deletion is the rename.
            out.removeIf(c -> c.kind() == Kind.DELETED && renamedFrom.contains(c.path()));
            return out;
        }
        catch (Exception e) {
            Log.debug(e);
            return null;
        }
    }

    // A repository path's under the working copy's, or null if it isn't there.
    private static String relative(Where w, String path) {
        if (path.equals(w.path()))
            return "";
        final String base = w.path() + "/";
        return path.startsWith(base) ? path.substring(base.length()) : null;
    }

    /** One file's diff in revision id, against the revision before; EMPTY for none, null if svn can't say. */
    static FileDiff changeDiff(VcsRepository repository, String id, CommitChange change, Whitespace ignored) {
        final Where w = where(repository);
        if (w == null)
            return null;
        final long n;
        try {
            n = Long.parseLong(id);
        }
        catch (NumberFormatException e) {
            return null;
        }
        // A deleted file is where it was before.
        final long peg = change.kind() == Kind.DELETED ? n - 1 : n;
        final String target = url(w, change.path()) + "@" + peg;
        List<FileDiff> diffs = changeDiffs(repository, id, target, ignored, true);
        // From before the file was copied or moved here: where it is now, traced back.
        final String now = url(w, change.path()) + "@HEAD";
        if (diffs == null)
            diffs = changeDiffs(repository, id, now, ignored, true);
        // A replaced file comes with --git as a deletion and an add: in svn's own format, a change.
        if (diffs != null && diffs.size() > 1 && diffs.get(0).isDeleted() && diffs.get(1).isNew()) {
            final List<FileDiff> plain = changeDiffs(repository, id, target, ignored, false);
            diffs = plain != null ? plain : changeDiffs(repository, id, now, ignored, false);
        }
        if (diffs == null)
            return null;
        for (FileDiff d : diffs)
            if (!d.hunks().isEmpty() || d.isBinary() || d.isNew() || d.isDeleted())
                // By its path in the working copy, not the repository.
                return new FileDiff(d.header(),
                        d.oldPath() == null ? null : change.origPath() != null ? change.origPath() : change.path(),
                        d.newPath() == null ? null : change.path(),
                        d.isNew(),
                        d.isDeleted(),
                        d.isBinary(),
                        d.isCombined(),
                        d.hunks(),
                        d.onlyWhitespace());
        return FileDiff.EMPTY;
    }

    private static List<FileDiff> changeDiffs(
            VcsRepository repository,
            String id,
            String target,
            Whitespace ignored,
            boolean git) {
        final List<String> args = new ArrayList<>(List.of("diff", "-c", id, "--internal-diff", "-x"));
        args.add(switch (ignored) {
            case NONE, TRAILING -> "-u";
            case AMOUNT -> "-b";
            case ALL -> "-w";
        });
        if (git)
            args.add("--git");
        args.addAll(List.of("--", target));
        final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), args.toArray(new String[0])));
        return r.succeeded() ? DiffParser.parse(r.stdout()) : null;
    }

    /**
     * path's bytes in revision id, or null: where it is now, traced back
     * to id, if it wasn't at path then, as before a copy.
     */
    static byte[] fileAt(VcsRepository repository, String id, String path) {
        final Where w = where(repository);
        if (w == null)
            return null;
        VcsResult r = SvnCli.run(SvnCli.read(repository.root(), "cat", "-r", id, "--", url(w, path) + "@" + id));
        if (!r.succeeded())
            r = SvnCli.run(SvnCli.read(repository.root(), "cat", "-r", id, "--", url(w, path) + "@HEAD"));
        return r.succeeded() ? r.stdout() : null;
    }

    /** Revision id's log entry and diff under the working copy's URL, as text; null if svn can't say. */
    static byte[] commit(VcsRepository repository, String id, Whitespace ignored) {
        final Where w = where(repository);
        if (w == null)
            return null;
        // The repository's whole revision where the working copy's URL wasn't yet.
        String target = w.url() + "@" + id;
        VcsResult log = SvnCli.run(SvnCli.read(repository.root(), "log", "-v", "-r", id, "--", target));
        if (!log.succeeded()) {
            target = "^/@" + id;
            log = SvnCli.run(SvnCli.read(repository.root(), "log", "-v", "-r", id, "--", target));
        }
        if (!log.succeeded())
            return null;
        final List<String> args = new ArrayList<>(List.of("diff", "-c", id, "--git", "--internal-diff", "-x"));
        args.add(switch (ignored) {
            case NONE, TRAILING -> "-u";
            case AMOUNT -> "-b";
            case ALL -> "-w";
        });
        args.addAll(List.of("--", target));
        final VcsResult diff = SvnCli.run(SvnCli.read(repository.root(), args.toArray(new String[0])));
        final String text = log.out() + (diff.succeeded() ? diff.out() : "");
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
