/*
 * BlameColumn.java
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
import java.awt.Graphics2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JPopupMenu;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.Dispatcher;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.Line;
import org.armedbear.j.Property;
import org.armedbear.j.util.TimeStyle;

/**
 * The blame column: each committed line a thin bar in its commit's age
 * color, and a run of one commit its entry, in vcsBlameFormat, on the run's
 * first line and on the window's top row; lines not committed yet are
 * blank. The buffer's commits are ranked by author time, the newest white
 * and the rest onto 7 steps of a cool blue-gray fading toward the
 * background, so a line's color stays as it scrolls. The entries, steps and
 * width are cached, worked out when more is read, not in paint.
 */
final class BlameColumn implements GutterColumn {
    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    static final int STEPS = 8;

    private final Buffer buffer;
    private final Blame blame;
    private final Runnable changed;

    // Recomputed on the event thread as blame streams; read in paint.
    private Map<BlameCommit, String> entries = Map.of();
    private Map<BlameCommit, Integer> steps = Map.of();
    private int width;
    // The properties the entries were made with.
    private String format;
    private int cap;
    private Color[] colors;
    private Color colorsBackground;
    private boolean told;
    private BlameData toldFailure;
    // The hashes whose messages a tool tip is waiting for.
    private final Set<String> asking = new HashSet<>();

    BlameColumn(Buffer buffer, Blame blame) {
        this.buffer = buffer;
        this.blame = blame;
        changed = () -> {
            rebuild(false);
            report();
            buffer.repaint();
        };
    }

    /** What blame tells when more is read; the column recomputes, says how it went, and repaints. */
    Runnable changed() {
        return changed;
    }

    @Override
    public void removed() {
        blame.removeListener(changed);
    }

    /** The blame read for the column. */
    Blame blame() {
        return blame;
    }

    /** Whether it has said it has blame, or why it has none: nothing left to wait for. */
    boolean told() {
        return told;
    }

    @Override
    public int width() {
        // A change to the format or the cap shows at the next paint.
        if (!buffer.getStringProperty(Property.VCS_BLAME_FORMAT).equals(format)
                || buffer.getIntegerProperty(Property.VCS_BLAME_MAX_WIDTH) != cap)
            rebuild(true);
        return width;
    }

    // The entries and steps for the blame shown. The width too when settle,
    // or nothing shows yet, or the read is done: not as each run streams in,
    // so the text doesn't shift as it widens.
    private void rebuild(boolean settle) {
        format = buffer.getStringProperty(Property.VCS_BLAME_FORMAT);
        cap = buffer.getIntegerProperty(Property.VCS_BLAME_MAX_WIDTH);
        final BlameData data = blame.data();
        if (data == null || data.isDone() && data.error() != null) {
            entries = Map.of();
            steps = Map.of();
            width = 0;
            return;
        }
        steps = ageSteps(data);
        entries = entries(format, steps.keySet(), System.currentTimeMillis(), cap);
        int widest = 0;
        for (String entry : entries.values())
            widest = Math.max(widest, entry.length());
        // One character of padding at the right.
        final int w = widest == 0 ? 0 : textLeft() + (widest + 1) * Display.getCharWidth();
        if (settle || width == 0 || data.isDone())
            width = w;
    }

    // Each failed read told; otherwise, once, that it has blame.
    private void report() {
        final BlameData failure = blame.failure();
        if (failure != null && failure != toldFailure) {
            toldFailure = failure;
            told = true;
            status("Blame failed: " + failure.error() + ": see vcsCommandLog");
            return;
        }
        final BlameData data = blame.data();
        if (!told && data != null && data.isDone() && data.hasNoHistory()) {
            told = true;
            status("No blame: not committed yet");
            return;
        }
        if (!told && data != null && (!steps.isEmpty() || data.isDone())) {
            told = true;
            status("Blamed");
        }
    }

    private static int bar() {
        return Math.max(2, Display.getCharWidth() / 4);
    }

    // Where an entry starts: after the bar and a gap.
    static int textLeft() {
        return 1 + bar() + Display.getCharWidth() / 2;
    }

