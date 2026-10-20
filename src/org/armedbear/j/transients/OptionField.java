/*
 * OptionField.java
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

package org.armedbear.j.transients;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.armedbear.j.CompletionPopup;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.History;
import org.armedbear.j.transients.Transient.Field;
import org.armedbear.j.transients.Transient.Option;
import org.armedbear.j.transients.Transient.Pick;

/**
 * The field an option's value is typed in, with a {@link Pick}'s candidates
 * listed under it as they match: the popup's, and the filter bar's.
 * Enter accepts the candidate highlighted, or the text when none is; Escape gives it up.
 * A checklist's ({@link Pick#several}) Enter or click checks or unchecks
 * the candidate highlighted, its value told a moment after the last, or as
 * the field closes; none is highlighted until something is typed or Up or
 * Down moves, and Enter with none closes it.
 */
final class OptionField {
    // More matches than this aren't listed: a repository's paths can be many.
    private static final int MAX_SHOWN = 1000;

    private final JTextField field;
    private CompletionPopup<String> completion;
    private List<String> candidates;
    private static final int WAIT_STEP = 250;
    private static final int WAIT_MAX = 10_000;
    private Timer waiting;
    private int waited;
    // Whether the option had a value when the field opened, which erasing clears.
    private final boolean wasSet;
    // What was typed in it before, for a Field that keeps it.
    private final History history;
    // A checklist's names checked, and what is told when they change; null for one value.
    private final List<String> checked;
    private final Consumer<String> change;
    // A checklist's value is told this long after its last change, each one reading the log again.
    static final int CHANGE_DELAY = 300;
    private final Timer changing;

