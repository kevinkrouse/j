/*
 * DiffParser.java
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

package org.armedbear.j.vcs.diff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads unified diffs, as git and svn write them, into {@link FileDiff}s.
 * Git's paths are expected with its default a/ and b/ prefixes.
 */
public final class DiffParser {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");

    private final String[] lines;
    private int i;

    private DiffParser(String text) {
        // A final newline leaves an empty last piece, which is not a line.
        String[] split = text.split("\n", -1);
        int n = split.length;
        if (n > 0 && split[n - 1].isEmpty())
            n--;
        lines = new String[n];
        System.arraycopy(split, 0, lines, 0, n);
    }

    public static List<FileDiff> parse(byte[] diff) {
        return parse(new String(diff, StandardCharsets.ISO_8859_1));
    }

    /** text is a diff's bytes as ISO-8859-1. */
    public static List<FileDiff> parse(String text) {
        return new DiffParser(text).files();
    }

    private List<FileDiff> files() {
        List<FileDiff> files = new ArrayList<>();
        while (i < lines.length) {
            if (isCombined(lines[i]))
                files.add(combined());
            else if (startsFile(i))
                files.add(file());
            else
                i++;
        }
        return files;
    }

    private static boolean isCombined(String line) {
        return line.startsWith("diff --cc ") || line.startsWith("diff --combined ");
    }

    // A merge's diff against both parents: its path, and no hunks.
    private FileDiff combined() {
        List<String> header = new ArrayList<>();
        String line = lines[i++];
        header.add(line);
        String path = unquote(line.substring(line.indexOf(' ', "diff --".length()) + 1));
        // Its body's lines start with two columns of markers, never "diff ".
        while (i < lines.length && !lines[i].startsWith("diff ") && !lines[i].startsWith("Index: "))
            i++;
        return new FileDiff(header, path, path, false, false, false, true, List.of());
    }

    private boolean startsFile(int at) {
        String line = lines[at];
        if (line.startsWith("diff --git ") || line.startsWith("Index: "))
            return true;
        // A bare unified diff: "---" then "+++".
        return line.startsWith("--- ") && at + 1 < lines.length && lines[at + 1].startsWith("+++ ");
    }

    // What a file's header lines say, read as they come.
    private static final class Header {
        final boolean git;
        final List<String> lines = new ArrayList<>();
        String oldPath;
        String newPath;
        boolean sawOld;
        boolean sawNew;
        boolean isNew;
        boolean isDeleted;
        boolean binary;
        String renameFrom;
        String renameTo;

        Header(boolean git) {
            this.git = git;
        }

        void add(String line) {
            lines.add(line);
            if (line.startsWith("--- ")) {
                sawOld = true;
                oldPath = path(line.substring(4), git, "a/");
                // svn marks the side a file is not on: added, the old side.
                if (!git && absent(line)) {
                    isNew = true;
                    oldPath = null;
                }
            } else if (line.startsWith("+++ ")) {
                sawNew = true;
                newPath = path(line.substring(4), git, "b/");
                if (!git && absent(line)) {
                    isDeleted = true;
                    newPath = null;
                }
            } else if (line.startsWith("new file mode")) {
                isNew = true;
            } else if (line.startsWith("deleted file mode")) {
                isDeleted = true;
            } else if (line.startsWith("rename from ")) {
                renameFrom = unquote(line.substring("rename from ".length()));
            } else if (line.startsWith("rename to ")) {
                renameTo = unquote(line.substring("rename to ".length()));
            } else if (line.startsWith("Binary files ") || line.equals("GIT binary patch")) {
                binary = true;
            }
        }
    }

    private FileDiff file() {
        Header h = new Header(lines[i].startsWith("diff --git "));
        h.add(lines[i++]);
        while (i < lines.length) {
            String line = lines[i];
            if (line.startsWith("@@ ") || line.startsWith("diff --git ") || line.startsWith("Index: "))
                break;
            if (line.startsWith("--- ") && h.sawOld)
                break; // the next file of a bare diff
            h.add(line);
            i++;
        }
        if (!h.sawOld && h.renameFrom != null)
            h.oldPath = h.renameFrom;
        if (!h.sawNew && h.renameTo != null)
            h.newPath = h.renameTo;
        if (h.git && !h.sawOld && !h.sawNew && h.renameFrom == null) {
            // No "---"/"+++": a mode change, or an empty or binary file.
            String[] both = gitHeaderPaths(h.lines.get(0));
            if (both != null) {
                h.oldPath = h.isNew ? null : both[0];
                h.newPath = h.isDeleted ? null : both[1];
            }
        }
        List<Hunk> hunks = new ArrayList<>();
        while (i < lines.length && lines[i].startsWith("@@ ")) {
            Hunk hunk = hunk();
            if (hunk != null)
                hunks.add(hunk);
        }
        return new FileDiff(h.lines, h.oldPath, h.newPath, h.isNew, h.isDeleted, h.binary, false, hunks);
    }

