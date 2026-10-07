/*
 * DefaultTextFieldHandler.java
 *
 * Copyright (C) 1998-2005 Peter Graves
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

package org.armedbear.j;

import static org.armedbear.j.Constants.*;

import java.awt.Container;
import java.awt.event.KeyEvent;
import java.awt.event.TextEvent;
import java.awt.event.TextListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JDialog;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Keys;

public class DefaultTextFieldHandler implements TextFieldHandler {
    protected final Editor editor;
    protected final HistoryTextField textField;

    protected List<String> completions;
    protected int index;

    private Expansion expansion;
    private String savedText;
    private String head;

    public DefaultTextFieldHandler(Editor editor, HistoryTextField textField) {
        this.editor = editor;
        Debug.assertTrue(editor != null);
        this.textField = textField;
    }

    public DefaultTextFieldHandler(HistoryTextField textField) {
        this(Editor.currentEditor(), textField);
    }

    @Override
    public void enter() {}

    @Override
    public void escape() {
        Container c = textField.getParent();
        while (true) {
            if (c instanceof JDialog)
                return;
            if (c == null)
                break;
            c = c.getParent();
        }
        // Text field is not in a dialog box. We must be dealing with the
        // location bar.
        Debug.assertTrue(editor != null);
        editor.ensureActive();
        editor.setFocusToDisplay();
        editor.updateLocation();
    }

    @Override
    public boolean wantTab() {
        return false;
    }

    @Override
    public void tab() {
        if (textField != null) {
            String prefix = textField.getText();
            String s = getCompletion(prefix);
            if (s != null && !s.equals(prefix)) {
                textField.setText(s);
                textField.setCaretPosition(s.length());
            }
        }
    }

    @Override
    public void shiftTab() {
        if (textField != null) {
            String s = getPreviousCompletion();
            if (s != null) {
                String text = textField.getText();
                if (!s.equals(text)) {
                    textField.setText(s);
                    textField.setCaretPosition(s.length());
                }
            }
        }
    }

    @Override
    public void resetCompletions() {
        completions = null;
    }

    protected String getCompletion(String prefix) {
        if (completions == null) {
            completions = getCompletions(prefix);
            index = 0;
        }
        if (completions == null || completions.size() == 0)
            return null;
        if (index >= completions.size())
            index = 0;
        return completions.get(index++);
    }

    private String getPreviousCompletion() {
        if (completions != null && completions.size() > 1) {
            index -= 2;
            if (index < 0)
                index += completions.size();
            return completions.get(index++);
        }
        return null;
    }

    @Override
    public List<String> getCompletions(String prefix) {
        return null;
    }

    private void killLine() {
        textField.setText(textField.getText().substring(0, textField.getCaretPosition()));
    }

    private void expand() {
        if (expansion == null) {
            // New expansion.
            savedText = textField.getText();
            int index = savedText.lastIndexOf(' ');
            if (index >= 0) {
                head = savedText.substring(0, index + 1);
                expansion = textField.getHandler().getExpansion(savedText.substring(index + 1));
            } else {
                Debug.assertTrue(head == null);
                expansion = textField.getHandler().getExpansion(savedText);
            }
        }
        final String candidate = expansion.getNextCandidate();
        if (candidate != null) {
            if (head != null)
                textField.setText(head.concat(candidate));
            else
                textField.setText(candidate);
        }
    }

    public void resetExpansion() {
        expansion = null;
        savedText = null;
        head = null;
    }

    @Override
    public Expansion getExpansion(String prefix) {
        return new Expansion(editor.getBuffer(), prefix, prefix);
    }

    protected void reset() {
        textField.resetHistory();
        textField.getHandler().resetCompletions();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        TextFieldHandler handler = textField.getHandler();
        if (handler == null)
            return;
        if (handler != this)
            Debug.bug();
        if (historyKeyPressed(e))
            return;
        final char keyChar = e.getKeyChar();
        final int keyCode = e.getKeyCode();
        final int modifiers = Keys.keyModifiers(e);
        switch (keyCode) {
            case KeyEvent.VK_ENTER:
                resetExpansion();
                // Make sure user can see what he typed.
                textField.paintImmediately(0, 0, textField.getWidth(), textField.getHeight());
                e.consume();
                handler.enter();
                return;
            case KeyEvent.VK_ESCAPE:
                if (expansion != null) {
                    // Cancel expansion.
                    textField.setText(savedText);
                    resetExpansion();
                    // Consume key event so parent will ignore it.
                    e.consume();
                } else
                    handler.escape();
                return;
            case KeyEvent.VK_TAB:
                resetExpansion();
                if (handler.wantTab()) {
                    if (modifiers == 0) {
                        e.consume();
                        handler.tab();
                    } else if (modifiers == SHIFT_MASK) {
                        e.consume();
                        handler.shiftTab();
                    }
                }
                return;
            case KeyEvent.VK_UP:
            case KeyEvent.VK_KP_UP:
                resetExpansion();
                textField.previousHistory();
                return;
            case KeyEvent.VK_P:
                resetExpansion();
                if (modifiers == CTRL_MASK)
                    textField.previousHistory();
                else
                    reset();
                return;
            case KeyEvent.VK_DOWN:
            case KeyEvent.VK_KP_DOWN:
                resetExpansion();
                if (modifiers == ALT_MASK)
                    showHistory();
                else
                    textField.nextHistory();
                return;
            case KeyEvent.VK_N:
                resetExpansion();
                if (modifiers == CTRL_MASK)
                    textField.nextHistory();
                else
                    reset();
                return;
            case KeyEvent.VK_SHIFT:
            case KeyEvent.VK_CONTROL:
            case KeyEvent.VK_META:
            case KeyEvent.VK_ALT:
                return;
            default:
                reset();
                break;
        }
        KeyMapping mapping = editor.getKeyMapping(keyChar, keyCode, modifiers);
        if (mapping != null) {
            Object command = mapping.getCommand();
            if (command == "killLine")
                killLine();
            else if (command == "expand") {
                expand();
                return; // Don't call resetExpansion()!
            } else if (command == "escape") {
                // keyboard-quit
                if (expansion != null) {
                    // Cancel expansion.
                    textField.setText(savedText);
                    resetExpansion();
                    // Consume key event so parent will ignore it.
                    e.consume();
                } else
                    handler.escape();
                return;
            }
        }
        resetExpansion();
    }

    @Override
    public void keyReleased(KeyEvent e) {
        TextListener textListener = textField.getTextListener();
        if (textListener != null)
            textListener.textValueChanged(new TextEvent(this, TextEvent.TEXT_VALUE_CHANGED));
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    private CompletionPopup<FinderItem.Row> history;

    /** Lists the field's history, newest first, as the finders list. */
    protected void showHistory() {
        if (textField == null || textField.getHistory() == null)
            return;
        final History h = textField.getHistory();
        final String existing = textField.getText();
        List<FinderItem.Row> rows = new ArrayList<>();
        for (int i = h.size(); i-- > 0;) {
            String s = h.get(i);
            if (!s.equals(existing))
                rows.add(new FinderItem.Row(new HistoryItem(s), null));
        }
        if (rows.isEmpty())
            return;
        if (history == null) {
            history = new CompletionPopup<>(textField, 15);
            history.setCellRenderer(new FinderCellRenderer());
            history.setOnClick(row -> chooseHistory(row));
        }
        history.show(rows, 0);
    }

    /** Whether the history list is showing. */
    protected final boolean isHistoryShowing() {
        return history != null && history.isShowing();
    }

    private void chooseHistory(FinderItem.Row row) {
        history.hide();
        if (row != null)
            historyChosen(row.item().label());
    }

    /** What picking an entry from the history does: by default, runs it. */
    protected void historyChosen(String s) {
        textField.setText(s);
        enter();
    }

    /** Called when Escape closes the history list. */
    protected void historyClosed() {}

    /**
     * Keys for the history list while it shows: Up, Down, Ctrl P, Ctrl N,
     * Page Up and Page Down move; Enter picks; Tab puts the entry in the field;
     * Escape closes the list. Any other key closes it and goes on as usual.
     */
    protected final boolean historyKeyPressed(KeyEvent e) {
        if (!isHistoryShowing())
            return false;
        final int modifiers = Keys.keyModifiers(e);
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> history.move(-1, false);
            case KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> history.move(+1, false);
            case KeyEvent.VK_P -> {
                if (modifiers != CTRL_MASK) {
                    history.hide();
                    return false;
                }
                history.move(-1, false);
            }
            case KeyEvent.VK_N -> {
                if (modifiers != CTRL_MASK) {
                    history.hide();
                    return false;
                }
                history.move(+1, false);
            }
            case KeyEvent.VK_PAGE_UP -> history.page(-1);
            case KeyEvent.VK_PAGE_DOWN -> history.page(+1);
            case KeyEvent.VK_ENTER -> chooseHistory(history.getSelected());
            case KeyEvent.VK_TAB -> {
                FinderItem.Row row = history.getSelected();
                history.hide();
                if (row != null) {
                    textField.setText(row.item().label());
                    textField.setCaretPosition(textField.getText().length());
                }
            }
            case KeyEvent.VK_ESCAPE -> {
                history.hide();
                historyClosed();
            }
            case KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_META, KeyEvent.VK_ALT -> {
                return true;
            }
            default -> {
                history.hide();
                return false;
            }
        }
        e.consume();
        return true;
    }

    private static final class HistoryItem implements FinderItem {
        private final String text;

        HistoryItem(String text) {
            this.text = text;
        }

        @Override
        public String matchText() {
            return text;
        }

        @Override
        public String label() {
            return text;
        }

        @Override
        public int labelOffset() {
            return 0;
        }

        @Override
        public Icon icon() {
            return Icons.getIconFromFile("history");
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {}
    }
}
