/*
 * HunkPreview.java
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

package org.armedbear.j.vcs.signs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import org.armedbear.j.CaretPopup;
import org.armedbear.j.ChangeMarks;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.Signs;
import org.armedbear.j.UIScale;
import org.armedbear.j.mode.compare.WordDiff;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.PickState;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.vcs.changelists.RepositoryLists;

/**
 * A hunk's removed and added lines in a popup under the caret, the
 * characters that differ between paired lines marked, under a bar with
 * buttons to go to the previous or next hunk, around at the ends, stage or
 * unstage it and pick it where there is version control, or revert it, and
 * its number, and where changelists sort the file's changes, move it to
 * another. While it shows, each enabled button's key, as the change
 * popup's, does what it does: p, n, s, u, ;, m and r.
 * The editor closes it after the next command; going to another hunk shows
 * that one in its place.
 */
public final class HunkPreview extends JPanel implements CaretPopup {
    private static final int PAD = 4;

    private final Editor editor;
    private final String title;
    private final List<Row> rows;
    private final JToolBar bar = new JToolBar();
    private final List<JButton> buttons = new ArrayList<>();

    // A line of the hunk: removed or added, its text, and the ranges of it that differ.
    record Row(boolean removed, String text, List<int[]> differs) {
        @Override
        public String toString() {
            return (removed ? "-" : "+") + text;
        }
    }

