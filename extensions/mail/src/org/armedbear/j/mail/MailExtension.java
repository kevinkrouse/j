/*
 * MailExtension.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mail;

import org.armedbear.j.Aliases;
import org.armedbear.j.Buffer;
import org.armedbear.j.Debug;
import org.armedbear.j.Directories;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Log;
import org.armedbear.j.Property;
import org.armedbear.j.ToolBar;
import org.armedbear.j.ToolBarIcon;
import org.armedbear.j.Version;
import org.armedbear.j.extension.Extension;
import org.armedbear.j.extension.ExtensionContext;
import org.armedbear.j.extension.Opener;

/** Mail and news. Its modes are a ModeProvider service of their own. */
public final class MailExtension implements Extension {
    @Override
    public String getName() {
        return "mail";
    }

    @Override
    public String getVersion() {
        return Version.getVersion();
    }

    @Override
    public void initialize(ExtensionContext context) {
        context.registerCommand("attachFile", MailCommands.class, "attachFile");
        context.registerCommand("bounce", MailCommands.class, "bounce");
        context.registerCommand("ccGroup", MailCommands.class, "ccGroup");
        context.registerCommand("compose", MailCommands.class, "compose");
        context.registerCommand("foldThread", MailCommands.class, "foldThread");
        context.registerCommand("foldThreads", MailCommands.class, "foldThreads");
        context.registerCommand("inbox", MailCommands.class, "inbox");
        context.registerCommand("mailboxCreateFolder", MailCommands.class, "mailboxCreateFolder");
        context.registerCommand("mailboxDelete", MailCommands.class, "mailboxDelete");
        context.registerCommand("mailboxDeleteFolder", MailCommands.class, "mailboxDeleteFolder");
        context.registerCommand("mailboxExpunge", MailCommands.class, "mailboxExpunge");
        context.registerCommand("mailboxFlag", MailCommands.class, "mailboxFlag");
        context.registerCommand("mailboxGetNewMessages", MailCommands.class, "mailboxGetNewMessages");
        context.registerCommand("mailboxLastMessage", MailCommands.class, "mailboxLastMessage");
        context.registerCommand("mailboxLimit", MailCommands.class, "mailboxLimit");
        context.registerCommand("mailboxMarkRead", MailCommands.class, "mailboxMarkRead");
        context.registerCommand("mailboxMarkUnread", MailCommands.class, "mailboxMarkUnread");
        context.registerCommand("mailboxMoveToFolder", MailCommands.class, "mailboxMoveToFolder");
        context.registerCommand("mailboxReadMessage", MailCommands.class, "mailboxReadMessage");
        context.registerCommand("mailboxReadMessageOtherWindow", MailCommands.class, "mailboxReadMessageOtherWindow");
        context.registerCommand("mailboxSaveToFolder", MailCommands.class, "mailboxSaveToFolder");
        context.registerCommand("mailboxStop", MailCommands.class, "mailboxStop");
        context.registerCommand("mailboxTag", MailCommands.class, "mailboxTag");
        context.registerCommand("mailboxTagPattern", MailCommands.class, "mailboxTagPattern");
        context.registerCommand("mailboxToggleRaw", MailCommands.class, "mailboxToggleRaw");
        context.registerCommand("mailboxUndelete", MailCommands.class, "mailboxUndelete");
        context.registerCommand("mailboxUnlimit", MailCommands.class, "mailboxUnlimit");
        context.registerCommand("mailboxUntagAll", MailCommands.class, "mailboxUntagAll");
        context.registerCommand("messageDelete", MailCommands.class, "messageDelete");
        context.registerCommand("messageFlag", MailCommands.class, "messageFlag");
        context.registerCommand("messageForward", MailCommands.class, "messageForward");
        context.registerCommand("messageIndex", MailCommands.class, "messageIndex");
        context.registerCommand("messageMoveToFolder", MailCommands.class, "messageMoveToFolder");
        context.registerCommand("messageNext", MailCommands.class, "messageNext");
        context.registerCommand("messageNextInThread", MailCommands.class, "messageNextInThread");
        context.registerCommand("messageParent", MailCommands.class, "messageParent");
        context.registerCommand("messagePrevious", MailCommands.class, "messagePrevious");
        context.registerCommand("messagePreviousInThread", MailCommands.class, "messagePreviousInThread");
        context.registerCommand("messageReplyToGroup", MailCommands.class, "messageReplyToGroup");
        context.registerCommand("messageReplyToSender", MailCommands.class, "messageReplyToSender");
        context.registerCommand("messageSaveAttachment", MailCommands.class, "messageSaveAttachment");
        context.registerCommand("messageToggleHeaders", MailCommands.class, "messageToggleHeaders");
        context.registerCommand("messageToggleRaw", MailCommands.class, "messageToggleRaw");
        context.registerCommand("messageToggleWrap", MailCommands.class, "messageToggleWrap");
        context.registerCommand("messageViewAttachment", MailCommands.class, "messageViewAttachment");
        context.registerCommand("openMailbox", MailCommands.class, "openMailbox");
        context.registerCommand("send", MailCommands.class, "send");
        context.registerCommand("sendMailBackTab", MailCommands.class, "sendMailBackTab");
        context.registerCommand("sendMailElectricColon", MailCommands.class, "sendMailElectricColon");
        context.registerCommand("sendMailTab", MailCommands.class, "sendMailTab");
        context.registerCommand("toggleGroupByThread", MailCommands.class, "toggleGroupByThread");
        context.registerCommand("news", NewsCommands.class, "news");
        context.registerCommand("openGroup", NewsCommands.class, "openGroup");
        context.registerCommand("openGroupAtDot", NewsCommands.class, "openGroupAtDot");
        context.registerCommand("readArticle", NewsCommands.class, "readArticle");
        context.registerCommand("readArticleOtherWindow", NewsCommands.class, "readArticleOtherWindow");
        context.registerOpener(new MailboxOpener());
        updateInboxAlias();
        Aliases.setSystemAlias("drafts", "mailbox:" + Directories.getDraftsFolder().netPath());
        context.getPreferences().addPreferencesChangeListener(MailExtension::updateInboxAlias);
        ToolBar.registerButton(
            new ToolBar.Button(
                "Inbox",
                ToolBarIcon.ICON_MAIL_INBOX,
                "inbox",
                () -> Mail.isEnabled() && Editor.preferences().getStringProperty(Property.INBOX) != null
            )
        );
        moveUnsentMessagesToDraftsFolder();
    }