    @Override
    public String getToolTipText(Line line, int x, int y) {
        final BlameData data = blame.data();
        final BlameCommit commit = data == null ? null : committed(data, line);
        if (commit == null)
            return null;
        final String message = blame.message(commit.hash());
        if (message == null)
            readMessage(commit.hash());
        final StringBuilder tip = new StringBuilder();
        tip.append(commit.hash()).append('\n');
        tip.append(commit.author()).append(" <").append(commit.authorMail()).append(">\n");
        tip.append(TIMESTAMP.format(Instant.ofEpochSecond(commit.authorTime())));
        if (commit.committerTime() != commit.authorTime())
            tip.append("  (committed ")
                    .append(TIMESTAMP.format(Instant.ofEpochSecond(commit.committerTime())))
                    .append(')');
        tip.append("\n\n");
        tip.append(message != null ? message.strip() : commit.summary());
        return tip.toString();
    }

    @Override
    public boolean isClickable(Line line, int x, int y) {
        final BlameData data = blame.data();
        return data != null && committed(data, line) != null;
    }

    @Override
    public boolean clicked(Editor editor, Line line, int x, int y) {
        final BlameData data = blame.data();
        final BlameCommit commit = data == null ? null : committed(data, line);
        if (commit == null)
            return false;
        BlameCommands.showCommit(editor, blame, commit);
        return true;
    }

    @Override
    public JPopupMenu contextMenu(Editor editor, Line line, int x, int y) {
        final BlameData data = blame.data();
        final BlameCommit commit = data == null ? null : committed(data, line);
        final JPopupMenu menu = new JPopupMenu();
        final Dispatcher d = editor.getDispatcher();
        if (commit != null) {
            AbstractMode.addContextMenuItem("Show Diff", "vcsBlameDiff", menu, d);
            AbstractMode.addContextMenuItem("Show Commit in Log", "vcsBlameShowCommit", menu, d);
            AbstractMode.addContextMenuItem("Blame This Revision", "vcsBlameRevision", menu, d);
            AbstractMode.addContextMenuItem("Blame Parent Revision", "vcsBlameParent", menu, d)
                    .setEnabled(commit.previous() != null);
            menu.addSeparator();
            AbstractMode.addContextMenuItem("Copy Hash", "vcsBlameCopyHash", menu, d);
            AbstractMode.addContextMenuItem("Copy Message", "vcsBlameCopyMessage", menu, d);
            menu.addSeparator();
        }
        AbstractMode.addContextMenuItem("Hide Blame", "vcsBlame", menu, d);
        return menu;
    }

    // The commit at line, or null if it is not committed or not known.
    private static BlameCommit committed(BlameData data, Line line) {
        final BlameCommit c = data.commit(line);
        return c == null || c.isUncommitted() ? null : c;
    }

    // The whole message read in the background, the tool tip showing the
    // summary until it comes, then shown again; asked again if it failed.
    private void readMessage(String hash) {
        if (!asking.add(hash))
            return;
        blame.readMessage(hash, m -> {
            asking.remove(hash);
            if (m != null)
                for (Editor ed : Editor.getEditorList())
                    if (ed.getBuffer() == buffer)
                        ed.getDisplay().refreshToolTip();
        });
    }

    @Override
    public void paint(Graphics2D g, Line line, int x, int y, int height, boolean top) {
        final BlameData data = blame.data();
        if (data == null)
            return;
        final BlameCommit commit = data.commit(line);
        if (commit == null || commit.isUncommitted())
            return;
        final Color color = color(commit);
        g.setColor(color);
        g.fillRect(x + 1, y, bar(), height);
        if (!top && !isRunHead(data, line))
            return;
        final String entry = entries.get(commit);
        if (entry == null || entry.isEmpty())
            return;
        final Graphics2D g2d = (Graphics2D) g.create();
        try {
            // Kept to the column: its width settles once blame is read.
            g2d.clipRect(x, y, width, height);
            // Its own font: the gutter's last drawing leaves another set.
            g2d.setFont(Display.getPlainFont());
            g2d.setColor(color);
            g2d.drawString(entry, x + textLeft(), y + g2d.getFontMetrics().getAscent());
        }
        finally {
            g2d.dispose();
        }
    }

    // Whether line starts its run: nothing before it visible, or another
    // commit. previousVisible, so a fold doesn't hide a run's annotation.
    static boolean isRunHead(BlameData data, Line line) {
        final Line previous = line.previousVisible();
        if (previous == null)
            return true;
        final BlameCommit here = data.commit(line);
        final BlameCommit before = data.commit(previous);
        if (here == before)
            return false;
        return here == null || before == null || !here.hash().equals(before.hash());
    }

    // commit's color, by its age step.
    private Color color(BlameCommit commit) {
        final Color background = buffer.getFormatter().getBackgroundColor();
        if (!background.equals(colorsBackground)) {
            colors = colors(background);
            colorsBackground = background;
        }
        return colors[steps.getOrDefault(commit, STEPS - 1)];
    }

