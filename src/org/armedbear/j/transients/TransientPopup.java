/*
 * TransientPopup.java
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
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.armedbear.j.CommandTable;
import org.armedbear.j.CompletionPopup;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.Help;
import org.armedbear.j.Log;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Cycle;
import org.armedbear.j.transients.Transient.Field;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.Transient.Item;
import org.armedbear.j.transients.Transient.Option;
import org.armedbear.j.transients.Transient.Pick;
import org.armedbear.j.transients.Transient.Prefix;
import org.armedbear.j.transients.Transient.Switch;
import org.armedbear.j.util.Utilities;

/**
 * A {@link Transient} in a box over the bottom of the window it was opened
 * from, in the window's popup layer: no location bar, no status bar. It
 * takes the keys until a command runs or Escape closes it, the same keys in
 * either edit mode; a click does what an item's key does.
 */
public final class TransientPopup extends JComponent {
    private static final int MAX_COLUMNS = 100;
    private static final int PAD = 4;

    // The one open, if any.
    private static TransientPopup open;

    private final Editor editor;
    private final Deque<Transient> back = new ArrayDeque<>();
    private Transient current;
    // The first character of a two character key, or "".
    private String pending = "";
    // Ctrl H was pressed: the next key shows its item's help.
    private boolean helpNext;
    private String message;

    // An option's value being typed.
    private JTextField field;
    // Focus leaving the field for somewhere else closes the popup.
    private final FocusAdapter fieldFocus = new FocusAdapter() {
        @Override
        public void focusLost(FocusEvent e) {
            if (!e.isTemporary() && !isInside(e.getOppositeComponent()))
                close(false);
        }
    };
    private Option fieldOption;
    private CompletionPopup<String> completion;

    private List<List<Cell>> rows = List.of();
    private boolean closed;

    // A piece of a row, where it is drawn; item or help make it clickable.
    private record Cell(int x, int width, String text, Font font, Color color, Item item, boolean help) {}

