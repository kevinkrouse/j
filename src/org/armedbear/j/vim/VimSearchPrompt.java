/*
 * VimSearchPrompt.java
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
 * Where the pattern for {@code /} and {@code ?} is typed.
 *
 * j's location bar, which {@code incrementalFind} and {@code executeCommand}
 * already borrow the same way. Taking focus is what makes this work without
 * touching the modal engine: with the text field focused the display gets no
 * key events, so {@link VimInputHandler} is simply not consulted until focus
 * comes back.
 *
 * <p>It sits at the top of the editor rather than the bottom, which is where
 * vim puts it. That is the one visible divergence and it is documented.
 */
final class VimSearchPrompt extends DefaultTextFieldHandler
{
    private final VimInputHandler handler;
    private final boolean forward;
    private boolean finished;

    private VimSearchPrompt(Editor editor, HistoryTextField textField,
                            VimInputHandler handler, boolean forward)
    {
        super(editor, textField);
        this.handler = handler;
        this.forward = forward;
    }

    /**
     * Opens the prompt.
     *
     * @return false when there is nowhere to put it, as in a frameless
     *         editor; the caller then has no search to wait for
     */
    static boolean open(Editor editor, VimInputHandler handler,
                        boolean forward)
    {
        // The frame owns focus and the session properties history is stored
        // in, so without one there is nothing to prompt with.
        if (editor.getFrame() == null)
            return false;
        final LocationBar locationBar = editor.getLocationBar();
        if (locationBar == null)
            return false;
        final HistoryTextField textField = locationBar.getTextField();
        if (textField == null)
            return false;
        locationBar.setLabelText(LocationBar.PROMPT_PATTERN);
        textField.setHandler(new VimSearchPrompt(editor, textField, handler,
                                                 forward));
        textField.setHistory(new History("vim.search"));
        textField.setText("");
        editor.setFocusToTextField();
        return true;
    }

    @Override
    public void enter()
    {
        final String pattern = textField.getText();
        finished = true;
        final History history = textField.getHistory();
        if (history != null && pattern != null && !pattern.isEmpty()) {
            history.append(pattern);
            history.save();
        }
        // Focus first: the search moves the caret, and the display has to own
        // the caret again before that happens.
        editor.ensureActive();
        editor.setFocusToDisplay();
        editor.updateLocation();
        handler.searchEntered(editor, pattern);
        editor.getDispatcher().eventHandled();
    }

    @Override
    public void escape()
    {
        finished = true;
        handler.searchCancelled();
        super.escape();
    }

    /**
     * Losing focus any other way -- a mouse click elsewhere -- abandons the
     * search too, or the operator would stay pending and swallow the next
     * motion typed.
     */
    boolean isFinished()
    {
        return finished;
    }
}
