/*
 * FinderItem.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import javax.swing.Icon;

/**
 * Something a finder lists. The query is matched against matchText();
 * label() and detail() are the parts of it shown, at labelOffset() and
 * detailOffset() in matchText(), so matched characters can be marked.
 */
public interface FinderItem {
    String matchText();

    String label();

    /** Where label() starts in matchText(), or -1 if it isn't there. */
    int labelOffset();

    /** Shown dimmed after the label; may be empty. */
    default String detail() {
        return "";
    }

    /** Where detail() starts in matchText(), or -1. */
    default int detailOffset() {
        return -1;
    }

    /** Shown dimmed after the detail, and cut short first; may be empty. */
    default String note() {
        return "";
    }

    /** Shown at the right, such as a key binding; may be empty. */
    default String keyText() {
        return "";
    }

    default Icon icon() {
        return null;
    }

    /** What a prompt fills in for this item, as a command's name. */
    default String insertText() {
        return label();
    }

    /** Added to the match score, to rank this item ahead of others. */
    default int boost() {
        return 0;
    }

    void accept(Editor editor, boolean otherWindow);

    /** An item in a finder's list, with the matched positions in its matchText(). */
    record Row(FinderItem item, int[] positions) {}
}