    /**
     * @param accept takes the value and closes the field
     * @param change takes a checklist's value as it changes, the field kept
     */
    OptionField(
            Option o,
            Editor editor,
            String value,
            Consumer<String> accept,
            Consumer<String> change,
            Runnable escape) {
        final Pick pick = o.chooser() instanceof Pick p ? p : null;
        final boolean checklist = pick != null && pick.several();
        checked = checklist ? Pick.split(value) : null;
        this.change = change;
        changing = checklist ? new Timer(CHANGE_DELAY, e -> change.accept(Pick.join(checked))) : null;
        if (changing != null)
            changing.setRepeats(false);
        // A checklist's field narrows its candidates, its value beside them.
        field = new JTextField(value == null || checklist ? "" : value);
        wasSet = !checklist && value != null && !value.isBlank();
        history = o.chooser() instanceof Field f && f.history() != null ? new History(f.history()) : null;
        field.setFont(Display.getPlainFont());
        field.setBorder(BorderFactory.createEmptyBorder());
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                final int code = e.getKeyCode();
                if (code == KeyEvent.VK_ENTER) {
                    final String selected =
                            completion != null && completion.isShowing() ? completion.getSelected() : null;
                    if (checked != null) {
                        if (selected != null)
                            toggle(selected);
                        else {
                            flush();
                            accept.accept(Pick.join(checked));
                        }
                        e.consume();
                        return;
                    }
                    final String text = selected != null ? selected : field.getText();
                    if (history != null && !text.isBlank()) {
                        history.append(text.strip());
                        history.save();
                    }
                    accept.accept(text);
                    e.consume();
                } else if (code == KeyEvent.VK_ESCAPE) {
                    escape.run();
                    e.consume();
                } else if (completion != null
                        && completion.isShowing()
                        && (code == KeyEvent.VK_DOWN || code == KeyEvent.VK_UP)) {
                    completion.move(code == KeyEvent.VK_DOWN ? 1 : -1, true);
                    e.consume();
                } else if (history != null && (code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN)) {
                    final String s = code == KeyEvent.VK_UP ? history.getPrevious() : history.getNext();
                    if (s != null || code == KeyEvent.VK_DOWN) {
                        field.setText(s != null ? s : "");
                        if (s == null)
                            history.reset();
                    }
                    e.consume();
                }
            }
        });
        if (pick != null) {
            completion = new CompletionPopup<>(field, 10);
            completion.setOnClick(checked != null ? this::toggle : accept);
            if (pick.swatch() != null || checked != null)
                completion.setCellRenderer(
                    new CandidateRenderer(pick.swatch() == null ? null : s -> pick.swatch().apply(editor, s),
                            checked == null ? null : checked::contains));
            candidates = pick.candidates().apply(editor);
            // Still being read, as a log's names when its popup opens: asked again until they come.
            if (candidates.isEmpty()) {
                waiting = new Timer(WAIT_STEP, null);
                waiting.addActionListener(e -> {
                    candidates = pick.candidates().apply(editor);
                    if (!candidates.isEmpty() || ++waited * WAIT_STEP >= WAIT_MAX) {
                        waiting.stop();
                        if (!candidates.isEmpty() && field.isShowing())
                            filter();
                    }
                });
                waiting.start();
            }
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    filter();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    filter();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {}
            });
        }
    }

    // A checklist's candidate checked or unchecked, its names in the
    // candidates' order: the value told a moment after the last.
    void toggle(String name) {
        if (checked == null)
            return;
        if (!checked.remove(name))
            checked.add(name);
        final List<String> ordered = new ArrayList<>();
        for (String s : candidates)
            if (checked.contains(s))
                ordered.add(s);
        for (String s : checked)
            if (!ordered.contains(s))
                ordered.add(s);
        checked.clear();
        checked.addAll(ordered);
        changing.restart();
        if (completion != null)
            completion.repaint();
    }

    /** Tells a checklist's value now if a change waits to be. */
    void flush() {
        if (changing != null && changing.isRunning()) {
            changing.stop();
            change.accept(Pick.join(checked));
        }
    }

    /** A checklist's names checked, in order; null for one value. */
    List<String> checked() {
        return checked == null ? null : List.copyOf(checked);
    }

    // Each candidate after its check box, for a checklist, and a box of its
    // color, or a space as wide for none.
    private static final class CandidateRenderer extends DefaultListCellRenderer {
        private final Map<String, Color> colors = new HashMap<>();
        private final Function<String, Color> swatch;
        private final Predicate<String> checked;

        CandidateRenderer(Function<String, Color> swatch, Predicate<String> checked) {
            this.swatch = swatch;
            this.checked = checked;
        }

        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean selected,
                boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            final String s = String.valueOf(value);
            final int size = list.getFontMetrics(list.getFont()).getAscent();
            final Box box;
            if (swatch == null)
                box = null;
            else {
                final Color color = colors.computeIfAbsent(s, k -> {
                    final Color c = swatch.apply(k);
                    return c != null ? c : NONE;
                });
                box = new Box(color == NONE ? null : color, size);
            }
            final Check check = checked == null ? null : new Check(checked.test(s), getForeground(), size);
            setIcon(check == null ? box : box == null ? check : new Pair(check, box));
            setIconTextGap(list.getFontMetrics(list.getFont()).charWidth(' '));
            return this;
        }

        private static final Color NONE = new Color(0, 0, 0, 0);
    }

    // A small rounded square of a color, or room for one.
    private record Box(Color color, int size) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (color == null)
                return;
            final Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(color);
            final int inset = size / 6;
            g2d.fillRoundRect(x + inset, y + inset, size - 2 * inset, size - 2 * inset, size / 3, size / 3);
            g2d.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    // A check box, ticked or empty, in the text's color.
    private record Check(boolean on, Color color, int size) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            final Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(color);
            final int inset = size / 6;
            final int side = size - 2 * inset;
            g2d.setStroke(new BasicStroke(Math.max(1f, size / 12f)));
            g2d.drawRoundRect(x + inset, y + inset, side, side, size / 4, size / 4);
            if (on) {
                g2d.setStroke(
                    new BasicStroke(Math.max(1.5f, size / 7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                final int l = x + inset, t = y + inset;
                g2d.drawPolyline(
                    new int[] { l + side / 5, l + side * 2 / 5, l + side * 4 / 5 },
                    new int[] { t + side / 2, t + side * 3 / 4, t + side / 4 },
                    3);
            }
            g2d.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    // Two icons side by side.
    private record Pair(Icon left, Icon right) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            left.paintIcon(c, g, x, y);
            right.paintIcon(c, g, x + left.getIconWidth(), y);
        }

        @Override
        public int getIconWidth() {
            return left.getIconWidth() + right.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return Math.max(left.getIconHeight(), right.getIconHeight());
        }
    }

    /**
     * Which candidate is highlighted, for Enter to take; -1 for none, when
     * Enter takes the text. The first match is, unless the field is blank
     * after erasing a value it had, when Enter clears it.
     */
    static int highlight(String text, boolean wasSet) {
        return text.isBlank() && wasSet ? -1 : 0;
    }

    JTextField field() {
        return field;
    }

    /** Once the field is placed: its text selected, the focus in it, and the candidates listed. */
    void start() {
        field.selectAll();
        field.requestFocusInWindow();
        if (candidates != null)
            filter();
    }

    void close() {
        flush();
        if (waiting != null)
            waiting.stop();
        if (completion != null) {
            completion.hide();
            completion = null;
        }
    }

    private void filter() {
        if (completion == null)
            return;
        final int highlight = checked != null ? field.getText().isBlank() ? -1 : 0 : highlight(field.getText(), wasSet);
        completion.show(matches(candidates, field.getText(), MAX_SHOWN), highlight);
    }

    /**
     * The candidates that have text in them, ignoring case, at most max:
     * one that is text first, then those that start with it, then the rest,
     * each in their order, so that Enter on "main" takes main, not domain-x.
     */
    static List<String> matches(List<String> candidates, String text, int max) {
        final String t = text.strip();
        final List<String> exact = new ArrayList<>();
        final List<String> starting = new ArrayList<>();
        final List<String> within = new ArrayList<>();
        for (String s : candidates) {
            // One that is the text is looked for to the end: it comes first.
            if (s.equalsIgnoreCase(t))
                exact.add(s);
            else if (exact.size() + starting.size() >= max)
                continue;
            else if (s.regionMatches(true, 0, t, 0, t.length()))
                starting.add(s);
            else if (within.size() < max && containsIgnoringCase(s, t))
                within.add(s);
        }
        final List<String> out = new ArrayList<>(exact);
        out.addAll(starting);
        out.addAll(within);
        return out.size() > max ? out.subList(0, max) : out;
    }

    private static boolean containsIgnoringCase(String s, String t) {
        for (int i = 0; i + t.length() <= s.length(); i++)
            if (s.regionMatches(true, i, t, 0, t.length()))
                return true;
        return false;
    }
}