    private Hunk hunk() {
        Matcher m = HUNK.matcher(lines[i++]);
        if (!m.matches())
            return null;
        final int oldStart = Integer.parseInt(m.group(1));
        final int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        final int newStart = Integer.parseInt(m.group(3));
        final int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
        List<DiffLine> body = new ArrayList<>();
        int olds = 0;
        int news = 0;
        while (i < lines.length && (olds < oldCount || news < newCount)) {
            String line = lines[i];
            if (line.startsWith("\\")) {
                markNoNewline(body);
                i++;
                continue;
            }
            char kind = line.isEmpty() ? ' ' : line.charAt(0);
            if (kind != ' ' && kind != '-' && kind != '+')
                break; // a short hunk
            String text = line.isEmpty() ? "" : line.substring(1);
            body.add(new DiffLine(kind, text, false));
            if (kind != '+')
                olds++;
            if (kind != '-')
                news++;
            i++;
        }
        // The last line's marker comes after the counts are met.
        if (i < lines.length && lines[i].startsWith("\\")) {
            markNoNewline(body);
            i++;
        }
        return new Hunk(oldStart, oldCount, newStart, newCount, m.group(5), body);
    }

    private static void markNoNewline(List<DiffLine> body) {
        if (body.isEmpty())
            return;
        int last = body.size() - 1;
        DiffLine line = body.get(last);
        body.set(last, line.as(line.kind(), true));
    }

    private static boolean absent(String line) {
        return line.endsWith("\t(nonexistent)") || line.endsWith("\t(revision 0)");
    }

    // "--- a/x", "+++ b/x", "--- /dev/null", svn's "--- x\t(revision 5)".
    // Git ends a path with a space in it with a tab.
    private static String path(String s, boolean git, String prefix) {
        if (!git) {
            int tab = s.indexOf('\t');
            if (tab >= 0)
                s = s.substring(0, tab);
        } else if (s.endsWith("\t")) {
            s = s.substring(0, s.length() - 1);
        }
        s = unquote(s);
        if (s.equals("/dev/null"))
            return null;
        if (git && s.startsWith(prefix))
            return s.substring(prefix.length());
        return s;
    }

    // "diff --git a/x b/x" for the same path on both sides, which is all
    // a header without "---" lines has.
    private static String[] gitHeaderPaths(String line) {
        String rest = line.substring("diff --git ".length());
        if (rest.startsWith("\"")) {
            int end = closingQuote(rest);
            if (end < 0)
                return null;
            String a = unquote(rest.substring(0, end + 1));
            String b = unquote(rest.substring(end + 1).strip());
            return new String[] { strip(a, "a/"), strip(b, "b/") };
        }
        // Unquoted: the halves are equal, "a/P b/P".
        int half = (rest.length() - 1) / 2;
        if (rest.length() % 2 == 1 && rest.charAt(half) == ' ') {
            String a = rest.substring(0, half);
            String b = rest.substring(half + 1);
            if (a.startsWith("a/") && b.startsWith("b/") && a.substring(2).equals(b.substring(2)))
                return new String[] { a.substring(2), b.substring(2) };
        }
        return null;
    }

    private static String strip(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    private static int closingQuote(String s) {
        for (int k = 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\')
                k++;
            else if (c == '"')
                return k;
        }
        return -1;
    }

    /** Undoes git's C-style quoting of a path: "a\tb", octal bytes as \303\251. */
    static String unquote(String s) {
        if (s.length() < 2 || s.charAt(0) != '"' || s.charAt(s.length() - 1) != '"')
            return s;
        StringBuilder sb = new StringBuilder();
        for (int k = 1; k < s.length() - 1; k++) {
            char c = s.charAt(k);
            if (c != '\\' || k + 1 >= s.length() - 1) {
                sb.append(c);
                continue;
            }
            char e = s.charAt(++k);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'a' -> sb.append('\007');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'v' -> sb.append('\013');
                default -> {
                    if (e >= '0' && e <= '7' && k + 2 < s.length() - 1) {
                        // Bytes, which as ISO-8859-1 chars are themselves.
                        sb.append((char) Integer.parseInt(s.substring(k, k + 3), 8));
                        k += 2;
                    } else {
                        sb.append(e);
                    }
                }
            }
        }
        return sb.toString();
    }
}
