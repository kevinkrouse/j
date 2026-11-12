/*
 * SvnBlame.java
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.armedbear.j.Log;
import org.armedbear.j.util.Diff;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.blame.BlameCommit;
import org.w3c.dom.Element;

/**
 * svn blame --xml, of a revision, or for the working text of BASE: svn
 * can't blame text it is given, so the lines the text has that BASE
 * hasn't, by a diff of the two, are not committed yet. A commit's summary
 * and the one before it are from the file's log.
 */
final class SvnBlame {
    private SvnBlame() {}

    static VcsResult blame(
            VcsRepository repository,
            String path,
            String revision,
            byte[] contents,
            Whitespace ignored,
            BlameCommit.Sink sink) {
        final String target;
        if (revision == null)
            target = SvnCli.target(path);
        else {
            final String url = SvnLog.url(repository, path);
            if (url == null)
                return null;
            target = url + "@" + revision;
        }
        // A file svn takes as binary by its svn:mime-type, blamed all the same.
        final List<String> args = new ArrayList<>(List.of("blame", "--xml", "--force"));
        if (revision == null)
            args.addAll(List.of("-r", "BASE"));
        switch (ignored) {
            case NONE, TRAILING -> {}
            case AMOUNT -> args.addAll(List.of("-x", "-b"));
            case ALL -> args.addAll(List.of("-x", "-w"));
        }
        args.addAll(List.of("--", target));
        VcsResult r = SvnCli.run(SvnCli.read(repository.root(), args.toArray(new String[0])));
        // A revision from before the file was copied or moved here: where it is now, traced back.
        if (!r.succeeded() && revision != null) {
            args.set(args.size() - 1, target.substring(0, target.lastIndexOf('@')) + "@HEAD");
            args.addAll(1, List.of("-r", revision));
            r = SvnCli.run(SvnCli.read(repository.root(), args.toArray(new String[0])));
        }
        if (!r.succeeded()) {
            // Added, with no committed revision; or not under version control.
            if (revision == null && (r.stderr().contains("E195002") || r.stderr().contains("E200009"))) {
                sink.noHistory();
                return new VcsResult(r.command(), 0, r.stdout(), "", r.millis());
            }
            return r;
        }
        final List<Element> entries;
        try {
            entries = new ArrayList<>();
            for (Element t : SvnCli.children(SvnCli.xml(r.stdout()).getDocumentElement(), "target"))
                entries.addAll(SvnCli.children(t, "entry"));
        }
        catch (Exception e) {
            Log.debug(e);
            return new VcsResult(r.command(), 1, r.stdout(), "Can't read svn blame: " + e.getMessage(), r.millis());
        }
        // The file's history as of BASE, or a copy's not committed yet; a
        // revision's, or where the file is now traced back to it.
        final Map<String, String[]> log = revision == null
                ? log(repository, List.of("--", path + "@BASE"), List.of("--", SvnCli.target(path)))
                : log(
                    repository,
                    List.of("--", target),
                    List.of("-r", revision + ":1", "--", target.substring(0, target.lastIndexOf('@')) + "@HEAD"));
        final Map<String, BlameCommit> commits = new HashMap<>();
        // Each line of what svn blamed: its commit.
        final List<BlameCommit> byLine = new ArrayList<>();
        for (Element e : entries) {
            final List<Element> cs = SvnCli.children(e, "commit");
            if (cs.isEmpty()) {
                byLine.add(BlameCommit.uncommitted(path));
                continue;
            }
            final Element c = cs.get(0);
            final String rev = c.getAttribute("revision");
            byLine.add(commits.computeIfAbsent(rev, k -> {
                final String date = SvnCli.childText(c, "date");
                final long time = date == null ? 0 : Instant.parse(date).getEpochSecond();
                final String[] l = log.get(k);
                final String author = SvnCli.childText(c, "author");
                return new BlameCommit(k,
                        author == null ? "" : author,
                        "",
                        time,
                        time,
                        l == null ? "" : l[0],
                        path,
                        l == null ? null : l[1],
                        l == null || l[1] == null ? null : path,
                        false);
            }));
        }
        if (revision != null) {
            tell(sink, byLine);
            return r;
        }
        // The working text's lines by BASE's, or not committed.
        final VcsResult cat =
                SvnCli.run(SvnCli.read(repository.root(), "cat", "-r", "BASE", "--", SvnCli.target(path)));
        if (!cat.succeeded())
            return cat;
        final List<String> base = lines(cat.stdout(), ignored);
        final List<String> now = lines(contents, ignored);
        final int[] from = new int[now.size()];
        int i = 0;
        int j = 0;
        for (Diff.Edit edit : Diff.diff(base, now)) {
            while (j < edit.newStart())
                from[j++] = i++;
            for (int k = 0; k < edit.newCount(); k++)
                from[j++] = -1;
            i = edit.oldStart() + edit.oldCount();
        }
        while (j < now.size())
            from[j++] = i++;
        final List<BlameCommit> mapped = new ArrayList<>();
        for (int k = 0; k < now.size(); k++)
            mapped.add(from[k] < 0 || from[k] >= byLine.size() ? BlameCommit.uncommitted(path) : byLine.get(from[k]));
        tell(sink, mapped);
        return r;
    }

    // Each line's commit told to sink, its line in the commit's version
    // unknown: svn blame doesn't give it.
    private static void tell(BlameCommit.Sink sink, List<BlameCommit> byLine) {
        for (int k = 0; k < byLine.size(); k++)
            sink.lines(k, 1, byLine.get(k), -1);
    }

    // The text's lines, compared as blame ignores ignored's whitespace.
    private static List<String> lines(byte[] text, Whitespace ignored) {
        final List<String> out = new ArrayList<>();
        if (text == null || text.length == 0)
            return out;
        final String s = new String(text, StandardCharsets.ISO_8859_1);
        for (String line : s.split("\n", -1))
            out.add(normal(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line, ignored));
        if (s.endsWith("\n"))
            out.remove(out.size() - 1);
        return out;
    }

    private static String normal(String line, Whitespace ignored) {
        return switch (ignored) {
            case NONE, TRAILING -> line;
            case AMOUNT -> line.replaceAll("[ \t]+", " ").replaceAll(" $", "");
            case ALL -> line.replaceAll("[ \t]+", "");
        };
    }

    // The file's revisions in target's log: each one's subject, and the one before it.
    @SafeVarargs
    private static Map<String, String[]> log(VcsRepository repository, List<String>... tries) {
        for (List<String> t : tries) {
            final Map<String, String[]> read = log(repository, t);
            if (read != null)
                return read;
        }
        return Map.of();
    }

    // null if svn can't say.
    private static Map<String, String[]> log(VcsRepository repository, List<String> target) {
        final Map<String, String[]> out = new HashMap<>();
        final List<String> args = new ArrayList<>(List.of("log", "--xml"));
        args.addAll(target);
        final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), args.toArray(new String[0])));
        if (!r.succeeded())
            return null;
        try {
            final List<Element> entries = SvnCli.children(SvnCli.xml(r.stdout()).getDocumentElement(), "logentry");
            for (int k = 0; k < entries.size(); k++) {
                final String msg = SvnCli.childText(entries.get(k), "msg");
                final String subject = msg == null ? "" : msg.strip().lines().findFirst().orElse("");
                out.put(
                    entries.get(k).getAttribute("revision"),
                    new String[] {
                        subject,
                        k + 1 < entries.size() ? entries.get(k + 1).getAttribute("revision") : null });
            }
        }
        catch (Exception e) {
            Log.debug(e);
        }
        return out;
    }
}
