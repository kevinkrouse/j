/*
 * RemoteShellBuffer.java
 *
 * Copyright (C) 2013 Kevin Krouse
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.armedbear.j;

import java.lang.StringBuilder;
import org.armedbear.j.util.Utilities;

import java.io.OutputStreamWriter;
import javax.swing.SwingUtilities;

public class RemoteShellBuffer extends ShellBuffer
{
    private String host;

    private RemoteShellBuffer(int type, String host)
    {
        super();
        if (type != TYPE_TELNET && type != TYPE_SSH)
            throw new NotSupportedException();
        this.type = type;
        this.host = host;
        if (type == TYPE_TELNET) {
            shellCommand = Editor.preferences().getStringProperty(Property.TELNET);
            if (shellCommand == null)
                shellCommand = "telnet";
        } else if (type == TYPE_SSH) {
            shellCommand = Editor.preferences().getStringProperty(Property.SSH);
            if (shellCommand == null)
                shellCommand = "ssh";
        }
        title = shellCommand + " " + host;
    }

    // Called in ShellBuffer constructor, so we override it here.
    protected void initializeHistory()
    {
        history = new History("remoteShell.history");
    }

    protected void startProcess()
    {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(Utilities.jptyPath() + " " + shellCommand + " " + host);
            setProcess(process);
        }
        catch (Throwable t) {
            setProcess(null);
            return;
        }
        startWatcherThread();
        // See if the process exits right away (meaning jpty couldn't launch
        // the program).
        try {
            Thread.sleep(100);
        }
        catch (InterruptedException e) {
            Log.error(e);
        }
        // When the process exits, the watcher thread calls setProcess(null),
        // so check the value of getProcess() here.
        if (getProcess() == null)
            return; // Process exited.
        Property property = switch (type) {
            case TYPE_TELNET -> Property.TELNET_PROMPT_PATTERN;
            case TYPE_SSH -> Property.SSH_PROMPT_PATTERN;
            default -> null;
        };
        if (property != null)
            setPromptRE(Editor.preferences().getStringProperty(property));
        try {
            stdin  = new OutputStreamWriter(process.getOutputStream());
            stdoutThread = new StdoutThread(process.getInputStream());
            stderrThread = new StderrThread(process.getErrorStream());
            stdoutThread.start();
            stderrThread.start();
            readOnly = false;
        }
        catch (Throwable t) {
            Log.error(t);
        }
    }

    private static RemoteShellBuffer createRemoteShell(int type, String host)
    {
        RemoteShellBuffer remoteShell = new RemoteShellBuffer(type, host);
        remoteShell.startProcess();
        if (remoteShell.getProcess() == null) {
            Editor.getBufferList().remove(remoteShell);
            String program = switch (type) {
                case TYPE_TELNET -> "telnet";
                case TYPE_SSH -> "ssh";
                default -> "client"; // A nice generic name.
            };
            String message;
            if (Utilities.haveJpty())
                message = "Unable to start " + program + " process";
            else
                message = "Unable to start " + program + " process (jpty not found in PATH)";
            MessageDialog.showMessageDialog(Editor.currentEditor(), message, "Error");
            remoteShell = null;
        }
        return remoteShell;
    }

    private static RemoteShellBuffer findRemoteShell(int type, String host)
    {
        if (host == null)
            return null;
        for (BufferIterator it = new BufferIterator(); it.hasNext();) {
            Buffer buf = it.next();
            if (buf instanceof RemoteShellBuffer) {
                RemoteShellBuffer remoteShell = (RemoteShellBuffer) buf;
                if (type == remoteShell.getType())
                    if (host.equals(remoteShell.getHost()))
                        return remoteShell;
            }
        }
        return null;
    }

    private StringBuffer sbFilter;

    private String telnetStdOutFilter(String s)
    {
        if (stripEcho && input != null) {
            if (sbFilter == null)
                sbFilter = new StringBuffer(s);
            else {
                sbFilter.append(s);
                s = sbFilter.toString();
            }
            if (s.startsWith(input)) {
                s = stripEcho(s);
                stripEcho = false; // Strip echo only once per command line.
                sbFilter = null;
            } else
                s = ""; // Save output until we have enough to strip echo.
        }
        return s;
    }

    private String sshStdOutFilter(String s)
    {
        if (stripEcho && input != null) {
            if (s.startsWith(input)) {
                s = stripEcho(s);
                stripEcho = false; // Strip echo only once per command line.
            }
        }
        return s;
    }

    protected String stdOutFilter(String s)
    {
        if (type == TYPE_TELNET)
            return telnetStdOutFilter(s);
        if (type == TYPE_SSH)
            return sshStdOutFilter(s);
        return s;
    }

    private String stripEcho(String s)
    {
        if (s.startsWith(input)) {
            int begin = input.length();
            if (s.length() > begin && s.charAt(begin) == '\r')
                ++begin;
            if (s.length() > begin && s.charAt(begin) == '\n')
                ++begin;
            s = s.substring(begin);
        }
        return s;
    }

    protected void stdOutUpdate(final String s)
    {
        // Filter to prevent two carriage returns in a row.
        final StringBuilder sb = new StringBuilder(s.length());
        boolean skipCR = false;
        final int limit = s.length();
        for (int i = 0; i < limit; i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                if (skipCR)
                    skipCR = false;
                else {
                    sb.append(c);
                    skipCR = true;
                }
            } else {
                sb.append(c);
                skipCR = false;
            }
        }
        Runnable r = new Runnable() {
            public void run()
            {
                appendString(sb.toString());
                setEndOfOutput(new Position(getEnd()));
                updateLineFlags();
                updateDisplayInAllFrames();
                resetUndo();
                checkPasswordPrompt();
            }
        };
        SwingUtilities.invokeLater(r);
    }

    protected String stdErrFilter(String s)
    {
        return s;
    }

    private final String getHost()
    {
        return host;
    }

    public final File getCurrentDirectory()
    {
        return Directories.getUserHomeDirectory();
    }

    // For the buffer list.
    public String toString()
    {
        return title;
    }

    public String getTitle()
    {
        return title;
    }

    public static void telnet()
    {
        if (!Editor.checkExperimental())
            return;
        if (Platform.isPlatformWindows()) {
            if (Editor.preferences().getStringProperty(Property.TELNET) == null)
                return;
        }
        String host = InputDialog.showInputDialog(Editor.currentEditor(), "Host:", "telnet");
        if (host == null || host.length() == 0)
            return;
        telnet(host);
    }

    public static void telnet(String host)
    {
        if (!Editor.checkExperimental())
            return;
        if (Platform.isPlatformWindows()) {
            if (Editor.preferences().getStringProperty(Property.TELNET) == null)
                return;
        }
        RemoteShellBuffer remoteShell = findRemoteShell(TYPE_TELNET, host);
        if (remoteShell != null) {
            if (remoteShell.getProcess() == null)
                remoteShell.startProcess();
        } else
            remoteShell = createRemoteShell(TYPE_TELNET, host);
        if (remoteShell != null) {
            final Editor editor = Editor.currentEditor();
            editor.makeNext(remoteShell);
            editor.switchToBuffer(remoteShell);
        }
    }

    public static void ssh()
    {
        if (!Editor.checkExperimental())
            return;
        if (Platform.isPlatformWindows()) {
            if (Editor.preferences().getStringProperty(Property.SSH) == null)
                return;
        }
        String host = InputDialog.showInputDialog(Editor.currentEditor(), "Host:", "ssh");
        if (host == null || host.length() == 0)
            return;
        ssh(host);
    }

    public static void ssh(String host)
    {
        if (!Editor.checkExperimental())
            return;
        if (Platform.isPlatformWindows()) {
            if (Editor.preferences().getStringProperty(Property.SSH) == null)
                return;
        }
        RemoteShellBuffer remoteShell = RemoteShellBuffer.findRemoteShell(TYPE_SSH, host);
        if (remoteShell != null) {
            if (remoteShell.getProcess() == null)
                remoteShell.startProcess();
        } else
            remoteShell = RemoteShellBuffer.createRemoteShell(TYPE_SSH, host);
        if (remoteShell != null) {
            final Editor editor = Editor.currentEditor();
            editor.makeNext(remoteShell);
            editor.switchToBuffer(remoteShell);
        }
    }
}
