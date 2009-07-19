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

import java.util.List;
import java.util.Collections;
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
        if (command.equals("commit"))
          {
            MessageDialog.showMessageDialog("Use \"svnCommit\".", "Error");
            return;
          }
        final Editor editor = Editor.currentEditor();
        editor.setWaitCursor();
        final String cmd = parseArgs("svn", s, true, false);
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
    
    private static void svnCompleted(Editor editor, Buffer buffer,
                                     String cmd, String output)
    {
        if (output != null && output.length() > 0)
        {
            Buffer buf;
            if (cmd.startsWith("svn diff"))
                buf = new DiffOutputBuffer(buffer, output, VC_SVN);
            else
                buf = OutputBuffer.getOutputBuffer(output);
            buf.setTitle(cmd);
            editor.makeNext(buf);
            editor.activateInOtherWindow(buf);
        }
        buffer.checkVCS();
        buffer.setBusy(false);
        for (EditorIterator it = new EditorIterator(); it.hasNext();)
          {
            Editor ed = it.nextEditor();
            if (ed.getBuffer() == buffer)
              {
                ed.setDefaultCursor();
                // Update SVN information in status bar.
                ed.getFrame().repaintStatusBar();
              }
          }
    }

    public static void add()
    {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer.getFile() == null)
          return;
        editor.setWaitCursor();
        final String cmd = "svn add " + maybeQuote(buffer.getFile().getName());
        outputBufferCommand(editor, cmd, buffer.getCurrentDirectory());
        // UNDONE: call buffer.checkVCS() and refresh 
    }

    public static void revert()
    {
        if (!checkSVNInstalled())
            return;
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        final File file = buffer.getFile();
        if (file == null)
          return;
//        if (buffer.isModified())
          {
            String prompt =
              "Discard changes to " + maybeQuote(file.getName()) + "?";
            if (!editor.confirm("Revert Buffer", prompt))
              return;
          }
        final String cmd = "svn revert " + maybeQuote(file.getName());
        Runnable commandRunnable = new Runnable()
          {
            public void run()
            {
              final String output = command(cmd, buffer.getCurrentDirectory());
              Runnable completionRunnable = new Runnable()
                {
                  public void run()
                  {
                    if (output.length() == 0 || output.trim().startsWith("Reverted "))
                      editor.status("File reverted");
                    else
                      {
                        OutputBuffer buf = OutputBuffer.getOutputBuffer(output);
                        buf.setTitle(cmd);
                        editor.makeNext(buf);
                        editor.activateInOtherWindow(buf);
                      }
                    editor.reload(buffer);
                    // Update read-only status.
                    if (editor.reactivate(buffer))
                      Sidebar.repaintBufferListInAllFrames();
                  }
                };
              SwingUtilities.invokeLater(completionRunnable);
            }
          };
        new Thread(commandRunnable).start();

    }

    public static void diff()
    {
        if (!checkSVNInstalled())
            return;
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        final Buffer parentBuffer;
        if (buffer instanceof CheckinBuffer)
          parentBuffer = buffer.getParentBuffer();
        else
          parentBuffer = buffer;
        final File file = parentBuffer.getFile();
        if (file == null)
          return;
        diff(editor, parentBuffer, file);
    }

    public static void diff(final Editor editor, final Buffer parentBuffer, File file)
    {
        final String baseCmd = "svn diff ";
        final String name = file.getName();
        final String title = baseCmd + maybeQuote(name);
        boolean save = false;
        if (parentBuffer.isModified())
          {
            int response =
              ConfirmDialog.showConfirmDialogWithCancelButton(editor,
                                                              CHECK_SAVE_PROMPT,
                                                              "SVN diff");
            switch (response)
              {
              case RESPONSE_YES:
                save = true;
                break;
              case RESPONSE_NO:
                break;
              case RESPONSE_CANCEL:
                return;
              }
            editor.repaintNow();
          }
        editor.setWaitCursor();
        if (!save || parentBuffer.save())
          {
            // Kill existing diff output buffer if any for same parent buffer.
            for (BufferIterator it = new BufferIterator(); it.hasNext();)
              {
                Buffer b = it.nextBuffer();
                if (b instanceof DiffOutputBuffer)
                  {
                    if (b.getParentBuffer() == parentBuffer)
                      {
                        editor.maybeKillBuffer(b);
                        break; // There should be one at most.
                      }
                  }
              }
            final String cmd = baseCmd + maybeQuote(file.canonicalPath());
            Runnable commandRunnable = new Runnable()
              {
                public void run()
                {
                  final String output =
                    command(cmd, parentBuffer.getCurrentDirectory());
                  Runnable completionRunnable = new Runnable()
                    {
                      public void run()
                      {
                        diffCompleted(editor, parentBuffer, title, output, VC_SVN);
                      }
                    };
                  SwingUtilities.invokeLater(completionRunnable);
                }
              };
            new Thread(commandRunnable).start();
          }
    }

    public static void diffDif()
    {
        if (!checkSVNInstalled())
            return;
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        editor.setWaitCursor();
        final String cmd = "svn diff";
        final File directory = buffer.getCurrentDirectory();
        // Kill existing diff output buffer if any for same directory.
        for (BufferIterator it = new BufferIterator(); it.hasNext();)
          {
            Buffer b = it.nextBuffer();
            if (b instanceof DiffOutputBuffer)
              {
                if (directory.equals(((DiffOutputBuffer) b).getDirectory()))
                  {
                    b.kill();
                    break; // There should be one at most.
                  }
              }
          }
        final DiffOutputBuffer buf = new DiffOutputBuffer(directory, null, VC_SVN);
        buf.setTitle(cmd);
        editor.makeNext(buf);
        Editor ed = editor.activateInOtherWindow(buf);
        ed.setWaitCursor();
        buf.setBusy(true);
        Runnable commandRunnable = new Runnable()
          {
            public void run()
            {
              final String output = command(cmd, directory);
              Runnable completionRunnable = new Runnable()
                {
                  public void run()
                  {
                    processCompleted(buf, output);
                  }
                };
              SwingUtilities.invokeLater(completionRunnable);
            }
          };
        new Thread(commandRunnable).start();
    }

    public static void log()
    {
        log("");
    }

    public static void log(String args)
    {
        if (!checkSVNInstalled())
            return;
        final Editor editor = Editor.currentEditor();
        final Buffer parentBuffer = editor.getBuffer();
        String cmd = parseArgs("svn log", args, true, true);
        outputBufferCommand(editor, cmd, parentBuffer.getCurrentDirectory());
    }

    public static void changelist()
    {
        changelist(null);
    }

    // arg is either a changelist name or null:
    // - if a changelist name, add the file to the changelist,
    // - if null, removes the file from it's current changelist.
    public static void changelist(String changelist)
    {
        if (!checkSVNInstalled())
            return;
        final Editor editor = Editor.currentEditor();
        final Buffer parentBuffer = editor.getBuffer();
        final File directory = parentBuffer.getCurrentDirectory();

        final FastStringBuffer sb = new FastStringBuffer();
        sb.append("svn changelist -q ");
        if (changelist != null)
            sb.append(changelist);
        else
            sb.append("--remove");
        sb.append(" ").append(maybeQuote(parentBuffer.getFile().getName()));
        final String cmd = sb.toString();
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

    public static void status()
    {
        if (!checkSVNInstalled())
            return;
        final Editor editor = Editor.currentEditor();
        final Buffer parentBuffer = editor.getBuffer();
        final File directory = parentBuffer.getCurrentDirectory();
        editor.setWaitCursor();
        // Kill existing status output buffer if any for same directory.
        for (BufferIterator it = new BufferIterator(); it.hasNext();)
          {
            Buffer b = it.nextBuffer();
            if (b instanceof VersionControlBuffer)
              {
                if (directory.equals(((VersionControlBuffer) b).getDirectory()))
                  {
                    b.kill();
                    break; // There should be one at most.
                  }
              }
          }
        final String cmd = "svn status";
        final VersionControlBuffer buf = new StatusOutputBuffer(directory, null, VC_SVN);
        buf.setTitle(cmd);
        editor.makeNext(buf);
        Editor ed = editor.activateInOtherWindow(buf);
        ed.setWaitCursor();
        buf.setBusy(true);
        Runnable commandRunnable = new Runnable()
        {
            public void run()
            {
                final String output = command(cmd, directory);
                Runnable completionRunnable = new Runnable()
                {
                    public void run()
                    {
                        processCompleted(buf, output);
                    }
                };
                SwingUtilities.invokeLater(completionRunnable);
            }
        };
        new Thread(commandRunnable).start();
    }

    public static void commit()
    {
        _commit(null);
    }

    public static void commit(String args)
    {
        List list = Utilities.tokenize(args);
        if (list.size() == 2)
          {
            String arg = (String) list.get(0);
            if (arg.equals("--cl") || arg.equals("--changelist"))
              {
                arg = (String) list.get(1);
                _commit(arg);
              }
          }
        FastStringBuffer sb = new FastStringBuffer("Unrecognized argument \"");
        sb.append(args.trim());
        sb.append('"');
        MessageDialog.showMessageDialog(sb.toString(), "Error");
    }

    private static void _commit(String changelist)
    {
        if (!checkSVNInstalled())
            return;
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        final Buffer parentBuffer;
        if (buffer instanceof DiffOutputBuffer)
          parentBuffer = buffer.getParentBuffer();
        else
          parentBuffer = buffer;
        FastStringBuffer sb = new FastStringBuffer("svn commit");
        if (changelist != null)
          {
            sb.append(" --changelist ");
            sb.append(changelist);
          }
        else
          {
            sb.append(parentBuffer.getFile().getName());
          }
        final String title = sb.toString();
        boolean save = false;
        List list = null;
        if (changelist != null)
            list = getModifiedBuffers();
        else if (parentBuffer.isModified())
            list = Collections.singletonList(parentBuffer);
        if (list != null && list.size() > 0)
          {
            int response =
              ConfirmDialog.showConfirmDialogWithCancelButton(editor,
                                                              list.size() == 1 ? CHECK_SAVE_PROMPT : "Save modified buffers first?",
                                                              title);
            switch (response)
              {
              case RESPONSE_YES:
                save = true;
                break;
              case RESPONSE_NO:
                break;
              case RESPONSE_CANCEL:
                return;
              }
            editor.repaintNow();
          }
        if (!save || saveModifiedBuffers(editor, list))
          {
            // Look for existing checkin buffer before making a new one.
            SVNCheckinBuffer checkinBuffer = null;
            for (BufferIterator it = new BufferIterator(); it.hasNext();)
              {
                Buffer buf = it.nextBuffer();
                if (buf instanceof SVNCheckinBuffer)
                  {
                    if (buf.getParentBuffer() == parentBuffer)
                      {
                        checkinBuffer = (SVNCheckinBuffer) buf;
                        break;
                      }
                  }
              }
            if (checkinBuffer == null)
              {
                checkinBuffer = new SVNCheckinBuffer(parentBuffer, VC_SVN);
                checkinBuffer.setChangelist(changelist);
                checkinBuffer.setFormatter(new PlainTextFormatter(checkinBuffer));
                checkinBuffer.setTitle(title);
              }
            editor.makeNext(checkinBuffer);
            editor.activateInOtherWindow(checkinBuffer);
        }
    }

    private static class SVNCheckinBuffer extends CheckinBuffer
    {
        private String changelist;

        public SVNCheckinBuffer(Buffer parentBuffer, int vcType) {
            super(parentBuffer, vcType);
        }

        public void setChangelist(String cl)
        {
            changelist = cl;
        }

        public String getChangelist()
        {
            return changelist;
        }
    }

    public static void replaceComment(final Editor editor, final String comment)
    {
        CVS.replaceComment(editor, comment);
    }

    public static String extractComment(CheckinBuffer cb)
    {
        return CVS.extractComment(cb);
    }

    public static void finish(final Editor editor, final CheckinBuffer cb)
    {
        final SVNCheckinBuffer checkinBuffer = (SVNCheckinBuffer)cb;
        final Buffer parentBuffer = checkinBuffer.getParentBuffer();

        final String cmd;
        if (checkinBuffer.getChangelist() != null)
        {
            cmd = "svn commit --changelist " + checkinBuffer.getChangelist();
        }
        else
        {
            File file = parentBuffer.getFile();
            if (file == null)
                Debug.bug();
            cmd = "svn commit " + maybeQuote(file.canonicalPath());
        }

        checkinBuffer.setBusy(true);
        parentBuffer.setBusy(true);
        editor.setWaitCursor();
        final String input = checkinBuffer.getText();
        final ShellCommand shellCommand =
                new ShellCommand(cmd, parentBuffer.getCurrentDirectory(), input);
        Runnable commandRunnable = new Runnable()
        {
            public void run()
            {
                shellCommand.run();
                if (shellCommand.exitValue() != 0)
                {
                    Log.error("SVN.finish input = |" + input + "|");
                    Log.error("SVN.finish exit value = " + shellCommand.exitValue());
                }
                else
                {
                    editor.otherWindow();
                    editor.unsplitWindow();
                    checkinBuffer.kill();
                }
                // UNDONE: consider killing diff and output buffers like P4
                // UNDONE: show output of svn if commit fails
                parentBuffer.checkVCS();
                parentBuffer.setBusy(false);
                for (EditorIterator it = new EditorIterator(); it.hasNext();)
                  {
                    Editor ed = it.nextEditor();
                    if (ed.getBuffer().isBusy())
                      ed.setWaitCursor();
                    else
                      ed.setDefaultCursor();
                    // Update SVN information in status bar.
                    if (ed.getBuffer() == parentBuffer)
                      ed.getFrame().repaintStatusBar();
                  }
                Editor.restoreFocus();
            }
        };
        new Thread(commandRunnable).start();
    }

    protected static boolean checkSVNInstalled()
    {
        if (haveSVN())
            return true;
        MessageDialog.showMessageDialog(
            "The Subversion command-line client does not appear to be in your PATH.",
            "Error");
        return false;
    }

    private static int haveSVN = -1;

    private static boolean haveSVN()
    {
        if (haveSVN > 0)
            return true;
        if (Utilities.have("svn")) {
            haveSVN = 1;
            return true;
        }
        return false;
    }
}