    private static void updateInboxAlias() {
        Aliases.setSystemAlias("inbox", Editor.preferences().getStringProperty(Property.INBOX));
    }

    // Mailbox URLs, and the drafts folder's files as messages to send.
    private static final class MailboxOpener implements Opener {
        @Override
        public boolean handles(String name) {
            return name.startsWith("pop://") || name.startsWith("{") || name.startsWith("mailbox:");
        }

        @Override
        public Buffer getBuffer(Editor editor, String name) {
            MailboxURL url = MailboxURL.parse(name);
            return url != null ? MailCommands.getMailboxBuffer(editor, url) : null;
        }

        @Override
        public void open(Editor editor, String name) {
            MailCommands.openMailbox(editor, name);
        }

        @Override
        public Buffer createBuffer(File file) {
            File dir = file.getParentFile();
            SendMailMode mode = SendMailMode.getMode();
            if (mode != null && dir != null && dir.equals(Directories.getDraftsFolder()))
                return mode.createBuffer(file);
            return null;
        }
    }

    // Older versions kept unsent messages in mail/unsent.
    private static void moveUnsentMessagesToDraftsFolder() {
        File unsentMessagesDirectory =
            File.getInstance(Directories.getMailDirectory(), "unsent");
        if (unsentMessagesDirectory == null) {
            Debug.bug();
            return;
        }
        if (!unsentMessagesDirectory.isDirectory())
            return; // Nothing to do.
        String[] files = unsentMessagesDirectory.list();
        if (files.length == 0) {
            unsentMessagesDirectory.delete();
            return;
        }
        File draftsFolder = Directories.getDraftsFolder();
        if (draftsFolder == null) {
            Debug.bug();
            return;
        }
        Log.info("moving unsent messages to drafts folder...");
        if (!draftsFolder.isDirectory()) {
            draftsFolder.mkdirs();
            if (!draftsFolder.isDirectory()) {
                Log.error("unable to create directory " + draftsFolder);
                return;
            }
        }
        if (!draftsFolder.canWrite()) {
            Log.error(draftsFolder.netPath() + " is not writable");
        }
        for (int i = 0; i < files.length; i++) {
            File source =
                File.getInstance(unsentMessagesDirectory, files[i]);
            File destination =
                File.getInstance(draftsFolder, files[i]);
            Log.debug("moving " + source + " to " + destination);
            if (!source.renameTo(destination))
                Log.error("error moving " + source + " to " + destination);
        }
        files = unsentMessagesDirectory.list();
        if (files.length == 0) {
            Log.debug("removing empty directory " + unsentMessagesDirectory);
            unsentMessagesDirectory.delete();
        } else
            Log.debug(
                "not removing directory " + unsentMessagesDirectory +
                    " (directory is not empty)"
            );
    }
}
