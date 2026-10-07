/*
 * VimExPrompt.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import org.armedbear.j.ActionTextFieldHandler;
import org.armedbear.j.Editor;
import org.armedbear.j.FinderItem;
import org.armedbear.j.FinderTextFieldHandler;
import org.armedbear.j.History;
import org.armedbear.j.HistoryTextField;
import org.armedbear.j.LocationBar;
import org.armedbear.j.util.Icons;

/**
 * Where a {@code :} line is typed.
 *
 * The same borrowed location bar as {@link VimSearchPrompt}, and for the same
 * reason: with the text field focused the display gets no key events, so the
 * modal engine is simply not consulted until focus comes back. It sits at the
 * top of the editor rather than the bottom, which is the one visible
 * divergence from vim and is documented.
 *
 * While the line is a bare name, a list shows the ex commands and j commands
 * that fuzzily match it. Tab fills in the selected one; Enter runs the line as
 * typed, or the selected command if the selection was moved. An empty line
 * shows no list, so Up recalls the history as in vim.
 */
final class VimExPrompt extends FinderTextFieldHandler {
    // A command name being typed: letters, after any spaces.
    private static final Pattern NAME = Pattern.compile("\\s*[A-Za-z]+");

    private final VimInputHandler handler;
    private List<FinderItem> items;
    // Run or abandoned; anything else that ends the prompt abandons the line.
    private boolean finished;

    private VimExPrompt(Editor editor, HistoryTextField textField, VimInputHandler handler) {
        super(editor, textField);
        this.handler = handler;
    }

    /**
     * Opens the prompt.
     *
     * @param seed what the line starts out containing, for the {@code '<,'>}
     *             a visual-mode {@code :} fills in
     * @return false when there is nowhere to put it, as in a frameless editor
     */
    static boolean open(Editor editor, VimInputHandler handler, String seed) {
        if (editor.getFrame() == null)
            return false;
        final LocationBar locationBar = editor.getLocationBar();
        if (locationBar == null)
            return false;
        final HistoryTextField textField = locationBar.getTextField();
        if (textField == null)
            return false;
        locationBar.setLabelText(LocationBar.PROMPT_COMMAND);
        VimExPrompt prompt = new VimExPrompt(editor, textField, handler);
        textField.setHandler(prompt);
        textField.setHistory(new History("vim.ex"));
        textField.setText(seed);
        textField.setCaretPosition(seed.length());
        editor.setFocusToTextField();
        SwingUtilities.invokeLater(prompt::start);
        return true;
    }

    // Built on the first name typed: most lines are run without a list.
    private List<FinderItem> items() {
        if (items == null) {
            List<FinderItem> all = new ArrayList<>();
            for (String[] c : EX_COMMANDS)
                all.add(new ExItem(c[0], c[1], c[2]));
            all.addAll(ActionTextFieldHandler.commandItems(editor));
            items = List.copyOf(all);
        }
        return items;
    }

    @Override
    public void detached() {
        super.detached();
        if (!finished) {
            finished = true;
            handler.exCancelled(editor);
        }
    }

    // A line from the history runs, as it always has.
    @Override
    protected void historyChosen(String s) {
        textField.setText(s);
        run();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        // Ctrl Enter and Alt Enter run the line, as Enter does.
        if (e.getKeyCode() == KeyEvent.VK_ENTER && e.getModifiersEx() != 0) {
            e.consume();
            enter();
            return;
        }
        super.keyPressed(e);
    }

    private static boolean isName(String text) {
        return NAME.matcher(text).matches();
    }

    @Override
    protected String queryText(String text) {
        return isName(text) ? text.strip() : "";
    }

    @Override
    protected List<FinderItem> candidates() {
        return isName(textField.getText()) ? items() : List.of();
    }

    @Override
    protected List<FinderItem> emptyQueryItems() {
        return List.of();
    }

    @Override
    public void enter() {
        if (isName(textField.getText()) && selectionMoved()) {
            FinderItem.Row row = selection();
            if (row != null)
                textField.setText(row.item().insertText());
        }
        run();
    }

    @Override
    protected void accept(FinderItem item, boolean otherWindow) {
        textField.setText(item.insertText());
        run();
    }

    @Override
    public void tab() {
        FinderItem.Row row = isName(textField.getText()) ? selection() : null;
        if (row != null) {
            String s = row.item().insertText() + " ";
            textField.setText(s);
            textField.setCaretPosition(s.length());
        }
    }

    private void run() {
        final String line = textField.getText();
        final History history = textField.getHistory();
        if (history != null && line != null && !line.isEmpty()) {
            history.append(line);
            history.save();
        }
        finished = true;
        detached();
        // Focus first: the command moves the caret and edits the buffer, and
        // the display has to own the caret again before that happens.
        editor.ensureActive();
        editor.setFocusToDisplay();
        editor.updateLocation();
        handler.exEntered(editor, line);
        editor.getDispatcher().eventHandled();
    }

    /** Escape abandons the line, as does clicking away from the field. */
    @Override
    public void escape() {
        if (!finished) {
            finished = true;
            handler.exCancelled(editor);
        }
        super.escape();
        // Back from CTRL-O, the mode shown has changed.
        editor.getDispatcher().eventHandled();
    }

    // The ex commands VimExCommands knows: name, shortest form, summary.
    private static final String[][] EX_COMMANDS = {
        { "copy", "co", "Copy lines below an address; :t is the same." },
        { "delete", "d", "Delete lines, into a register." },
        { "delmarks", "delm", "Forget marks." },
        { "global", "g", "Run a command on every line matching a pattern." },
        { "join", "j", "Join lines." },
        { "jumps", "ju", "List the jump list, to go to a position." },
        { "changes", "changes", "List where the buffer was changed, to go to a change." },
        { "move", "m", "Move lines below an address." },
        { "nohlsearch", "noh", "Stop highlighting the last search's matches." },
        { "normal", "norm", "Run normal mode keys on each line." },
        { "only", "on", "Close every other window." },
        { "close", "clo", "Close this window." },
        { "quit", "q", "Close this window, or the editor." },
        { "set", "se", "Set an option." },
        { "sort", "sor", "Sort lines." },
        { "split", "sp", "Split the window, opening a file in the new one." },
        { "substitute", "s", "Replace a pattern's matches: s/pattern/replacement/flags." },
        { "vglobal", "v", "Run a command on every line not matching a pattern." },
        { "vsplit", "vs", "Split the window side by side, opening a file." },
        { "write", "w", "Save the buffer." },
        { "wq", "wq", "Save the buffer and close the window." },
        { "yank", "y", "Copy lines into a register." }, };

    private static final class ExItem implements FinderItem {
        private final String name;
        private final String abbreviation;
        private final String summary;

        ExItem(String name, String abbreviation, String summary) {
            this.name = name;
            this.abbreviation = abbreviation;
            this.summary = summary;
        }

        // Just the name: what is typed after ":" is a name, and summaries
        // shared by such as split and vsplit would tie them.
        @Override
        public String matchText() {
            return name;
        }

        @Override
        public String label() {
            return name;
        }

        @Override
        public int labelOffset() {
            return 0;
        }

        @Override
        public String detail() {
            return abbreviation.equals(name) ? "" : ":" + abbreviation;
        }

        @Override
        public String note() {
            return summary;
        }

        @Override
        public Icon icon() {
            return Icons.getIconFromFile("action");
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {}
    }
}
