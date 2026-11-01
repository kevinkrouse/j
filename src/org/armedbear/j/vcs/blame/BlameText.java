/*
 * BlameText.java
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

package org.armedbear.j.vcs.blame;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.FoldCommands;
import org.armedbear.j.Formatter;
import org.armedbear.j.Line;
import org.armedbear.j.LocalTag;
import org.armedbear.j.Preferences;
import org.armedbear.j.Property;
import org.armedbear.j.TextStyle;
import org.armedbear.j.VirtualText;
import org.armedbear.j.mode.web.WebBuffer;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.TimeStyle;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;

/**
 * The blame shown as text after a line's end, for the buffer the caret is
 * in: the caret line's own blame once it rests, and the authors of each
 * method and class after its first line. One for the app, on the event
 * thread, following the current editor.
 */
public final class BlameText {
    static final String OWNER = "vcs.blame.line";
    static final String AUTHORS_OWNER = "vcs.blame.authors";
    // How long the caret rests before the text shows.
    static final int DELAY_MILLIS = 750;
    // Its color, this far from the background toward the text.
    private static final double SHADE = 0.35;
    // The spaces after the line's end, besides the one EOL text has.
    private static final String GAP = "  ";
    private static final String NOT_COMMITTED = "Not committed yet";

    private static final Timer timer = new Timer(DELAY_MILLIS, e -> show());
    // Where the caret last rested, as show() found it: what viewChanged
    // compares against. Null while a rest is awaited.
    private static Editor restedOn;
    private static Line restedLine;
    private static int restedModCount;
    // The line blame shown, the buffer whose layer holds it and its line.
    private static Buffer shown;
    private static Line shownLine;
    private static String shownText;
    private static Blame listened;
    // Per buffer, weakly: a buffer closed is let go. What its authors were
    // last built from, and the identity read for them.
    private static final Map<Buffer, Built> built = new WeakHashMap<>();
    private static final Map<Buffer, Prepared> prepared = new WeakHashMap<>();
    // The buffer whose tags and identity are being read now.
    private static Buffer preparingFor;
    private static final Runnable listener = BlameText::redraw;

    static {
        timer.setRepeats(false);
        final Preferences p = Editor.preferences();
        if (p != null)
            p.addPreferencesChangeListener(BlameText::preferencesChanged);
    }

    private BlameText() {}