    /**
     * The age steps' colors: the newest white, or black on a light
     * background; the rest a cool blue-gray fading toward the background.
     */
    static Color[] colors(Color background) {
        final boolean dark = DefaultTheme.isDark(background);
        final Color cool = dark ? new Color(0xA3B8D4) : new Color(0x3D5C80);
        final Color[] colors = new Color[STEPS];
        colors[0] = dark ? Color.WHITE : Color.BLACK;
        for (int s = 1; s < STEPS; s++)
            colors[s] = DefaultTheme.blend(cool, background, (s - 1) * 0.09);
        return colors;
    }

    /** The buffer's commits, ranked by author time newest first, onto their age steps. */
    static Map<BlameCommit, Integer> ageSteps(BlameData data) {
        final List<BlameCommit> ranked = data.commits()
                .stream()
                .filter(c -> !c.isUncommitted())
                .sorted(Comparator.comparingLong(BlameCommit::authorTime).reversed())
                .toList();
        final Map<BlameCommit, Integer> made = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++)
            made.put(ranked.get(i), step(i, ranked.size()));
        return made;
    }

    /** rank of n commits, newest at 0: the newest step 0, the rest spread over 1 to 7. */
    static int step(int rank, int n) {
        if (rank == 0)
            return 0;
        return Math.min(STEPS - 1, 1 + (rank - 1) * (STEPS - 2) / Math.max(1, n - 2));
    }

    /**
     * format for each of commits now, each field padded to its widest among
     * them so the next lines up; no entry wider than maxWidth characters, an
     * ellipsis past it.
     */
    static Map<BlameCommit, String> entries(
            String format,
            Collection<BlameCommit> commits,
            long nowMillis,
            int maxWidth) {
        final Map<BlameCommit, List<String>> parts = new HashMap<>();
        final List<Integer> widths = new ArrayList<>();
        for (BlameCommit c : commits) {
            final List<String> p = parts(format, c, nowMillis);
            parts.put(c, p);
            for (int i = 0; i < p.size(); i++)
                if (i == widths.size())
                    widths.add(p.get(i).length());
                else
                    widths.set(i, Math.max(widths.get(i), p.get(i).length()));
        }
        final Map<BlameCommit, String> made = new HashMap<>();
        for (Map.Entry<BlameCommit, List<String>> e : parts.entrySet()) {
            final StringBuilder entry = new StringBuilder();
            final List<String> p = e.getValue();
            for (int i = 0; i < p.size(); i++)
                entry.append(p.get(i)).append(" ".repeat(widths.get(i) - p.get(i).length()));
            final String s = entry.toString().stripTrailing();
            made.put(e.getKey(), maxWidth > 0 && s.length() > maxWidth ? s.substring(0, maxWidth - 1) + "\u2026" : s);
        }
        return made;
    }

    // format's parts for commit: the text between its fields, and each field expanded.
    private static List<String> parts(String format, BlameCommit commit, long nowMillis) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            final char c = format.charAt(i);
            if (c != '%' || i == format.length() - 1) {
                text.append(c);
                continue;
            }
            final String field = switch (format.charAt(++i)) {
                case 'd' -> SHORT_DATE.format(Instant.ofEpochSecond(commit.authorTime()));
                case 'D' -> DATE.format(Instant.ofEpochSecond(commit.authorTime()));
                case 'r' -> TimeStyle.age(commit.authorTime(), nowMillis / 1000);
                case 'a' -> lastName(commit.author());
                case 'A' -> commit.author();
                case 'e' -> mailUser(commit.authorMail());
                case 'h' -> commit.hash().substring(0, Math.min(7, commit.hash().length()));
                case 's' -> commit.summary();
                case '%' -> "%";
                default -> "%" + format.charAt(i);
            };
            parts.add(text.toString());
            parts.add(field);
            text.setLength(0);
        }
        parts.add(text.toString());
        return parts;
    }

    // The author's last name: the name after its last space.
    static String lastName(String author) {
        final String name = author.strip();
        return name.substring(name.lastIndexOf(' ') + 1);
    }

    // The user part of a mail address: before its @.
    static String mailUser(String mail) {
        final int at = mail.indexOf('@');
        return at < 0 ? mail : mail.substring(0, at);
    }

    // The editor showing the buffer, if any, told.
    private void status(String message) {
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == buffer)
                ed.status(message);
    }
}
