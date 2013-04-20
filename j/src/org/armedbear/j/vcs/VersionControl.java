/*
 * VersionControl.java
 *
 * Copyright (C) 2005 Peter Graves
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

package org.armedbear.j.vcs;

import org.armedbear.j.Buffer;
import org.armedbear.j.BufferIterator;
import org.armedbear.j.Constants;
import org.armedbear.j.Directories;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorIterator;
import org.armedbear.j.util.FastStringBuffer;
import org.armedbear.j.File;
import org.armedbear.j.MessageDialog;
import org.armedbear.j.OutputBuffer;
import org.armedbear.j.Property;
import org.armedbear.j.ShellCommand;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.vcs.cvs.CVSEntry;
import org.armedbear.j.vcs.git.GitEntry;
import org.armedbear.j.vcs.svn.SVNEntry;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

public abstract class VersionControl implements Constants
{
  public static VersionControlEntry getEntry(Buffer buffer)
  {
      int vc;
      final VersionControlEntry prevEntry = buffer.getVCSEntry();
      if (prevEntry != null)
          vc = prevEntry.getVersionControl();
      else
          vc = guessVCS(buffer);

      if (vc < 0)
        return null;

      switch (vc)
      {
          case VC_CVS:   return CVSEntry.getEntry(buffer);
          case VC_SVN:   return SVNEntry.getEntry(buffer);
//          case VC_P4:    return P4Entry.getEntry(buffer);
//          case VC_DARCS: return DarcsEntry.getEntry(buffer);
          case VC_GIT:   return GitEntry.getEntry(buffer);
          default:       return null;
      }
  }

  public static int guessVCS(Buffer buffer)
  {
      final File file = buffer.getFile();
      if (file == null || file.isRemote())
          return -1;
      File parentDir = file.getParentFile();
      if (parentDir == null)
          return -1;
      File dir = null;
      if (null != (dir = File.getInstance(parentDir, "CVS")) && dir.isDirectory())
          return VC_CVS;
      if (System.getenv("P4CONFIG") != null || System.getenv("P4PORT") != null)
          return VC_P4;
      do {
          assert parentDir != null;
          if (null != (dir = File.getInstance(parentDir, ".svn")) && dir.isDirectory())
              return VC_SVN;
          if (null != (dir = File.getInstance(parentDir, "_darcs")) && dir.isDirectory())
              return VC_DARCS;
          if (null != (dir = File.getInstance(parentDir, ".git")) && dir.isDirectory())
              return VC_GIT;
          parentDir = parentDir.getParentFile();
      }
      while (parentDir != null || parentDir == Directories.getUserHomeDirectory());
      return -1;
  }


  protected static void diffCompleted(Editor editor, Buffer parentBuffer,
                                      String title, String output, int vcType)
  {
    if (output.length() == 0)
      {
        parentBuffer.setBusy(false);
        MessageDialog.showMessageDialog(editor,
                "No changes since latest version",
                parentBuffer.getFile().getName());
      }
    else
      {
        DiffOutputBuffer buf =
          new DiffOutputBuffer(parentBuffer, output, vcType);
        buf.setTitle(title);
        editor.makeNext(buf);
        editor.activateInOtherWindow(buf);
        parentBuffer.setBusy(false);
        for (EditorIterator it = new EditorIterator(); it.hasNext();)
          {
            Editor ed = it.next();
            if (ed.getBuffer() == parentBuffer)
              ed.setDefaultCursor();
          }
      }
  }

  protected static void processCompleted(Buffer buffer, String output)
  {
    buffer.setText(output);
    buffer.setBusy(false);
    for (EditorIterator it = new EditorIterator(); it.hasNext();)
      {
        Editor ed = it.next();
        if (ed.getBuffer() == buffer)
          {
            ed.setDot(buffer.getFirstLine(), 0);
            ed.setTopLine(buffer.getFirstLine());
            ed.setUpdateFlag(REPAINT);
            ed.updateDisplay();
          }
      }
  }

  protected static void vcsCompleted(Editor editor, Buffer buffer,
                                     boolean diff, String title, String output, int vcType, boolean checkVCS)
  {
      if (output != null && output.length() > 0)
      {
          Buffer buf;
          if (diff)
              buf = new DiffOutputBuffer(buffer, output, vcType);
          else
              buf = OutputBuffer.getOutputBuffer(output);
          buf.setTitle(title);
          editor.makeNext(buf);
          editor.activateInOtherWindow(buf);
      }

      if (checkVCS)
      {
          buffer.checkVCS();
          buffer.setBusy(false);
          for (EditorIterator it = new EditorIterator(); it.hasNext();)
          {
              Editor ed = it.next();
              if (ed.getBuffer() == buffer)
              {
                  ed.setDefaultCursor();
                  // Update version information in status bar.
                  ed.getFrame().repaintStatusBar();
              }
          }
      }
  }

    /**
     * Parse a command line and replace tokens if needed.
     *
     * @param cmd the command line to parse.
     * @param args arguments to the command will be concatinated to the cmd or null.
     * @param replaceFileTokens if true, replace '%' with the current buffer's filename.
     * @param appendFilename if true, append the current buffer's filename if no '%' token was already replaced in the args.
     * @return the parsed command line.
     */
    protected static String parseArgs(String cmd, String args, boolean replaceFileTokens, boolean appendFilename)
    {
        final Editor editor = Editor.currentEditor();
        final Buffer parentBuffer = editor.getBuffer();
        final File file = parentBuffer.getFile();

        boolean hasFilename = false;
        if (args != null)
            cmd = cmd + ' ' + args;
        List<String> tokens = Utilities.tokenize(cmd);
        FastStringBuffer sb = new FastStringBuffer();
        if (replaceFileTokens) {
            for (String arg : tokens) {
                if (arg.equals("%")) {
                    hasFilename = true;
                    if (file != null)
                        arg = file.canonicalPath();
                }
                sb.append(Utilities.maybeQuote(arg));
                sb.append(' ');
            }
        }
        else {
            for (String arg : tokens) {
                if (arg.charAt(0) != '-') {
                    // assume filename.
                    hasFilename = true;
                    break;
                }
            }

            sb.append(args);
        }

        if (appendFilename && !hasFilename && file != null) {
            if (sb.charAt(sb.length()-1) != ' ')
                sb.append(" ");
            sb.append(Utilities.maybeQuote(file.getName()));
        }

        return sb.toString();
    }

  // Implementation.
  protected static String command(String cmd, File workingDirectory)
  {
      ShellCommand shellCommand = new ShellCommand(cmd, workingDirectory);
      shellCommand.run();
      return shellCommand.getOutput();
  }

  protected static void outputBufferCommand(final Editor editor, final String cmd, final File workingDirectory)
  {
      editor.setWaitCursor();
      Runnable commandRunnable = new Runnable()
        {
          public void run()
          {
            final String output = command(cmd, workingDirectory);
            Runnable completionRunnable = new Runnable()
              {
                public void run()
                {
                  OutputBuffer buf = OutputBuffer.getOutputBuffer(output);
                  buf.setTitle(cmd);
                  editor.makeNext(buf);
                  editor.activateInOtherWindow(buf);
                  editor.setDefaultCursor();
                }
              };
            SwingUtilities.invokeLater(completionRunnable);
          }
        };
      new Thread(commandRunnable).start();
  }

  protected static List<Buffer> getModifiedBuffers()
  {
    ArrayList<Buffer> list = null;
    for (BufferIterator it = new BufferIterator(); it.hasNext();)
      {
        Buffer buf = it.next();
        if (!buf.isModified())
          continue;
        if (buf.isUntitled())
          continue;
        final int modeId = buf.getModeId();
        if (modeId == SEND_MAIL_MODE)
          continue;
        if (modeId == CHECKIN_MODE)
          continue;
        if (buf.getFile() != null && buf.getFile().isLocal())
          {
            if (list == null)
              list = new ArrayList<Buffer>();
            list.add(buf);
          }
      }
    return list;
  }

  protected static boolean saveModifiedBuffers(Editor editor, List<Buffer> list)
  {
    editor.setWaitCursor();
    int numErrors = 0;
    for (Buffer buf : list) {
        if (buf.getFile() != null && buf.getFile().isLocal()) {
            editor.status("Saving modified buffers...");
            if (buf.getBooleanProperty(Property.REMOVE_TRAILING_WHITESPACE))
                buf.removeTrailingWhitespace();
            if (!buf.save())
                ++numErrors;
        }
    }
    editor.setDefaultCursor();
    if (numErrors == 0)
      {
        editor.status("Saving modified buffers...done");
        return true;
      }
    // User will already have seen detailed error information from Buffer.save().
    editor.status("");
    return false;
  }
}
