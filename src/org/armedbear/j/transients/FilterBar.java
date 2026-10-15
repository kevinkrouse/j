/*
 * FilterBar.java
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

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JTextField;
import org.armedbear.j.Buffer;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.transients.Transient.Cycle;
import org.armedbear.j.transients.Transient.Option;
import org.armedbear.j.util.Utilities;

/**
 * A strip over a window of the filters on its view, as the log's branch and
 * author, from {@code Mode.getFilters}: each a chip, its popup key in bold,
 * "-b branch: master ▾", or dimmed "-a author ▾" when unset. A click on a
 * chip picks its value, as the popup does, and one on its "×" clears it.
 * It never takes the focus but for the field a value is typed in.
 */
public final class FilterBar extends JComponent {
    private static final int PAD = 3;

    private final Editor editor;
    private List<Chip> chips = List.of();
    private OptionField optionField;
    private Option fieldOption;

    // A filter as drawn: its key, then the chip, then "×" when set.
    private record Chip(Option option, int keyX, int x, int width, int clearX, String text, boolean set) {}

    public FilterBar(Editor editor) {
        this.editor = editor;
        setLayout(null);
        setFocusable(false);
        setToolTipText("");
        final MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1)
                    clicked(e.getX());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                final boolean on = chipAt(e.getX()) != null;
                setCursor(Cursor.getPredefinedCursor(on ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Lays the chips out again from the mode's filters and their values. */
    public void refresh() {
        final List<Option> options = editor.getMode().getFilters(editor);
        final Font plain = Display.getPlainFont();
        final FontMetrics fm = Utilities.getFontMetrics(plain);
        final FontMetrics bfm = Utilities.getFontMetrics(plain.deriveFont(Font.BOLD));
        final int cw = Display.getCharWidth();
        final List<Chip> out = new ArrayList<>();
        int x = cw;
        for (Option o : options == null ? List.<Option>of() : options) {
            final String value = o.binding().get(editor);
            final boolean set = value != null && !value.isEmpty();
            final String text = set ? o.description() + ": " + value + " ▾" : o.description() + " ▾";
            final int keyX = x;
            x += bfm.stringWidth(o.key()) + cw / 2;
            final int width = fm.stringWidth(text) + cw;
            final int clearX = set ? x + width + cw / 2 : -1;
            out.add(new Chip(o, keyX, x, width, clearX, text, set));
            x += width + (set ? cw / 2 + fm.stringWidth("×") : 0) + 2 * cw;
        }
        chips = out;
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(super.getPreferredSize().width, Display.getCharHeight() + 2 * PAD);
    }

    private Formatter formatter() {
        return editor.getFormatter();
    }

    @Override
    protected void paintComponent(Graphics g) {
        final Graphics2D g2d = (Graphics2D) g;
        Display.setRenderingHints(g2d);
        final Formatter f = formatter();
        final Color bg = f.getBackgroundColor();
        final Color text = f.getColor(0);
        g2d.setColor(DefaultTheme.blend(bg, text, 0.04));
        g2d.fillRect(0, 0, getWidth(), getHeight());
        g2d.setColor(DefaultTheme.blend(bg, text, 0.2));
        g2d.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
        final Font plain = Display.getPlainFont();
        final int ascent = Utilities.getFontMetrics(plain).getAscent();
        final int ch = Display.getCharHeight();
        final int cw = Display.getCharWidth();
        for (Chip c : chips) {
            g2d.setFont(plain.deriveFont(Font.BOLD));
            g2d.setColor(f.getHoverLinkColor());
            g2d.drawString(c.option().key(), c.keyX(), PAD + ascent);
            if (c.option() == fieldOption)
                continue;
            Display.fillChip(g2d, DefaultTheme.blend(bg, text, c.set() ? 0.14 : 0.07), c.x(), PAD, c.width(), ch);
            g2d.setFont(plain);
            g2d.setColor(c.set() ? text : DefaultTheme.blend(bg, text, 0.5));
            g2d.drawString(c.text(), c.x() + cw / 2, PAD + ascent);
            if (c.set()) {
                g2d.setColor(DefaultTheme.blend(bg, text, 0.5));
                g2d.drawString("×", c.clearX(), PAD + ascent);
            }
        }
    }

    // Clicks.

    private Chip chipAt(int x) {
        for (Chip c : chips)
            if (x >= c.keyX() && x < c.x() + c.width() || c.set() && x >= c.clearX() && x < c.clearX() + clearWidth())
                return c;
        return null;
    }

    private static int clearWidth() {
        return Utilities.getFontMetrics(Display.getPlainFont()).stringWidth("×");
    }

    /** A click at x: a chip picks its value, its "×" clears it. */
    void clicked(int x) {
        final Chip c = chipAt(x);
        if (c == null)
            return;
        if (c.set() && x >= c.clearX())
            set(c.option(), "");
        else
            choose(c.option());
    }

    /** Picks option's value: the next of a cycle, else typed in a field over its chip. */
    public void choose(Option o) {
        closeField();
        if (o.chooser() instanceof Cycle cycle) {
            final List<String> values = cycle.values();
            final int i = values.indexOf(o.binding().get(editor));
            set(o, values.get((i + 1) % values.size()));
            return;
        }
        Chip chip = null;
        for (Chip c : chips)
            if (c.option() == o)
                chip = c;
        if (chip == null)
            return;
        fieldOption = o;
        optionField = new OptionField(o, editor, o.binding().get(editor), this::acceptField, this::escape);
        final JTextField field = optionField.field();
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!e.isTemporary())
                    closeField();
            }
        });
        final int cw = Display.getCharWidth();
        field.setBounds(chip.x(), PAD, Math.max(chip.width(), 30 * cw), Display.getCharHeight());
        add(field);
        repaint();
        optionField.start();
    }

    /** Sets the filter whose field is open to value, and closes the field. */
    public void acceptField(String value) {
        final Option o = fieldOption;
        closeField();
        if (o != null)
            set(o, value == null ? "" : value.strip());
        editor.setFocusToDisplay();
    }

    private void escape() {
        closeField();
        editor.setFocusToDisplay();
    }

    private void closeField() {
        if (optionField == null)
            return;
        final OptionField f = optionField;
        optionField = null;
        fieldOption = null;
        f.close();
        // Removing a focused field sends the focus to the next component,
        // as a location bar, which would take it for an instant.
        if (f.field().isFocusOwner())
            editor.setFocusToDisplay();
        remove(f.field());
        repaint();
    }

    // Sets a filter, then every window on the buffer shows it.
    private void set(Option o, String value) {
        o.binding().set(editor, value);
        refreshAll(editor.getBuffer());
    }

    /** What a change to buffer's values asks of every window on it: its filter bar laid out again, and its text shown. */
    public static void refreshAll(Buffer buffer) {
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == buffer) {
                ed.updateFilterBar();
                ed.updateDisplay();
            }
    }

    /** The field a value is being typed in, or null. */
    public JTextField getField() {
        return optionField == null ? null : optionField.field();
    }

    /** Where option's chip is drawn, for tests to click. */
    public Rectangle boundsOf(Option o) {
        for (Chip c : chips)
            if (c.option() == o)
                return new Rectangle(c.x(), PAD, c.width(), Display.getCharHeight());
        return null;
    }

    /** Where option's "×" is drawn, or null when it is unset. */
    public Rectangle clearBoundsOf(Option o) {
        for (Chip c : chips)
            if (c.option() == o && c.set())
                return new Rectangle(c.clearX(), PAD, clearWidth(), Display.getCharHeight());
        return null;
    }

    /** The chips as text, for tests: "-b branch ▾", "-a author: kk ▾ ×". */
    public List<String> text() {
        final List<String> out = new ArrayList<>();
        for (Chip c : chips)
            out.add(c.option().key() + " " + c.text() + (c.set() ? " ×" : ""));
        return out;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        final Chip c = chipAt(e.getX());
        if (c == null)
            return null;
        return c.set() && e.getX() >= c.clearX() ? "Clear " + c.option().description() : c.option().name();
    }
}
