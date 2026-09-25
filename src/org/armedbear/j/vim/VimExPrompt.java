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

import org.armedbear.j.DefaultTextFieldHandler;
import org.armedbear.j.Editor;
import org.armedbear.j.History;
import org.armedbear.j.HistoryTextField;
import org.armedbear.j.LocationBar;

/**
 * Where a {@code :} line is typed.
 *
 * The same borrowed location bar as {@link VimSearchPrompt}, and for the same
 * reason: with the text field focused the display gets no key events, so the
 * modal engine is simply not consulted until focus comes back. It sits at the
 * top of the editor rather than the bottom, which is the one visible
 * divergence from vim and is documented.
 */
final class VimExPrompt extends DefaultTextFieldHandler
{
    private final VimInputHandler handler;

    private VimExPrompt(Editor editor, HistoryTextField textField,
                        VimInputHandler handler)
    {
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
    static boolean open(Editor editor, VimInputHandler handler, String seed)
    {
        if (editor.getFrame() == null)
            return false;
        final LocationBar locationBar = editor.getLocationBar();
        if (locationBar == null)
            return false;
        final HistoryTextField textField = locationBar.getTextField();
        if (textField == null)
            return false;
        locationBar.setLabelText(LocationBar.PROMPT_COMMAND);
        textField.setHandler(new VimExPrompt(editor, textField, handler));
        textField.setHistory(new History("vim.ex"));
        textField.setText(seed);
        textField.setCaretPosition(seed.length());
        editor.setFocusToTextField();
        return true;
    }

    @Override
    public void enter()
    {
        final String line = textField.getText();
        final History history = textField.getHistory();
        if (history != null && line != null && !line.isEmpty()) {
            history.append(line);
            history.save();
        }
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
    public void escape()
    {
        handler.exCancelled();
        super.escape();
    }
}
