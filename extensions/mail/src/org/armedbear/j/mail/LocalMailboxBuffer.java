/*
 * LocalMailbox.java
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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Debug;
import org.armedbear.j.Directories;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Headers;
import org.armedbear.j.Line;
import org.armedbear.j.LocalFile;
import org.armedbear.j.Log;
import org.armedbear.j.ProgressNotifier;
import org.armedbear.j.Property;
import org.armedbear.j.Sidebar;
import org.armedbear.j.View;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Utilities;

public class LocalMailboxBuffer extends MailboxBuffer {
    @Override
    public String getAliasValue() {
        return "mailbox:" + getMailboxFile().netPath();
    }

    private File mailboxFile;

    public LocalMailboxBuffer(MailboxURL url) {
        super(url);
        if (url instanceof LocalMailboxURL localMailboxUrl)
            mailboxFile = localMailboxUrl.getFile();
        init();
    }

    private void init() {
        supportsUndo = false;
        type = TYPE_MAILBOX;
        mode = MailboxMode.getMode();
        formatter = mode.getFormatter(this);
        readOnly = true;
        if (mailboxFile != null)
            title = mailboxFile.canonicalPath();
        setInitialized(true);
    }

    @Override
    public String getName() {
        Debug.assertTrue(mailboxFile != null);
        return mailboxFile.canonicalPath();
    }

    public final File getMailboxFile() {
        return mailboxFile;
    }

    public final void setMailboxFile(File mailboxFile) {
        this.mailboxFile = mailboxFile;
    }

    @Override
    public int getMessageCount() {
        if (entries == null)
            return 0;
        return entries.size();
    }

    @Override
    public Message getMessage(MailboxEntry entry, ProgressNotifier progressNotifier) {
        try {
            RandomAccessFile raf = mailboxFile.getRandomAccessFile("r");
            String header = getMessageHeader(entry, raf);
            Headers headers = Headers.parse(header);
            String charset = null;
            String contentType = headers.getValue(Headers.CONTENT_TYPE);
            if (contentType != null)
                charset = Utilities.getCharsetFromContentType(contentType);
            String body = getMessageBody(entry, raf, charset);
            return new Message(header + "\r\n" + body, headers);
        }
        catch (IOException e) {
            Log.error(e);
            return null;
        }
    }

    private String getMessageHeader(MailboxEntry entry, RandomAccessFile raf) {
        StringBuilder sb = new StringBuilder(8192);
        try {
            long offset = ((LocalMailboxEntry) entry).getMessageStart();
            raf.seek(offset);
            String text = raf.readLine();
            if (!text.startsWith("From ")) {
                Log.debug("LocalMailboxBuffer.getMessage expected \"From \"");
                Log.debug("text = |" + text + "|");
                Log.debug("offset = " + offset);
                Debug.assertTrue(false);
                return ""; // BUG!
            }
            while (true) {
                text = raf.readLine();
                if (text == null)
                    break; // End of file (shouldn't happen).
                if (text.length() == 0)
                    break; // Reached end of header.
                sb.append(text);
                sb.append("\r\n");
            }
        }
        catch (IOException e) {
            Log.error(e);
        }
        return sb.toString();
    }

    // File pointer must be at the start of the message body.
    private String getMessageBody(MailboxEntry entry, RandomAccessFile raf, String charset) {
        byte[] bytes = null;
        try {
            long bodyStart = raf.getFilePointer();
            long size = ((LocalMailboxEntry) entry).getNextMessageStart() - bodyStart;
            Debug.assertTrue(size >= 0);
            Debug.assertTrue(size < Integer.MAX_VALUE);
            bytes = new byte[(int) size];
            raf.readFully(bytes);
        }
        catch (IOException e) {
            Log.error(e);
            return "";
        }
        try {
            return new String(bytes, Utilities.getEncodingFromCharset(charset));
        }
        catch (UnsupportedEncodingException e) {
            Log.error(e);
        }
        // An unknown charset: read it as UTF-8.
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void getNewMessages() {
        Log.error("LocalMailboxBuffer.getNewMessages is not implemented");
    }

    @Override
    public void createFolder() {
        Log.error("LocalMailboxBuffer.createFolder is not implemented");
    }

    @Override
    public void deleteFolder() {
        Log.error("LocalMailboxBuffer.deleteFolder is not implemented");
    }

    @Override
    public void saveToFolder() {
        Log.error("LocalMailboxBuffer.saveToFolder is not implemented");
    }

    @Override
    public void moveToFolder() {
        Log.error("LocalMailboxBuffer.moveToFolder is not implemented");
    }

    @Override
    public void delete() {
        changeEntries(true, true, true, entry -> setFlag(entry, MailboxEntry.DELETED, true));
    }

    @Override
    public void undelete() {
        boolean advance = getBooleanProperty(Property.UNDELETE_ADVANCE_DOT);
        changeEntries(true, advance, true, entry -> setFlag(entry, MailboxEntry.DELETED, false));
    }

    @Override
    public void markRead() {
        changeEntries(false, true, true, entry -> setFlag(entry, MailboxEntry.SEEN, true));
    }

    @Override
    public void markUnread() {
        changeEntries(false, true, true, entry -> setFlag(entry, MailboxEntry.SEEN, false));
    }

    @Override
    public void flag() {
        changeEntries(false, true, false, entry -> {
            entry.toggleFlag();
            return true;
        });
    }

    // Sets or clears flag; false if it was already so.
    private static boolean setFlag(MailboxEntry entry, int flag, boolean on) {
        int flags = on ? entry.getFlags() | flag : entry.getFlags() & ~flag;
        if (flags == entry.getFlags())
            return false;
        entry.setFlags(flags);
        return true;
    }

    /**
     * Applies change to the tagged entries or, if none are, the caret's, then
     * moves the caret to the next message if advance. change says whether it
     * changed the entry. recount updates the message counts.
     */
    private void changeEntries(
        boolean waitCursor,
        boolean advance,
        boolean recount,
        Predicate<MailboxEntry> change
    ) {
        Editor editor = Editor.currentEditor();
        if (!lock()) {
            editor.status("Mailbox is locked");
            return;
        }
        try {
            if (waitCursor)
                editor.setWaitCursor();
            List<MailboxEntry> list = getTaggedEntries();
            if (list == null) {
                Line line = editor.getDotLine();
                if (!(line instanceof MailboxLine mailboxLine))
                    return;
                list = List.of(mailboxLine.getMailboxEntry());
            } else
                advance = false;
            for (MailboxEntry entry : list) {
                if (change.test(entry)) {
                    dirty = true;
                    updateEntry(entry);
                }
            }
            if (recount) {
                countMessages();
                // Update message count in sidebar buffer list.
                Sidebar.repaintBufferListInAllFrames();
            }
            if (advance)
                advanceDot(editor.getDotLine());
        }
        finally {
            unlock();
        }
    }

    @Override
    public void setAnsweredFlag(MailboxEntry entry) {
        if ((entry.getFlags() & MailboxEntry.ANSWERED) == 0) {
            entry.setFlags(entry.getFlags() | MailboxEntry.ANSWERED);
            setDirty(true);
            updateEntry(entry);
        }
    }

    @Override
    public void expunge() {
        Log.error("LocalMailboxBuffer.expunge is not implemented");
    }

    @Override
    public int load() {
        if (lock()) {
            setBusy(true);
            Background.start("LocalMailboxBuffer load", loadRunnable);
            setLoaded(true);
            return LOAD_PENDING;
        } else
            return LOAD_FAILED;
    }

    private Runnable loadRunnable = () -> {
        try {
            readMailboxFile(null);
            refreshBuffer();
        }
        finally {
            unlock();
            setBusy(false);
            Runnable completionRunnable = () -> {
                for (Editor ed : Editor.getEditorList()) {
                    View view = new View();
                    view.setDotEntry(getInitialEntry());
                    ed.setView(LocalMailboxBuffer.this, view);
                    if (ed.getBuffer() == LocalMailboxBuffer.this) {
                        ed.bufferActivated(true);
                        ed.updateDisplay();
                    }
                }
            };
            SwingUtilities.invokeLater(completionRunnable);
        }
    };

    protected void readMailboxFile(ProgressNotifier progressNotifier) {
        Log.debug("LocalMailboxBuffer.readMailboxFile");
        long start = System.currentTimeMillis();
        Mbox mbox = Mbox.getInstance(mailboxFile);
        if (mbox == null)
            return;
        if (mbox.lock()) {
            entries = mbox.getEntries(progressNotifier);
            mbox.unlock();
        }
        Log.debug("readMailboxFile " + (System.currentTimeMillis() - start) + " ms");
    }

    @Override
    public void readMessage(Line line) {
        readMessage(line, false);
    }

    @Override
    public void readMessageOtherWindow(Line line) {
        readMessage(line, true);
    }

    private void readMessage(Line line, boolean useOtherWindow) {
        Editor editor = Editor.currentEditor();
        MailboxEntry entry = ((MailboxLine) line).getMailboxEntry();
        Buffer buf = null;
        for (Buffer b : Editor.getBufferList()) {
            if (b instanceof MessageBuffer messageBuffer) {
                if (messageBuffer.getMailboxEntry() == entry) {
                    buf = b;
                    break;
                }
            }
        }
        if (buf == null)
            buf = new LocalMessageBuffer(this, entry);
        activateMessageBuffer(editor, (MessageBuffer) buf, useOtherWindow);
    }

    protected boolean rewriteMailbox(boolean purge) {
        Log.debug("rewriteMailbox");
        long start = System.currentTimeMillis();
        boolean succeeded = false;
        try {
            BufferedReader reader =
                new BufferedReader(
                    new InputStreamReader(
                        mailboxFile.getInputStream(),
                        "ISO8859_1"
                    )
                );
            File tempFile =
                Utilities.getTempFile(mailboxFile.getParentFile());
            BufferedWriter writer =
                new BufferedWriter(
                    new OutputStreamWriter(
                        tempFile.getOutputStream(),
                        "ISO8859_1"
                    )
                );
            long newOffsets[] = new long[entries.size()];
            long offset = 0;
            boolean skip = false;
            int i = 0;
            while (true) {
                String text = reader.readLine();
                if (text == null)
                    break;
                if (text.startsWith("From ")) {
                    if (purge)
                        skip = entries.get(i).isDeleted();
                    else
                        skip = false;
                    if (skip)
                        newOffsets[i] = -1;
                    else {
                        newOffsets[i] = offset;
                        writer.write(text);
                        writer.write('\n');
                        offset += text.length() + 1;
                    }
                    // Headers.
                    boolean seenXJStatus = false;
                    while (true) {
                        text = reader.readLine();
                        if (text == null)
                            break;
                        if (text.length() == 0)
                            break; // End of headers.
                        if (!skip) {
                            if (text.startsWith("X-J-Status: ")) {
                                text = "X-J-Status: " + getMessageStatus(i);
                                seenXJStatus = true;
                            }
                            writer.write(text);
                            writer.write('\n');
                            offset += text.length() + 1;
                        }
                    }
                    if (!skip) {
                        if (!seenXJStatus) {
                            writer.write("X-J-Status: " + getMessageStatus(i));
                            writer.write('\n');
                        }
                        writer.write('\n');
                        offset += 1;
                    }
                    ++i;
                } else {
                    // Body of message.
                    if (!skip) {
                        writer.write(text);
                        writer.write('\n');
                        offset += text.length() + 1;
                    }
                }
            }
            writer.flush();
            writer.close();
            reader.close();
            if (Utilities.deleteRename(tempFile, mailboxFile)) {
                // Update offsets.
                for (i = 0; i < entries.size(); i++) {
                    LocalMailboxEntry entry = (LocalMailboxEntry) entries.get(i);
                    long newOffset = newOffsets[i];
                    if (newOffset == -1) {
                        if (!entry.isDeleted())
                            Debug.bug("expected entry " + i + " to be deleted");
                    }
                    entry.setMessageStart(newOffset);
                }
                if (purge) {
                    // Copy entries to new list, skipping deleted entries.
                    ArrayList<MailboxEntry> v =
                        new ArrayList<>(entries.size());
                    for (i = 0; i < entries.size(); i++) {
                        MailboxEntry entry = entries.get(i);
                        if (!entry.isDeleted())
                            v.add(entry);
                    }
                    v.trimToSize();
                    entries = v;
                }
                // Update nextMessageStart for every entry.
                LocalMailboxEntry thisEntry = null;
                LocalMailboxEntry lastEntry = null;
                for (i = 0; i < entries.size(); i++) {
                    thisEntry = (LocalMailboxEntry) entries.get(i);
                    if (lastEntry != null)
                        lastEntry.setNextMessageStart(thisEntry.getMessageStart());
                    lastEntry = thisEntry;
                }
                if (thisEntry != null)
                    thisEntry.setNextMessageStart(offset);
                // Success!
                dirty = false;
                succeeded = true;
            }
        }
        catch (IOException e) {
            Log.error(e);
        }
        finally {
            long elapsed = System.currentTimeMillis() - start;
            Log.debug("rewriteMailbox " + elapsed + " ms");
        }
        return succeeded;
    }

    private final int getMessageStatus(int i) {
        return entries.get(i).getFlags();
    }

    private static String localPrefix;

    private boolean isOwned() {
        if (localPrefix == null)
            localPrefix = Directories.getMailDirectory().canonicalPath().concat(LocalFile.getSeparator());
        return mailboxFile.canonicalPath().startsWith(localPrefix);
    }

    @Override
    public void dispose() {
        Log.debug("LocalMailboxBuffer.dispose");
        Mbox.cleanup();
        MailboxProperties.saveProperties(this);
        if (isOwned()) {
            Log.debug("mailbox is owned");
        } else {
            Log.debug("mailbox is foreign");
            return;
        }
        Runnable disposeRunnable = () -> {
            try {
                Log.debug("disposeRunnable.run() calling acquire()...");
                acquire(); // Blocks, may throw InterruptedException.
                Log.debug("disposeRunnable.run() back from acquire()");
                clearRecent();
                if (dirty) {
                    final Object pending = new Object();
                    Editor.getPendingOperations().add(pending);
                    Log.debug("disposeRunnable.run() calling rewriteMailbox()...");
                    rewriteMailbox(false);
                    Log.debug("disposeRunnable.run() back from rewriteMailbox()");
                    Editor.getPendingOperations().remove(pending);
                }
                release();
                Log.debug("disposeRunnable.run() back from release()");
            }
            catch (InterruptedException e) {
                Log.error(e);
            }
        };
        Background.start("LocalMailboxBuffer dispose", disposeRunnable);
    }

    @Override
    public String toString() {
        final String name;
        if (isOwned())
            name = mailboxFile.getParentFile().getName();
        else if (mailboxFile.isLocal())
            name = mailboxFile.canonicalPath();
        else
            name = mailboxFile.netPath();
        final int newMessageCount = getNewMessageCount();
        if (newMessageCount > 0) {
            StringBuilder sb = new StringBuilder(name);
            sb.append(" (");
            sb.append(newMessageCount);
            sb.append(" new)");
            return sb.toString();
        } else
            return name;
    }
}
