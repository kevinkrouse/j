/*
 * Copyright (C) 2009 Kevin Krouse
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

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.*;

public final class StatusMode extends AbstractMode
{
    private static final StatusMode mode = new StatusMode();

    private StatusMode()
    {
        super(VCS_STATUS_MODE, VCS_STATUS_MODE_NAME);
    }

    public static StatusMode getMode()
    {
        return mode;
    }

    public Formatter getFormatter(Buffer buffer)
    {
        return new StatusFormatter((StatusOutputBuffer)buffer);
    }

    public static void diffFile()
    {
        fileAction(true);
    }

    public static void gotoFile()
    {
        fileAction(false);
    }

    private static void fileAction(boolean useDiff)
    {
        final Editor editor = Editor.currentEditor();
        if (editor.getDot() == null)
          return;
        final Buffer buffer = editor.getBuffer();
        if (!(buffer instanceof StatusOutputBuffer))
          return;

        // If this method is invoked via a mouse event mapping, move dot to
        // location of mouse click first.
        AWTEvent e = editor.getDispatcher().getLastEvent();
        if (e instanceof MouseEvent)
          editor.mouseMoveDotToPoint((MouseEvent) e);

        StatusOutputBuffer outputBuffer = (StatusOutputBuffer) buffer;
        int vcType = outputBuffer.getVCType();
        switch (vcType) {
          case VC_SVN:
              svnFileAction(editor, outputBuffer, useDiff);
              break;
          default:
              throw new IllegalStateException("statusGotoFile/statusDiffFile not supported for this version type: " + vcType);
        }
    }

    private static void svnFileAction(final Editor editor, final StatusOutputBuffer outputBuffer, boolean useDiff)
    {
        final Line dotLine = editor.getDotLine();

        if (dotLine == null || dotLine.length() == 0)
            return;
        char c = dotLine.charAt(0);
        // "svn status" output added a column in 1.6
        int filenameIndex = SVN.have16() ? 8 : 7;
        String filename = dotLine.substring(filenameIndex);
        Buffer buf = null;

        if (c == 'D') {
            // deleted, nothing to do.
        }
        else if (useDiff && c == 'M') {
            // modified, open file diff
            File file = File.getInstance(outputBuffer.getDirectory(), filename);
            Buffer parentBuffer = editor.openFile(file);
            if (parentBuffer != null)
                SVN.diff(editor, parentBuffer, file);
        }
        else {
            // otherwise, just open it
            File file = File.getInstance(outputBuffer.getDirectory(), filename);
            buf = Editor.getBuffer(file);
            if (buf != null) {
                if (editor.getOtherEditor() != null) {
                    editor.activateInOtherWindow(buf);
                }
                else {
                    editor.makeNext(buf);
                    editor.activate(buf);
                }
            }
        }
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km)
    {
        km.mapKey(KeyEvent.VK_ENTER, 0, "statusDiffFile");
        km.mapKey(KeyEvent.VK_ENTER, CTRL_MASK, "statusGotoFile");
        km.mapKey(KeyEvent.VK_G, CTRL_MASK | SHIFT_MASK, "statusGotoFile");
        km.mapKey(VK_DOUBLE_MOUSE_1, 0, "statusDiffFile");
        km.mapKey(VK_MOUSE_2, 0, "statusGotoFile");
        km.mapKey('q', "tempBufferQuit");
    }
}
