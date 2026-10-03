/*
 * NewsGroupSummaryMode.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

package org.armedbear.j.mail;

import java.awt.event.KeyEvent;
import org.armedbear.j.Editor;
import org.armedbear.j.Frame;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Mode;
import org.armedbear.j.NavigationComponent;
import org.armedbear.j.ToolBar;

public final class NewsGroupSummaryMode extends MailboxMode {
    public static final String NAME = "Summary";

    private static volatile NewsGroupSummaryMode mode;

    private NewsGroupSummaryMode(int id) {
        super(id, NAME);
    }

    /** Made once, by the mode list, which assigns its id. */
    public static synchronized NewsGroupSummaryMode create(int id) {
        if (mode == null)
            mode = new NewsGroupSummaryMode(id);
        return mode;
    }

    public static Mode getMode() {
        NewsGroupSummaryMode m = mode;
        return m != null
            ? m
            : Editor.getModeList().getModeFromModeName(NAME) instanceof NewsGroupSummaryMode x ? x : null;
    }

    public NavigationComponent getSidebarComponent(Editor editor) {
        return null;
    }

    protected final void setKeyMapDefaults(KeyMap km) {
        km.mapKey(KeyEvent.VK_ENTER, 0, "readArticleOtherWindow");
        km.mapKey(KeyEvent.VK_ENTER, CTRL_MASK, "readArticle");
    }

    protected ToolBar getDefaultToolBar(Frame frame) {
        return frame.getDefaultToolBar();
    }
}
