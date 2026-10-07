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
    ICON_BACK("left"),
    ICON_CLOSE("close"),
    ICON_COPY("copy"),
    ICON_CUT("cut"),
    ICON_DELETE("delete"),
    ICON_DIRECTORY("directory-list"),
    ICON_EXIT("application-exit"),
    ICON_FIND("search"),
    ICON_FORWARD("right"),
    ICON_HOME("home"),
    ICON_MAIL_ATTACH("attach"),
    ICON_MAIL_COMPOSE("mail-message-new"),
    ICON_MAIL_INBOX("mail"),
    ICON_MAIL_NEXT("right"),
    ICON_MAIL_PREVIOUS("left"),
    ICON_MAIL_RECEIVE("mail-receive"),
    ICON_MAIL_REPLY_SENDER("mail-reply-sender"),
    ICON_MAIL_REPLY_ALL("mail-reply-all"),
    ICON_MAIL_SEND("mail-send"),
    //ICON_MAIL_SEND_RECEIVE("mail-send-receive"),
    ICON_NEW("document-new"),
    ICON_OPEN("document-open"),
    ICON_PASTE("paste"),
    ICON_PROJECT("project"),
    ICON_REDO("redo"),
    ICON_REFRESH("refresh"),
    ICON_REPLACE("search-and-replace"),
    ICON_SAVE("document-save"),
    ICON_STOP("stop"),
    ICON_UNDO("undo"),
    ICON_UP("up");

    private String _filename;

    ToolBarIcon(String filename)
    {
        _filename = filename;
    }
    
    public String getFile()
    {
        return _filename;
    }
}