    /** The caret, buffer or scroll may have changed: keep the text where the caret rests. */
    public static void viewChanged(Editor editor) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> viewChanged(editor));
            return;
        }
        if (editor == null || editor != Editor.currentEditor())
            return;
        final Buffer buffer = editor.getBuffer();
        if (buffer == null || editor.getDot() == null)
            return;
        final Line dot = editor.getDotLine();
        // The caret resting where it was, the text unchanged: leave what is shown.
        if (editor == restedOn && dot == restedLine && buffer.getModCount() == restedModCount)
            return;
        clearText();
        restedOn = null;
        restedLine = null;
        // Another buffer: the one left stops being read now, not at the next rest.
        if (listened != null && buffer.getBlame() != listened)
            stopListening();
        // Only a window on screen: a test's editor runs no blame.
        if (editor.getDisplay().isShowing())
            timer.restart();
    }

    // The caret rested: show its line's blame and the buffer's authors, or give up.
    static void show() {
        final Editor editor = Editor.currentEditor();
        if (editor == null || editor.getBuffer() == null) {
            giveUp();
            return;
        }
        final Buffer buffer = editor.getBuffer();
        final Line dot = editor.getDot() == null ? null : editor.getDotLine();
        restedOn = editor;
        restedLine = dot;
        restedModCount = buffer.getModCount();
        final boolean line = lineBlameWanted(buffer);
        final boolean authors = authorsWanted(buffer);
        if (dot == null || (!line && !authors)) {
            clearAuthorsOn(buffer);
            giveUp();
            return;
        }
        listen(buffer);
        if (line)
            draw(editor, buffer, dot);
        else
            clearText();
        if (authors)
            buildAuthors(editor, buffer);
        else
            clearAuthorsOn(buffer);
    }

    // The blame streamed more: redraw if still resting where the text is.
    private static void redraw() {
        final Editor editor = restedOn;
        if (editor == null || editor != Editor.currentEditor() || editor.getDot() == null)
            return;
        final Buffer buffer = editor.getBuffer();
        if (buffer == null)
            return;
        if (lineBlameWanted(buffer) && editor.getDotLine() == restedLine)
            draw(editor, buffer, restedLine);
        if (authorsWanted(buffer))
            buildAuthors(editor, buffer);
    }

    private static void draw(Editor editor, Buffer buffer, Line line) {
        final Blame blame = listened;
        final BlameData data = blame == null ? null : blame.data();
        if (data == null || data.hasNoHistory()) {
            clearText();
            return;
        }
        final BlameCommit commit = data.commit(line);
        if (commit == null) {
            clearText();
            return;
        }
        final String text = text(commit);
        // More streamed in, the line's the same: nothing to repaint.
        if (buffer == shown && line == shownLine && text.equals(shownText))
            return;
        clearText();
        final Color color = color(editor);
        // No action: a click past the line's end moves the caret.
        final VirtualText.Run run =
                new VirtualText.Run(text, color, TextStyle.PLAIN, null, commit.isUncommitted() ? null : commit.hash());
        shown = buffer;
        shownLine = line;
        shownText = text;
        // Priority 1: drawn after the authors' entry, which is nearer the text.
        buffer.getVirtualText()
                .set(OWNER, List.of(VirtualText.eol(line, List.of(VirtualText.Run.of(GAP), run)).withPriority(1)));
    }

    private static String text(BlameCommit commit) {
        if (commit.isUncommitted())
            return NOT_COMMITTED;
        final long now = System.currentTimeMillis() / 1000;
        return commit.author() + ", " + TimeStyle.ago(commit.authorTime(), now) + " \u00b7 " + commit.summary();
    }

    // The authors of the buffer's methods and classes, built on the event
    // thread once its tags and the user's identity are to hand.
    private static void buildAuthors(Editor editor, Buffer buffer) {
        final Blame blame = listened;
        final BlameData data = blame == null ? null : blame.data();
        // Still being read, as on coming back to the buffer: what is shown
        // stays. A count from part of the stream would be wrong.
        if (data == null || !data.isDone())
            return;
        if (data.hasNoHistory() || buffer.getMode() == null || buffer.getMode().getTagger(buffer) == null) {
            clearAuthorsOn(buffer);
            return;
        }
        final VcsRepository repo = blame.repository();
        final List<LocalTag> tags = buffer.getTags();
        final Prepared p = prepared.get(buffer);
        if (p != null && p.stamp() == repo.backend().identityStamp(repo) && tags != null) {
            applyAuthors(editor, buffer, data, tags, p.identity());
            return;
        }
        if (preparingFor != buffer)
            prepareAuthors(buffer, repo);
    }

    // The user's identity as read for a buffer, and the backend's stamp it
    // was good for; kept so a rest that changed nothing runs no thread.
    private record Prepared(VcsBackend.Identity identity, long stamp) {}

    // What the background read: the identity, and the modCount the tags
    // were read at.
    private record Read(VcsBackend.Identity identity, int modCount) {}

    // The tags and the user's identity, read off the event thread; then
    // built if this buffer is still the one looked at and the caret rests.
    private static void prepareAuthors(Buffer buffer, VcsRepository repo) {
        preparingFor = buffer;
        final long stamp = repo.backend().identityStamp(repo);
        Background.then("vcs blame authors", () -> {
            buffer.readTags();
            return new Read(repo.backend().identity(repo), buffer.getModCount());
        }, read -> {
            if (preparingFor == buffer)
                preparingFor = null;
            final Editor cur = Editor.currentEditor();
            if (read == null || cur == null || cur.getBuffer() != buffer || !authorsWanted(buffer))
                return;
            prepared.put(buffer, new Prepared(read.identity(), stamp));
            // Still being edited: the next rest builds.
            if (buffer.getModCount() != restedModCount)
                return;
            // The tagger gave none for this text: don't read again.
            if (buffer.getTags() == null && buffer.getModCount() == read.modCount()) {
                clearAuthorsOn(buffer);
                return;
            }
            // Built now, or the tags read again if an edit cleared them
            // after the caret came to rest.
            buildAuthors(cur, buffer);
        });
    }

    // What a buffer's entries were built from, so a redraw that changed
    // nothing builds no more.
    private record Built(int modCount, BlameData data, VcsBackend.Identity identity, Color color) {}

    // The entries set on the buffer: one per method and class, at the end
    // of its first line, with the hover and the click on the run.
    private static void applyAuthors(
            Editor editor,
            Buffer buffer,
            BlameData data,
            List<LocalTag> tags,
            VcsBackend.Identity identity) {
        final Color color = color(editor);
        final Built b = new Built(buffer.getModCount(), data, identity, color);
        if (b.equals(built.get(buffer)))
            return;
        final long now = System.currentTimeMillis() / 1000;
        final List<VirtualText> list = new ArrayList<>();
        for (LocalTag tag : tags) {
            if (!tag.isGlobal())
                continue;
            final Line first = tag.getLine();
            if (first == null)
                continue;
            final Authors a = authors(data, first, rangeLast(buffer, first), identity, now);
            if (a == null)
                continue;
            final VirtualText.Run run =
                    new VirtualText.Run(a.entry(), color, TextStyle.PLAIN, clickAction(first), a.tooltip());
            list.add(VirtualText.eol(first, List.of(VirtualText.Run.of(GAP), run)));
        }
        buffer.getVirtualText().set(AUTHORS_OWNER, list);
        built.put(buffer, b);
    }

    // A range's authors: the entry after its first line, and the hover
    // listing them, as HTML.
    record Authors(String entry, String tooltip) {}

    /**
     * The authors of the lines from first to last, as data gives them.
     * Committed lines count for their author, the user's own (by mail
     * address) under the user's name; lines not committed yet count for
     * the user, if known, and mark the entry with a `*` (with no identity
     * they count for no one and only give the `*`). The entry is the
     * author of the most lines, last name, plus ` +N` for the others.
     * Null when no line has a commit to count.
     */
    static Authors authors(BlameData data, Line first, Line last, VcsBackend.Identity user, long now) {
        // The user's name as the entry gives it: theirs, or as their
        // commits name them.
        String userName = user == null ? null : user.name();
        // Each author's committed lines and latest change.
        final Map<String, long[]> byName = new LinkedHashMap<>();
        int uncommitted = 0;
        for (Line line = first; line != null && line.lineNumber() <= last.lineNumber(); line = line.next()) {
            final BlameCommit c = data.commit(line);
            if (c == null)
                continue;
            if (c.isUncommitted()) {
                uncommitted++;
                continue;
            }
            String name = c.author();
            if (isUser(c, user)) {
                if (userName == null)
                    userName = name;
                name = userName;
            }
            final long[] e = byName.computeIfAbsent(name, k -> new long[2]);
            e[0]++;
            e[1] = Math.max(e[1], c.authorTime());
        }
        // The user is an author only when they have uncommitted lines.
        final List<String> candidates = new ArrayList<>(byName.keySet());
        if (userName != null && uncommitted > 0 && !byName.containsKey(userName))
            candidates.add(userName);
        if (candidates.isEmpty())
            return uncommitted > 0 ? new Authors("*", hover(byName, uncommitted, now)) : null;
        // The author of the most lines, the user's uncommitted lines
        // counted for them; ties to the latest change.
        String top = null;
        long topLines = -1;
        long topLatest = -1;
        for (String name : candidates) {
            final long[] e = byName.getOrDefault(name, new long[2]);
            final long lines = e[0] + (name.equals(userName) ? uncommitted : 0);
            if (lines > topLines || (lines == topLines && e[1] > topLatest)) {
                top = name;
                topLines = lines;
                topLatest = e[1];
            }
        }
        final int others = candidates.size() - 1;
        final String entry =
                BlameColumn.lastName(top) + (uncommitted > 0 ? "*" : "") + (others > 0 ? " +" + others : "");
        return new Authors(entry, hover(byName, uncommitted, now));
    }

    // Whether the user made commit: the same mail address, as blame and
    // the identity both give it through the mailmap.
    private static boolean isUser(BlameCommit commit, VcsBackend.Identity user) {
        return user != null && user.email() != null && user.email().equalsIgnoreCase(commit.authorMail());
    }

    // Every author newest first, then the uncommitted lines: a table of
    // names, counts and, for committed ones, ages, so they line up. HTML,
    // escaped: an author's name is anyone's to choose.
    private static String hover(Map<String, long[]> byName, int uncommitted, long now) {
        final List<Map.Entry<String, long[]>> sorted = new ArrayList<>(byName.entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed());
        final StringBuilder sb = new StringBuilder("<html><table cellspacing=0 cellpadding=0>");
        for (Map.Entry<String, long[]> e : sorted)
            row(sb, e.getKey(), e.getValue()[0], TimeStyle.ago(e.getValue()[1], now));
        if (uncommitted > 0)
            row(sb, NOT_COMMITTED, uncommitted, null);
        return sb.append("</table></html>").toString();
    }

    private static void row(StringBuilder sb, String name, long lines, String ago) {
        sb.append("<tr><td>").append(Display.escapeHtml(name));
        sb.append("</td><td align=right>&nbsp;&nbsp;").append(lines);
        sb.append("</td><td>&nbsp;").append(lines == 1 ? "line" : "lines");
        if (ago != null)
            sb.append(", ").append(Display.escapeHtml(ago));
        sb.append("</td></tr>");
    }

    // The last line of the run the tag heads: its fold through a closing
    // brace at the tag's indent, as the gutter's fold extent; the tag's
    // line alone when there is nothing to fold. An opening brace on a line
    // of its own, at the tag's indent, heads the fold in its place.
    private static Line rangeLast(Buffer buffer, Line first) {
        Line head = first;
        Line[] below = buffer.getFolds().getFoldBelow(buffer, head);
        final Line next = first.next();
        if (below == null
                && next != null
                && next.getText().strip().equals("{")
                && buffer.getIndentation(next) == buffer.getIndentation(first)) {
            head = next;
            below = buffer.getFolds().getFoldBelow(buffer, head);
        }
        if (below == null || below.length != 2)
            return head;
        return FoldCommands.throughClosing(buffer, head, below[1]);
    }

    // The click: the column on, the caret on the method's first line, the
    // method in view; or, with the column on, the column off. The editor
    // the click lands in, not the one built for, and the method's extent
    // as it is now: an edit since the build may have moved its end.
    private static Runnable clickAction(Line first) {
        return () -> {
            final Editor editor = Editor.currentEditor();
            if (editor == null || editor.getBuffer() == null)
                return;
            final Buffer buffer = editor.getBuffer();
            final boolean on = BlameCommands.hasColumn(buffer);
            BlameCommands.vcsBlame(editor);
            if (on)
                return;
            editor.moveDotTo(first, 0);
            editor.getDisplay().centerRegion(first, rangeLast(buffer, first));
            viewChanged(editor);
        };
    }

    private static Color color(Editor editor) {
        final Formatter f = editor.getFormatter();
        return DefaultTheme.blend(f.getBackgroundColor(), f.getColor(0), SHADE);
    }

    private static boolean common(Buffer buffer) {
        return Blame.canBlame(buffer)
                && !(buffer instanceof WebBuffer)
                && buffer.getLineCount() <= buffer.getIntegerProperty(Property.VCS_BLAME_MAX_LINES);
    }

    private static boolean lineBlameWanted(Buffer buffer) {
        return buffer.getBooleanProperty(Property.VCS_LINE_BLAME) && common(buffer);
    }

    private static boolean authorsWanted(Buffer buffer) {
        return buffer.getBooleanProperty(Property.VCS_CODE_AUTHORS) && common(buffer);
    }

    // Listening moves with the caret: a buffer no longer looked at stops
    // being read, unless the column listens too.
    private static void listen(Buffer buffer) {
        final Blame blame = Blame.of(buffer);
        if (blame == listened)
            return;
        stopListening();
        if (blame != null) {
            listened = blame;
            blame.addListener(listener);
        }
    }

    private static void stopListening() {
        if (listened != null) {
            listened.removeListener(listener);
            listened = null;
        }
    }

    private static void giveUp() {
        clearText();
        stopListening();
    }

    private static void clearText() {
        if (shown != null) {
            shown.getVirtualText().clear(OWNER);
            shown = null;
        }
        shownLine = null;
        shownText = null;
    }

    // The buffer's authors gone; another buffer's are left where they are.
    private static void clearAuthorsOn(Buffer buffer) {
        buffer.getVirtualText().clear(AUTHORS_OWNER);
        built.remove(buffer);
    }

    // The preferences changed: the line's blame and every buffer's authors
    // no longer wanted go now, not when the caret next rests.
    static void preferencesChanged() {
        SwingUtilities.invokeLater(() -> {
            if (shown != null && !lineBlameWanted(shown))
                clearText();
            for (Buffer b : List.copyOf(built.keySet()))
                if (!authorsWanted(b))
                    clearAuthorsOn(b);
        });
    }

    // Forget where the caret rested and what is shown, so a test starts clean.
    static void reset() {
        timer.stop();
        clearText();
        for (Buffer b : List.copyOf(built.keySet()))
            clearAuthorsOn(b);
        stopListening();
        restedOn = null;
        restedLine = null;
        prepared.clear();
        preparingFor = null;
    }
}
