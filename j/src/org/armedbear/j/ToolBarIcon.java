/*
 * ToolBarIcon.java
 *
 * Copyright (C) 2002-2009 Peter Graves
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

public enum ToolBarIcon
{
    ICON_BACK("stock_left.png"),
    ICON_CLOSE("stock_close.png"),
    ICON_COPY("stock_copy.png"),
    ICON_CUT("stock_cut.png"),
    ICON_DELETE("stock_delete.png"),
    ICON_DIRECTORY("stock_index.png"),
    ICON_EXIT("application-exit.png"),
    ICON_FIND("stock_search.png"),
    ICON_FORWARD("stock_right.png"),
    ICON_HOME("stock_home.png"),
    ICON_MAIL_ATTACH("stock_attach.png"),
    ICON_MAIL_COMPOSE("mail-message-new.png"),
    ICON_MAIL_INBOX("mail.png"),
    ICON_MAIL_NEXT("stock_right.png"),
    ICON_MAIL_PREVIOUS("stock_left.png"),
    ICON_MAIL_RECEIVE("mail-receive.png"),
    ICON_MAIL_REPLY_SENDER("mail-reply-sender.png"),
    ICON_MAIL_REPLY_ALL("mail-reply-all.png"),
    ICON_MAIL_SEND("mail-send.png"),
    //ICON_MAIL_SEND_RECEIVE("mail-send-receive.png"),
    ICON_NEW("document-new.png"),
    ICON_OPEN("document-open.png"),
    ICON_PASTE("stock_paste.png"),
    ICON_REDO("stock_redo.png"),
    ICON_REFRESH("stock_refresh.png"),
    ICON_REPLACE("stock_search-and-replace.png"),
    ICON_SAVE("document-save.png"),
    ICON_STOP("stock_stop.png"),
    ICON_UNDO("stock_undo.png"),
    ICON_UP("stock_up.png");

    private String _filename;

    ToolBarIcon(String filename)
    {
        _filename = filename;
    }
    
    public String getFile(int size)
    {
        return "toolbar/" + String.valueOf(size) + "/" + _filename;
    }
}