    private HunkPreview(Editor editor, String title, List<Row> rows) {
        super(new BorderLayout());
        this.editor = editor;
        this.title = title;
        this.rows = rows;
        final Color bg = editor.getFormatter().getBackgroundColor();
        final Color text = editor.getFormatter().getColor(0);
        setBackground(bg);
        setBorder(BorderFactory.createLineBorder(DefaultTheme.blend(bg, text, 0.35)));
        final JLabel label = new JLabel(title);
        label.setFont(Display.getPlainFont());
        label.setForeground(text);
        label.setBorder(BorderFactory.createEmptyBorder(0, 2 * PAD, 0, PAD));
        bar.setFloatable(false);
        bar.setRollover(true);
        bar.setOpaque(false);
        bar.setBorder(null);
        final JPanel top = new JPanel(new BorderLayout());
        top.setBackground(DefaultTheme.blend(bg, text, 0.06));
        top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, DefaultTheme.blend(bg, text, 0.2)));
        top.add(bar, BorderLayout.WEST);
        top.add(label, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        add(new Lines(), BorderLayout.CENTER);
    }

    // A button on the bar, in the text color, dimmed when disabled.
    // The move's button, its menu under it.
    private JButton moveButton;

    // b, which opens a menu under it: a small triangle in its icon's corner.
    private void dropdown(JButton b) {
        moveButton = b;
        // No icon, as one that couldn't be read: no triangle either.
        if (b.getIcon() == null || b.getDisabledIcon() == null)
            return;
        final Color bg = editor.getFormatter().getBackgroundColor();
        final Color text = editor.getFormatter().getColor(0);
        b.setIcon(new Dropdown(b.getIcon(), text));
        b.setDisabledIcon(new Dropdown(b.getDisabledIcon(), DefaultTheme.blend(bg, text, 0.3)));
    }

    /** Opens the changelists the change shown can move to under the preview's button; false if it has none. */
    public static boolean showChangelistMenu(Editor editor) {
        if (!(editor.getCaretPopup() instanceof HunkPreview p) || p.moveButton == null)
            return false;
        // The preview stays, past the command that opens the menu, till
        // the menu closes, a list chosen or not.
        editor.replacingCaretPopup();
        ChangelistMenu.show(editor, p.moveButton, 0, p.moveButton.getHeight(), () -> close(editor));
        return true;
    }

    // An icon with a small downward triangle at its bottom right, as a menu's button.
    private record Dropdown(javax.swing.Icon base, Color color) implements javax.swing.Icon {
        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            base.paintIcon(c, g, x, y);
            final int w = Math.max(3, getIconWidth() / 4);
            final int left = x + getIconWidth() - w;
            final int top = y + getIconHeight() - w / 2 - 1;
            g.setColor(color);
            g.fillPolygon(new int[] { left, left + w, left + w / 2 }, new int[] { top, top, top + w / 2 + 1 }, 3);
        }

        @Override
        public int getIconWidth() {
            return base.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return base.getIconHeight();
        }
    }

    private JButton button(String icon, String command, char key, String tip, boolean enabled) {
        final Color bg = editor.getFormatter().getBackgroundColor();
        final Color text = editor.getFormatter().getColor(0);
        final int size = UIScale.scale(Icons.ICON_SIZE);
        final JButton b = new JButton(Icons.getIconFromFile(icon, size, text));
        b.setDisabledIcon(Icons.getIconFromFile(icon, size, DefaultTheme.blend(bg, text, 0.3)));
        b.setToolTipText(tip + " (" + key + ")");
        if (enabled)
            keys.mapKey(key, command);
        b.setActionCommand(command);
        b.setEnabled(enabled);
        // The keys stay with the editor.
        b.setFocusable(false);
        b.setMargin(new Insets(2, 2, 2, 2));
        // Flat, its border only under the mouse, as j's toolbar's.
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.getModel().addChangeListener(e -> b.setBorderPainted(b.isEnabled() && b.getModel().isRollover()));
        b.addActionListener(
            e -> editor.getDispatcher()
                    .actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, command)));
        bar.add(b);
        buttons.add(b);
        return b;
    }

    /**
     * Shows a hunk at the caret, its old lines and its new: the version
     * control's, staged or not, or (vcs false) j's own since the file was
     * loaded.
     */
    public static void show(Editor editor, boolean vcs, boolean staged, List<String> old, List<String> now) {
        close(editor);
        if (old.isEmpty() && now.isEmpty())
            return;
        final int tabWidth = editor.getBuffer().getTabWidth();
        final List<Row> rows = new ArrayList<>();
        final int pairs = Math.min(old.size(), now.size());
        final List<WordDiff.Result> diffs = new ArrayList<>();
        for (int k = 0; k < pairs; k++)
            diffs.add(WordDiff.chars(detab(old.get(k), tabWidth), detab(now.get(k), tabWidth)));
        for (int k = 0; k < old.size(); k++)
            rows.add(new Row(true, detab(old.get(k), tabWidth), k < pairs ? diffs.get(k).removed() : List.of()));
        for (int k = 0; k < now.size(); k++)
            rows.add(new Row(false, detab(now.get(k), tabWidth), k < pairs ? diffs.get(k).added() : List.of()));
        // Numbered among the hunks ]c and [c go to.
        final List<Line> starts = ChangeMarks.hunkStarts(editor);
        final int at = editor.getDotLineNumber();
        int number = 0;
        for (Line l : starts)
            if (l.lineNumber() <= at)
                number++;
        final boolean others = starts.size() > 1;
        final String title =
                "Hunk " + Math.max(1, number) + " of " + Math.max(1, starts.size()) + (staged ? " (staged)" : "");
        final HunkPreview p = new HunkPreview(editor, title, rows);
        p.button("up", "previousChange", 'p', "Previous hunk", others);
        p.button("down", "nextChange", 'n', "Next hunk", others);
        if (staged)
            p.button("minus", "vcsUnstageHunk", 'u', "Unstage hunk", true);
        else if (vcs) {
            p.button("plus", "vcsStageHunk", 's', "Stage hunk", true);
            final SignsUpdater u = editor.getBuffer().getSignsUpdater();
            final PickState picked = u == null ? PickState.NONE : u.pickStateAt(editor.getDotLineNumber());
            p.button(switch (picked) {
                case ALL -> "pick-button-on";
                case SOME -> "pick-button-some";
                case NONE -> "pick-button-off";
            },
                "vcsPickHunk",
                ';',
                picked == PickState.ALL ? "Unpick hunk in the status" : "Pick hunk in the status",
                true);
            // Where changelists sort the file's changes, to another.
            if (u != null && RepositoryLists.shown(u.getRepository()) != null)
                p.dropdown(p.button("changelist", "vcsChangelistMenu", 'm', "Move to changelist…", true));
        }
        p.button("undo", "revertLines", 'r', "Revert hunk", !staged);
        editor.showCaretPopup(p);
        p.attach();
    }

    // Its enabled buttons' keys.
    private final KeyMap keys = new KeyMap();

    @Override
    public KeyMap getKeyMap() {
        return keys;
    }

    /** Closes editor's preview, if it shows one. */
    public static void close(Editor editor) {
        if (showing(editor))
            editor.closeCaretPopup();
    }

    /** Whether editor shows a preview. */
    public static boolean showing(Editor editor) {
        return editor.getCaretPopup() instanceof HunkPreview;
    }

    private void attach() {
        final JRootPane root = SwingUtilities.getRootPane(editor);
        if (root == null)
            return; // Headless.
        root.getLayeredPane().add(this, JLayeredPane.POPUP_LAYER);
        place();
    }

    @Override
    public void removed() {
        final Container parent = getParent();
        if (parent != null) {
            final Rectangle bounds = getBounds();
            parent.remove(this);
            parent.repaint(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }

    @Override
    public void scrolled() {
        if (getParent() != null)
            place();
    }

    // Under the caret's line, or over it when there is more room there; out
    // of sight while the line is.
    private void place() {
        final Display display = editor.getDisplay();
        final int lineTop = display.getLineTop(editor.getDotLine());
        setVisible(lineTop >= 0);
        if (lineTop < 0)
            return;
        final int ch = Display.getCharHeight();
        final Dimension wanted = getPreferredSize();
        final int gutter = Display.getGutterWidth(editor.getBuffer());
        final int width = Math.min(wanted.width, Math.max(0, display.getWidth() - gutter));
        final int below = display.getHeight() - (lineTop + ch);
        final boolean under = below >= wanted.height || below >= lineTop;
        final int height = Math.min(wanted.height, Math.max(0, under ? below : lineTop));
        final Point p =
                SwingUtilities.convertPoint(display, gutter, under ? lineTop + ch : lineTop - height, getParent());
        setBounds(p.x, p.y, width, height);
        revalidate();
        repaint();
    }

    // The hunk's lines, on red and green, the characters that differ deeper.
    private final class Lines extends JComponent {
        @Override
        public Dimension getPreferredSize() {
            final FontMetrics fm = Utilities.getFontMetrics(Display.getPlainFont());
            int width = 0;
            for (Row r : rows)
                width = Math.max(width, fm.stringWidth(r.toString()));
            return new Dimension(width + 2 * PAD, rows.size() * Display.getCharHeight() + 2 * PAD);
        }

        @Override
        protected void paintComponent(Graphics g) {
            final Graphics2D g2d = (Graphics2D) g;
            Display.setRenderingHints(g2d);
            final Color bg = editor.getFormatter().getBackgroundColor();
            final Color text = editor.getFormatter().getColor(0);
            final FontMetrics fm = Utilities.getFontMetrics(Display.getPlainFont());
            g2d.setFont(Display.getPlainFont());
            final int ch = Display.getCharHeight();
            for (int i = 0; i < rows.size(); i++) {
                final Row r = rows.get(i);
                final Color color = Signs.color(r.removed() ? Signs.Kind.DELETED : Signs.Kind.ADDED, bg);
                final int y = PAD + i * ch;
                g2d.setColor(DefaultTheme.blend(bg, color, 0.15));
                g2d.fillRect(0, y, getWidth(), ch);
                final String s = r.toString();
                g2d.setColor(DefaultTheme.blend(bg, color, 0.45));
                for (int[] d : r.differs()) {
                    final int x = PAD + fm.stringWidth(s.substring(0, d[0] + 1));
                    g2d.fillRect(x, y, fm.stringWidth(s.substring(d[0] + 1, d[1] + 1)), ch);
                }
                g2d.setColor(text);
                g2d.drawString(s, PAD, y + fm.getAscent());
            }
        }
    }

    // Tabs as spaces to the buffer's stops, so columns line up as in it.
    private static String detab(String s, int tabWidth) {
        if (s.indexOf('\t') < 0 || tabWidth <= 0)
            return s;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\t')
                do
                    sb.append(' ');
                while (sb.length() % tabWidth != 0);
            else
                sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    // For tests.

    private static HunkPreview of(Editor editor) {
        return editor.getCaretPopup() instanceof HunkPreview p ? p : null;
    }

    static String title(Editor editor) {
        final HunkPreview p = of(editor);
        return p == null ? null : p.title;
    }

    /** Its buttons' commands, the disabled ones in parentheses. */
    static List<String> buttons(Editor editor) {
        final List<String> out = new ArrayList<>();
        final HunkPreview p = of(editor);
        if (p != null)
            for (JButton b : p.buttons)
                out.add(b.isEnabled() ? b.getActionCommand() : "(" + b.getActionCommand() + ")");
        return out;
    }

    static List<Row> rows(Editor editor) {
        final HunkPreview p = of(editor);
        return p == null ? List.of() : p.rows;
    }
}
