/*
 * SVN.java
 *
 * Copyright (C) 2009 Kevin Krouse
 * $Id$
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

import java.util.Iterator;
import java.util.List;
import javax.swing.SwingUtilities;

public class SVN extends VersionControl implements Constants
{
    public static void svn()
    {
        if (!checkSVNInstalled())
            return;
        MessageDialog.showMessageDialog("The command \"svn\" requires an argument.",
                                        "Error");
    }
    
    public static void svn(String s)
    {
        if (!checkSVNInstalled())
            return;
        List args = Utilities.tokenize(s);
        if (args.size() == 0)
            return;
        String command = (String) args.get(0);
        final Editor editor = Editor.currentEditor();
        editor.setWaitCursor();
        FastStringBuffer sb = new FastStringBuffer("svn ");
        for (Iterator it = args.iterator(); it.hasNext();)
        {
            String arg = (String) it.next();
            if (arg.equals("%"))
            {
                File file = editor.getBuffer().getFile();
                if (file != null)
                    arg = file.canonicalPath();
            }
            sb.append(maybeQuote(arg));
            sb.append(' ');
        }
        final String cmd = sb.toString().trim();
        final Buffer parentBuffer = editor.getBuffer();
        Runnable commandRunnable = new Runnable()
        {
            public void run()
            {
                final String output =
                    command(cmd, editor.getCurrentDirectory());
                Runnable completionRunnable = new Runnable()
                {
                    public void run()
                    {
                        svnCompleted(editor, parentBuffer, cmd, output);
                    }
                };
                SwingUtilities.invokeLater(completionRunnable);
            }
        };
        new Thread(commandRunnable).start();
    }
    
    private static void svnCompleted(Editor editor, Buffer parentBuffer,
                                     String cmd, String output)
    {
        if (output != null && output.length() > 0)
        {
            Buffer buf;
            if (cmd.startsWith("svn diff"))
                buf = new DiffOutputBuffer(parentBuffer, output, VC_SVN);
            else
                buf = OutputBuffer.getOutputBuffer(output);
            buf.setTitle(cmd);
            editor.makeNext(buf);
            editor.activateInOtherWindow(buf);
        }
    }
    
    private static boolean checkSVNInstalled()
    {
        if (haveSVN())
            return true;
        MessageDialog.showMessageDialog(
            "The Subversion command-line client does not appear to be in your PATH.",
            "Error");
        return false;
    }

    private static boolean haveSVN = false;

    private static boolean haveSVN()
    {
        if (haveSVN)
            return true;
        if (Utilities.have("svn"))
            haveSVN = true;
        return haveSVN;
    }
}