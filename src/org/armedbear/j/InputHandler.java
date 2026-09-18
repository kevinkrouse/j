/*
 * InputHandler.java
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
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111, USA.
 */

package org.armedbear.j;

/**
 * A chance to claim a keystroke before the key maps see it.
 *
 * j's ordinary input model has no such point. {@link Editor#handleJEvent} looks
 * a key up in the buffer's mode key map and then the global one, and anything
 * that matches neither falls through to {@code Dispatcher.dispatchKeyTyped},
 * which inserts it into the buffer. There is nowhere for a key to mean
 * something other than itself, which is what modal editing needs.
 *
 * An editor in the default "simple" edit mode has no handler at all, so that
 * path is untouched.
 *
 * @see org.armedbear.j.vim.VimInputHandler
 */
public interface InputHandler
{
    /** What a handler wants done with a keystroke. */
    enum Result
    {
        /**
         * The handler dealt with it. Nothing else runs and nothing is
         * inserted.
         */
        CONSUMED,

        /**
         * The handler wants this keystroke, but not at this event id.
         *
         * AWT reports an ordinary character twice: once as a key press,
         * carrying the physical key, and again as a key typed, carrying the
         * character the layout produced. Only the second says whether the user
         * typed {@code d} or {@code D} or, on some layouts, {@code :}. A
         * handler defers the press so it can decide on the typed event, without
         * the key maps getting the key in between.
         */
        DEFER,

        /** Not interesting: carry on as if there were no handler. */
        PASS_THROUGH
    }

    /** What the caret should look like. */
    enum CaretShape
    {
        /** Between two characters, as when inserting. */
        BAR,
        /** On a character, as in a mode where keys are commands. */
        BLOCK,
        /** On a character that typing will overwrite. */
        UNDERLINE
    }

    /**
     * The caret shape for the current state.
     *
     * A modal editor has to show which mode it is in, and the caret is the
     * place the eye already is.
     */
    default CaretShape getCaretShape()
    {
        return CaretShape.BAR;
    }

    /**
     * What to show in the status bar, as in {@code -- INSERT --}, or null for
     * a state that announces nothing.
     */
    default String getModeIndicator()
    {
        return null;
    }

    /**
     * The command typed so far but not yet complete, as in {@code "a2d}, or
     * null when there is none.
     */
    default String getPendingCommand()
    {
        return null;
    }

    /**
     * Offers one event to the handler.
     *
     * Called for key presses, key typed events and mouse presses, from the top
     * of {@link Editor#handleJEvent}.
     */
    Result handle(Editor editor, JEvent event);

    /**
     * Called when the editor stops showing the buffer this handler has state
     * for, so that anything left open can be closed.
     */
    default void editorDeactivated(Editor editor)
    {
    }
}
