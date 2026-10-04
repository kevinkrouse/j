/*
 * NewsGroupSummary.java
 *
 * Copyright (C) 2000-2003 Peter Graves
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

import static org.armedbear.j.Constants.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.InputDialog;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.MessageDialog;
import org.armedbear.j.Position;
import org.armedbear.j.ProgressNotifier;
import org.armedbear.j.StatusBarProgressNotifier;
import org.armedbear.j.View;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.FastStringReader;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Utilities;

public final class NewsGroupSummaryBuffer extends MailboxBuffer {
    private final NntpSession session;
    private final String groupName;
    private final HashMap<String, String> map = new HashMap<>();

    private ProgressNotifier progressNotifier;
    private String errorText;
    private int numberToGet;

    public NewsGroupSummaryBuffer(NntpSession session, String groupName) {
        super();
        this.session = session;
        this.groupName = groupName;
        supportsUndo = false;
        type = TYPE_MAILBOX;
        mode = NewsGroupSummaryMode.getMode();
        formatter = mode.getFormatter(this);
        readOnly = true;
        title = groupName;
        progressNotifier = new StatusBarProgressNotifier(this);
        setInitialized(true);
    }

    public final NntpSession getSession() {
        return session;
    }

    @Override
    public final String getName() {
        return groupName;
    }

    @Override
    public int load() {
        setBusy(true);
        Background.start("NewsGroupSummaryBuffer load", loadRunnable);
        setLoaded(true);
        return LOAD_COMPLETED;
    }

    private Runnable loadRunnable = new Runnable() {
        @Override
        public void run() {
            if (!session.connect()) {
                errorText = session.getErrorText();
                SwingUtilities.invokeLater(errorRunnable);
                return;
            }
            if (!selectGroup()) {
                errorText = "No group \"" + groupName + '\"';
                SwingUtilities.invokeLater(errorRunnable);
                return;
            }
            final int count = session.getCount();
            if (count == 0) {
                errorText = "No articles";
                SwingUtilities.invokeLater(errorRunnable);
                return;
            }
            if (count > 100) {
                Runnable confirmRunnable = () -> {
                    Editor editor = Editor.currentEditor();
                    editor.setDefaultCursor();
                    String prompt = "How many headers would you like?";
                    String defaultValue = String.valueOf(count);
                    String response =
                        InputDialog.showInputDialog(
                            editor,
                            prompt,
                            groupName,
                            defaultValue
                        );
                    editor.setWaitCursor();
                    numberToGet = 0;
                    if (response != null && response.length() > 0) {
                        try {
                            numberToGet = Integer.parseInt(response);
                        }
                        catch (NumberFormatException e) {
                            Log.error(e);
                        }
                    }
                };
                try {
                    SwingUtilities.invokeAndWait(confirmRunnable);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                catch (InvocationTargetException e) {
                    Log.error(e);
                }
            } else
                numberToGet = count;
            if (numberToGet == 0) {
                SwingUtilities.invokeLater(errorRunnable);
                return;
            }
            getHeaders();
            if (entries != null && entries.size() > 0) {
                addEntriesToBuffer();
                SwingUtilities.invokeLater(updateDisplayRunnable);
            } else {
                errorText = "No articles";
                SwingUtilities.invokeLater(errorRunnable);
            }
        }
    };

    private Runnable updateDisplayRunnable = new Runnable() {
        @Override
        public void run() {
            setBusy(false);
            invalidate();
            for (Editor ed : Editor.getEditorList()) {
                if (ed.getBuffer() == NewsGroupSummaryBuffer.this) {
                    ed.setDot(getInitialDotPos());
                    ed.moveCaretToDotCol();
                    ed.setTopLine(getFirstLine());
                    ed.setUpdateFlag(REPAINT);
                    ed.updateDisplay();
                }
            }
        }
    };

    private Runnable errorRunnable = new Runnable() {
        @Override
        public void run() {
            Editor editor = Editor.currentEditor();
            editor.setDefaultCursor();
            if (errorText != null)
                MessageDialog.showMessageDialog(errorText, "Error");
            if (Editor.getBufferList().contains(NewsGroupSummaryBuffer.this))
                kill();
            for (Editor ed : Editor.getEditorList())
                ed.updateDisplay();
        }
    };

    public void readArticle(Editor editor, Line line, boolean useOtherWindow) {
        if (line instanceof MailboxLine mailboxLine) {
            editor.setMark(null);
            NewsGroupSummaryEntry entry =
                (NewsGroupSummaryEntry) mailboxLine.getMailboxEntry();
            NewsGroupMessageBuffer mb = new NewsGroupMessageBuffer(this, entry);
            activateMessageBuffer(editor, mb, useOtherWindow);
        }
    }

    public String getArticle(int articleNumber, ProgressNotifier progressNotifier) {
        String key = String.valueOf(articleNumber);
        String filename = map.get(key);
        if (filename != null) {
            File file = File.getInstance(filename);
            if (file != null) {
                try {
                    MailReader reader = new MailReader(file.getInputStream());
                    long length = file.length();
                    StringBuilder sb = new StringBuilder((int) length);
                    String s;
                    while ((s = reader.readLine()) != null) {
                        sb.append(s);
                        sb.append('\n');
                    }
                    return sb.toString();
                }
                catch (IOException e) {
                    Log.error(e);
                }
            }
        }
        String text = session.getArticle(articleNumber, progressNotifier);
        if (text != null) {
            File file = Utilities.getTempFile();
            try {
                FastStringReader reader = new FastStringReader(text);
                try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(
                        file.getOutputStream(),
                        "ISO-8859-1"
                    )
                )) {
                    String s;
                    while ((s = reader.readLine()) != null) {
                        writer.write(s);
                        writer.write('\n');
                    }
                    writer.flush();
                }
            }
            catch (IOException e) {
                Log.error(e);
            }
            map.put(key, file.canonicalPath());
        }
        return text;
    }

    private void addEntriesToBuffer() {
        if (entries != null) {
            withWriteLock(() -> {
                int limit = entries.size();
                for (int i = 0; i < limit; i++)
                    appendLine((NewsGroupSummaryEntry) entries.get(i));
                renumber();
            });
        }
    }

    @Override
    public Position getInitialDotPos() {
        return getFirstLine() != null ? new Position(getFirstLine(), 0) : null;
    }

    private boolean selectGroup() {
        return session.selectGroup(groupName);
    }

    private void getHeaders() {
        int last = session.getLast();
        int first = session.getFirst();
        if (last <= first)
            return;
        if (last - first > numberToGet)
            first = last - numberToGet;
        entries = new ArrayList<>();
        if (session.writeLine("XOVER " + first + "-" + last)) {
            String s = session.readLine();
            if (s.startsWith("224")) {
                progressNotifier.progressStart();
                int count = 0;
                while (true) {
                    s = session.readLine();
                    if (s == null)
                        break;
                    if (s.equals("."))
                        break;
                    NewsGroupSummaryEntry entry =
                        NewsGroupSummaryEntry.parseOverviewEntry(s);
                    if (entry != null)
                        entries.add(entry);
                    ++count;
                    progressNotifier.progress("Received " + count + " headers");
                }
                progressNotifier.progressStop();
            } else
                Log.error("XOVER response = " + s);
        }
    }

    @Override
    public void dispose() {
        if (session != null) {
            Runnable r = () -> {
                session.disconnect();
            };
            Background.start("NewsGroupSummaryBuffer", r);
        }
    }

    // For the buffer list.
    @Override
    public Icon getIcon() {
        return Icons.getIconFromFile("mailbox");
    }

    @Override
    public void getNewMessages() {}

    @Override
    public void readMessage(Line line) {}

    @Override
    public void createFolder() {}

    @Override
    public void deleteFolder() {}

    @Override
    public void saveToFolder() {}

    @Override
    public void moveToFolder() {}

    @Override
    public void delete() {}

    @Override
    public void undelete() {}

    @Override
    public void markRead() {}

    @Override
    public void markUnread() {}

    @Override
    public void setAnsweredFlag(MailboxEntry entry) {}

    @Override
    public void expunge() {}

    @Override
    public int getMessageCount() {
        return 0;
    }

    @Override
    public void saveView(Editor editor) {
        final View view = saveViewInternal(editor);
        editor.setView(this, view);
        setLastView(view);
    }
}