    private final ComponentAdapter resized = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            refresh(); // Its columns, banded again to the new width.
        }
    };

    private final AncestorListener ancestry = new AncestorListener() {
        @Override
        public void ancestorAdded(AncestorEvent e) {}

        @Override
        public void ancestorRemoved(AncestorEvent e) {
            close(false);
        }

        @Override
        public void ancestorMoved(AncestorEvent e) {
            place();
        }
    };

    private TransientPopup(Editor editor, Transient t) {
        this.editor = editor;
        this.current = t;
        setLayout(null);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setToolTipText("");
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                // Ctrl with Alt is AltGr on Windows, typing a character.
                if (e.isControlDown() && e.isAltDown())
                    return;
                if (pressed(e.getKeyCode(), e.isControlDown(), e.isAltDown() || e.isMetaDown() || e.isShiftDown()))
                    e.consume();
            }

            @Override
            public void keyTyped(KeyEvent e) {
                final boolean altGr = e.isControlDown() && e.isAltDown();
                final boolean plain = !e.isControlDown() && !e.isAltDown() && !e.isMetaDown();
                if ((plain || altGr) && !Character.isISOControl(e.getKeyChar())) {
                    typed(e.getKeyChar());
                    e.consume();
                }
            }
        });
        final MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1)
                    clicked(e.getX(), e.getY());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                final Cell c = cellAt(e.getX(), e.getY());
                final boolean link = c != null && (c.help() || c.item() != null);
                setCursor(Cursor.getPredefinedCursor(link ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!e.isTemporary() && !isInside(e.getOppositeComponent()))
                    close(false);
            }
        });
    }

    /** Opens t over editor's window, in place of any popup open. */
    public static TransientPopup open(Editor editor, Transient t) {
        if (open != null)
            open.close(false);
        final TransientPopup popup = new TransientPopup(editor, t);
        open = popup;
        popup.attach();
        return popup;
    }

    /** The popup open, or null. */
    public static TransientPopup getOpen() {
        return open;
    }

    public Transient getTransient() {
        return current;
    }

    public Editor getEditor() {
        return editor;
    }

    public boolean isClosed() {
        return closed;
    }

    private void attach() {
        layOut();
        final JRootPane root = SwingUtilities.getRootPane(editor);
        if (root == null)
            return; // Headless: keys and clicks still work.
        root.getLayeredPane().add(this, JLayeredPane.POPUP_LAYER);
        editor.addComponentListener(resized);
        editor.addAncestorListener(ancestry);
        place();
        requestFocusInWindow();
    }

    /** Closes it; with refocus, the window it came from gets the keys again. */
    public void close(boolean refocus) {
        if (closed)
            return;
        closed = true;
        if (open == this)
            open = null;
        closeField();
        editor.removeComponentListener(resized);
        editor.removeAncestorListener(ancestry);
        final Container parent = getParent();
        if (parent != null) {
            final Rectangle bounds = getBounds();
            parent.remove(this);
            parent.repaint(bounds.x, bounds.y, bounds.width, bounds.height);
        }
        if (refocus)
            editor.setFocusToDisplay();
    }

    private boolean isInside(Component c) {
        for (; c != null; c = c.getParent())
            if (c == this)
                return true;
        return false;
    }

    // Keys.

    /**
     * A key with no character, or a control key; true if it was used. Other
     * control keys are taken too, so they do nothing behind the popup.
     */
    boolean pressed(int keyCode, boolean ctrl, boolean otherModifiers) {
        if (keyCode == KeyEvent.VK_ESCAPE && !ctrl && !otherModifiers) {
            escape();
            return true;
        }
        if (ctrl && keyCode == KeyEvent.VK_S) {
            saveDefaults();
            return true;
        }
        if (ctrl && keyCode == KeyEvent.VK_H) {
            helpNext = true;
            pending = "";
            message = null;
            refresh();
            return true;
        }
        return ctrl;
    }

    /** A character typed. */
    void typed(char c) {
        final String key = pending + c;
        final Item item = current.item(key);
        message = null;
        if (item == null && current.isPrefixOfKey(key)) {
            pending = key;
            refresh();
            return;
        }
        pending = "";
        if (helpNext) {
            helpNext = false;
            if (item != null)
                help(item);
            else
                refresh();
            return;
        }
        if (item != null)
            act(item);
        else if (c == 'q' && key.length() == 1)
            close(true);
        else if (c == '?' && key.length() == 1)
            help(null);
        else {
            message = key + " is not a key here";
            refresh();
        }
    }

    // Back from a field, a half typed key, or a nested popup; else closed.
    private void escape() {
        if (field != null) {
            closeField();
            requestFocusInWindow();
            refresh();
        } else if (!pending.isEmpty() || helpNext) {
            pending = "";
            helpNext = false;
            refresh();
        } else if (!back.isEmpty()) {
            message = null;
            current = back.pop();
            refresh();
        } else
            close(true);
    }

    /** What an item's key, or a click on it, does. */
    void act(Item item) {
        switch (item) {
            case Switch s -> {
                setOn(s, !isOn(s));
                refresh();
            }
            case Option o -> {
                if (o.chooser() instanceof Cycle cycle) {
                    final List<String> values = cycle.values();
                    final String value = value(o);
                    final int i = value == null ? -1 : values.indexOf(value);
                    setValue(o, values.get((i + 1) % values.size()));
                    refresh();
                } else
                    openField(o);
            }
            case Command c -> run(c);
            case Prefix p -> {
                back.push(current);
                current = p.next().get();
                refresh();
            }
        }
    }

    private void run(Command c) {
        if (!c.stays())
            close(true);
        editor.getDispatcher().actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, c.command()));
        if (c.stays())
            SwingUtilities.invokeLater(this::refresh);
    }

    // Item's help, or with null the popup's.
    private void help(Item item) {
        final String topic = item instanceof Command c ? c.command() : current.help();
        close(false);
        Help.help(topic);
    }

    private void saveDefaults() {
        if (!hasOwnValues()) {
            message = "nothing to save: these settings are the buffer's";
            refresh();
            return;
        }
        try {
            Transients.saveDefaults(current.id(), args());
            message = "saved as the defaults";
        }
        catch (IOException e) {
            Log.error(e);
            message = "not saved: " + e.getMessage();
        }
        refresh();
    }

    // Values.

    // Whether any of its switches and options keep their values here, not
    // in a binding.
    private boolean hasOwnValues() {
        for (Group g : current.groups())
            for (Item item : g.items())
                if (item instanceof Switch s && s.binding() == null || item instanceof Option o && o.binding() == null)
                    return true;
        return false;
    }

    private TransientArgs args() {
        return Transients.args(editor, current.id());
    }

    boolean isOn(Switch s) {
        if (s.binding() != null) {
            final Boolean b = s.binding().get(editor);
            return b != null && b;
        }
        return args().isOn(s.name());
    }

    private void setOn(Switch s, boolean b) {
        if (s.binding() != null) {
            s.binding().set(editor, b);
            updateWindows();
        } else {
            args().setOn(s.name(), b);
            Transients.changed(current.id(), args());
        }
    }

    String value(Option o) {
        return o.binding() != null ? o.binding().get(editor) : args().get(o.name());
    }

    private void setValue(Option o, String value) {
        if (o.binding() != null) {
            o.binding().set(editor, value);
            updateWindows();
        } else {
            args().set(o.name(), value);
            Transients.changed(current.id(), args());
        }
    }

    // A binding changed the buffer: what a command's end does, in every
    // window showing it, since no command ran.
    private void updateWindows() {
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == editor.getBuffer())
                ed.updateDisplay();
    }

    // An option's field, on its row where the value is.

    private void openField(Option o) {
        closeField();
        fieldOption = o;
        field = new JTextField(value(o) == null ? "" : value(o));
        field.setFont(Display.getPlainFont());
        field.setBorder(BorderFactory.createEmptyBorder());
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                final int code = e.getKeyCode();
                if (code == KeyEvent.VK_ENTER) {
                    final String picked =
                            completion != null && completion.isShowing() ? completion.getSelected() : null;
                    acceptField(picked != null ? picked : field.getText());
                    e.consume();
                } else if (code == KeyEvent.VK_ESCAPE) {
                    escape();
                    e.consume();
                } else if (completion != null
                        && completion.isShowing()
                        && (code == KeyEvent.VK_DOWN || code == KeyEvent.VK_UP)) {
                    completion.move(code == KeyEvent.VK_DOWN ? 1 : -1, true);
                    e.consume();
                }
            }
        });
        field.addFocusListener(fieldFocus);
        List<String> all = null;
        if (o.chooser() instanceof Pick pick) {
            completion = new CompletionPopup<>(field, 10);
            completion.setOnClick(this::acceptField);
            all = pick.candidates().apply(editor);
            final List<String> candidates = all;
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    filter(candidates);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    filter(candidates);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {}
            });
        }
        add(field);
        refresh();
        field.selectAll();
        field.requestFocusInWindow();
        if (all != null)
            filter(all);
    }

    private void filter(List<String> all) {
        if (field == null || completion == null)
            return;
        final String typed = field.getText().toLowerCase(Locale.ROOT);
        final List<String> shown = new ArrayList<>();
        for (String s : all)
            if (s.toLowerCase(Locale.ROOT).contains(typed))
                shown.add(s);
        completion.show(shown, 0);
    }

    /** Sets the option whose field is open to value, and closes the field. */
    public void acceptField(String value) {
        final Option o = fieldOption;
        if (o == null)
            return;
        closeField();
        setValue(o, value == null ? "" : value.strip());
        requestFocusInWindow();
        refresh();
    }

    private void closeField() {
        if (completion != null) {
            completion.hide();
            completion = null;
        }
        if (field != null) {
            // Removing a focused field sends the focus on to whatever is next,
            // outside the popup; it comes back here first.
            field.removeFocusListener(fieldFocus);
            if (field.isFocusOwner() && !closed)
                requestFocusInWindow();
            remove(field);
            field = null;
        }
        fieldOption = null;
    }

    /** The field an option's value is being typed in, or null. */
    JTextField getField() {
        return field;
    }

    // Layout.

    private void refresh() {
        if (closed)
            return;
        layOut();
        place();
        repaint();
    }

    private Formatter formatter() {
        return editor.getFormatter();
    }

    private Color dim() {
        return DefaultTheme.blend(formatter().getBackgroundColor(), formatter().getColor(0), 0.5);
    }

    private Color keyColor() {
        return formatter().getHoverLinkColor();
    }

    private Color onColor() {
        final Color c = formatter().getFormatTable().resolveColor("string", true);
        return c != null ? c : formatter().getColor(0);
    }

    private int innerWidth() {
        final int cw = Display.getCharWidth();
        final int window = editor.getWidth() > 0 ? editor.getWidth() : MAX_COLUMNS * cw;
        return Math.min(window - 2 * PAD - 2 * cw, MAX_COLUMNS * cw);
    }

    // Rows of cells: the title, the groups in columns, banded to the width,
    // and the help line.
    private void layOut() {
        final Font plain = Display.getPlainFont();
        final Font bold = plain.deriveFont(Font.BOLD);
        final FontMetrics fm = Utilities.getFontMetrics(plain);
        final FontMetrics bfm = Utilities.getFontMetrics(bold);
        final int cw = Display.getCharWidth();
        final Color text = formatter().getColor(0);
        final List<List<Cell>> out = new ArrayList<>();

        final List<Cell> title = new ArrayList<>();
        int x = add(title, 0, current.title(), bold, text, null, false, bfm);
        if (helpNext)
            add(title, x + 2 * cw, "help for which key?", plain, dim(), null, false, fm);
        else if (!pending.isEmpty())
            add(title, x + 2 * cw, pending + "-", bold, keyColor(), null, false, bfm);
        else if (message != null)
            add(title, x + 2 * cw, message, plain, dim(), null, false, fm);
        out.add(title);

        // Each group a column: its label, then its items.
        final List<List<List<Cell>>> columns = new ArrayList<>();
        final List<Integer> widths = new ArrayList<>();
        for (Group g : current.groups()) {
            final List<List<Cell>> col = new ArrayList<>();
            int width = 0;
            final List<Cell> label = new ArrayList<>();
            width = Math.max(width, add(label, 0, g.label(), bold, text, null, false, bfm));
            col.add(label);
            int keyWidth = 0;
            for (Item item : g.items())
                keyWidth = Math.max(keyWidth, bfm.stringWidth(item.key()));
            for (Item item : g.items()) {
                final List<Cell> row = new ArrayList<>();
                add(row, 0, item.key(), bold, keyColor(), item, false, bfm);
                int end = add(row, keyWidth + cw, item.description(), plain, text, item, false, fm);
                end = addValue(row, item, end + cw, plain, fm);
                width = Math.max(width, end);
                col.add(row);
            }
            columns.add(col);
            widths.add(width);
        }
        final int gap = 3 * cw;
        final int inner = innerWidth();
        int i = 0;
        while (i < columns.size()) {
            // As many columns as fit beside one another.
            int j = i;
            int used = 0;
            while (j < columns.size() && (j == i || used + gap + widths.get(j) <= inner)) {
                used += (j == i ? 0 : gap) + widths.get(j);
                j++;
            }
            int height = 0;
            for (int k = i; k < j; k++)
                height = Math.max(height, columns.get(k).size());
            if (i > 0)
                out.add(List.of());
            for (int r = 0; r < height; r++) {
                final List<Cell> row = new ArrayList<>();
                int left = 0;
                for (int k = i; k < j; k++) {
                    final List<List<Cell>> col = columns.get(k);
                    if (r < col.size())
                        for (Cell c : col.get(r))
                            row.add(new Cell(left + c.x(), c.width(), c.text(), c.font(), c.color(), c.item(), false));
                    left += widths.get(k) + gap;
                }
                out.add(row);
            }
            i = j;
        }

        final List<Cell> help = new ArrayList<>();
        x = add(help, 0, "?", bold, keyColor(), null, true, bfm);
        x = add(help, x + cw, "help", plain, keyColor(), null, true, fm);
        add(help, x, ": " + current.title().toLowerCase(Locale.ROOT), plain, dim(), null, true, fm);
        out.add(List.of());
        out.add(help);
        rows = out;
    }

    // The value shown after an item: a switch's name, an option's value.
    private int addValue(List<Cell> row, Item item, int x, Font plain, FontMetrics fm) {
        if (item instanceof Switch s && !s.name().isEmpty())
            return add(row, x, "(" + s.name() + ")", plain, isOn(s) ? onColor() : dim(), item, false, fm);
        if (item instanceof Option o) {
            if (field != null && fieldOption == o)
                return x + 20 * Display.getCharWidth(); // Room for the field.
            final String value = value(o);
            if (value == null || value.isEmpty())
                return add(row, x, "(" + o.name() + ")", plain, dim(), item, false, fm);
            return add(row, x, value, plain, onColor(), item, false, fm);
        }
        return x;
    }

    private static int add(
            List<Cell> row,
            int x,
            String text,
            Font font,
            Color color,
            Item item,
            boolean help,
            FontMetrics fm) {
        final int width = fm.stringWidth(text);
        row.add(new Cell(x, width, text, font, color, item, help));
        return x + width;
    }

    // Over the bottom of the window's text, above any scroll bar, centered,
    // as tall as its rows.
    private void place() {
        final Container parent = getParent();
        if (parent == null || closed)
            return;
        final Display display = editor.getDisplay();
        final Rectangle window = display.getParent() != null
                ? SwingUtilities.convertRectangle(display.getParent(), display.getBounds(), parent)
                : SwingUtilities.convertRectangle(editor.getParent(), editor.getBounds(), parent);
        final int cw = Display.getCharWidth();
        final int ch = Display.getCharHeight();
        final int width = Math.min(window.width, innerWidth() + 2 * PAD + 2 * cw);
        final int height = Math.min(window.height, rows.size() * ch + 2 * PAD);
        setBounds(window.x + (window.width - width) / 2, window.y + window.height - height, width, height);
        if (field != null) {
            final Rectangle r = fieldBounds();
            if (r != null)
                field.setBounds(r);
        }
        revalidate();
        repaint();
    }

    // Where the field goes: after its option's description.
    private Rectangle fieldBounds() {
        final int ch = Display.getCharHeight();
        for (int r = 0; r < rows.size(); r++) {
            Cell last = null;
            for (Cell c : rows.get(r))
                if (c.item() == fieldOption)
                    last = c;
            if (last != null)
                return new Rectangle(left() + last.x() + last.width() + Display.getCharWidth(),
                        PAD + r * ch,
                        20 * Display.getCharWidth(),
                        ch);
        }
        return null;
    }

    private int left() {
        return PAD + Display.getCharWidth();
    }

    @Override
    protected void paintComponent(Graphics g) {
        final Graphics2D g2d = (Graphics2D) g;
        Display.setRenderingHints(g2d);
        final Color bg = formatter().getBackgroundColor();
        g2d.setColor(DefaultTheme.blend(bg, formatter().getColor(0), 0.06));
        g2d.fillRect(0, 0, getWidth(), getHeight());
        g2d.setColor(DefaultTheme.blend(bg, formatter().getColor(0), 0.35));
        g2d.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        final int ch = Display.getCharHeight();
        final int ascent = Utilities.getFontMetrics(Display.getPlainFont()).getAscent();
        for (int r = 0; r < rows.size(); r++) {
            final int y = PAD + r * ch;
            for (Cell c : rows.get(r)) {
                g2d.setFont(c.font());
                g2d.setColor(c.color());
                g2d.drawString(c.text(), left() + c.x(), y + ascent);
                if (c.help() && c.text().equals("help"))
                    g2d.drawLine(left() + c.x(), y + ascent + 1, left() + c.x() + c.width(), y + ascent + 1);
            }
        }
    }

    // Clicks and hovers.

    private Cell cellAt(int x, int y) {
        final int r = (y - PAD) / Display.getCharHeight();
        if (y < PAD || r >= rows.size())
            return null;
        for (Cell c : rows.get(r))
            if (x >= left() + c.x() && x < left() + c.x() + c.width())
                return c;
        return null;
    }

    /** A click at (x, y) in the popup. */
    void clicked(int x, int y) {
        final Cell c = cellAt(x, y);
        if (c == null) {
            requestFocusInWindow();
            return;
        }
        if (c.help()) {
            final String topic = current.help();
            close(false);
            Help.help(topic);
        } else if (c.item() != null) {
            pending = "";
            act(c.item());
        }
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        final Cell c = cellAt(e.getX(), e.getY());
        if (c == null)
            return null;
        if (c.help())
            return current.help();
        return switch (c.item()) {
            case Command cmd -> {
                final String summary = CommandTable.getSummary(cmd.command());
                yield summary == null ? cmd.command() : cmd.command() + ": " + summary;
            }
            case Switch s -> s.name().isEmpty() ? s.description() : s.name();
            case Option o -> o.name();
            case Prefix p -> p.description();
            case null -> null;
        };
    }

    /** The text of the rows, for tests: cells a space apart. */
    List<String> text() {
        final List<String> list = new ArrayList<>();
        for (List<Cell> row : rows) {
            final StringBuilder sb = new StringBuilder();
            for (Cell c : row) {
                if (sb.length() > 0)
                    sb.append(' ');
                sb.append(c.text());
            }
            list.add(sb.toString());
        }
        return list;
    }

    /** Where an item is drawn, for tests to click. */
    Rectangle boundsOf(Item item) {
        final int ch = Display.getCharHeight();
        for (int r = 0; r < rows.size(); r++)
            for (Cell c : rows.get(r))
                if (c.item() == item)
                    return new Rectangle(left() + c.x(), PAD + r * ch, c.width(), ch);
        return null;
    }
}
