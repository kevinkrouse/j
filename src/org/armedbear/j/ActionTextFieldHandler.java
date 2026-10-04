/*
 * ActionTextFieldHandler.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.Icon;
import org.armedbear.j.util.Icons;

/**
 * Finds a command by its name or summary, shows its key binding, and runs
 * it. One that needs an argument goes to the Command: prompt.
 */
public final class ActionTextFieldHandler extends FinderTextFieldHandler {
    // The command prompt's history, so both list the same recent commands.
    static final String HISTORY = "executeCommand.input";

    private final List<FinderItem> candidates;
    private final List<FinderItem> emptyQueryItems;

    public ActionTextFieldHandler(Editor editor, HistoryTextField textField) {
        super(editor, textField);
        Map<String, FinderItem> byName = new HashMap<>();
        List<Command> commands = CommandTable.getCommands();
        commands.sort(Comparator.comparing(c -> c.getName().toLowerCase(Locale.ROOT)));
        List<FinderItem> all = new ArrayList<>(commands.size());
        for (Command command : commands) {
            FinderItem item = new ActionItem(editor, command);
            all.add(item);
            byName.put(command.getName().toLowerCase(Locale.ROOT), item);
        }
        candidates = List.copyOf(all);
        // Recently run commands first, then the rest.
        Set<FinderItem> empty = new LinkedHashSet<>();
        History history = new History(HISTORY, 30);
        for (int i = history.size(); i-- > 0;) {
            String s = history.get(i).strip();
            int space = s.indexOf(' ');
            FinderItem item = byName.get((space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT));
            if (item != null)
                empty.add(item);
        }
        empty.addAll(all);
        emptyQueryItems = List.copyOf(empty);
    }

    public void start() {
        refilter();
    }

    @Override
    protected List<FinderItem> candidates() {
        return candidates;
    }

    @Override
    protected List<FinderItem> emptyQueryItems() {
        return emptyQueryItems;
    }

    /** Humanizes a command name: "openFileInOtherWindow" is "Open File In Other Window". */
    static String humanize(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i == 0) {
                sb.append(Character.toUpperCase(c));
                continue;
            }
            char prev = name.charAt(i - 1);
            boolean hump = Character.isUpperCase(c) && !Character.isUpperCase(prev);
            boolean digits = Character.isDigit(c) && !Character.isDigit(prev);
            if (hump || digits)
                sb.append(' ');
            sb.append(c);
        }
        return sb.toString();
    }

    // Toolbar icons, and the commands that share them.
    private static final Map<String, String> ICONS = new HashMap<>();

    private static void icon(String icon, String... commands) {
        for (String command : commands)
            ICONS.put(command.toLowerCase(Locale.ROOT), icon);
    }

    static {
        icon("document-new", "newBuffer", "newFrame");
        icon(
            "document-open",
            "openFile",
            "openFileInOtherWindow",
            "openFileInOtherFrame",
            "findFileInProject",
            "recentFiles"
        );
        icon("document-save", "save", "saveAs", "saveCopy", "saveAll");
        icon("close", "killBuffer", "closeAll", "closeOthers", "closeWindow");
        icon("undo", "undo");
        icon("redo", "redo");
        icon("cut", "killRegion", "killLine", "killAppend");
        icon("copy", "copyRegion", "copyAppend", "copyPath");
        icon("paste", "paste", "cyclePaste", "pasteColumn");
        icon(
            "search",
            "find",
            "findNext",
            "findPrev",
            "incrementalFind",
            "findInFiles",
            "findTag",
            "findAction",
            "listOccurrences"
        );
        icon("search-and-replace", "replace", "replaceInFiles");
        icon("directory-list", "dir", "dirProjectDir");
        icon("home", "dirHomeDir");
        icon("up", "dirUpDir");
        icon("left", "webBack");
        icon("right", "webForward");
        icon("refresh", "dirRescan", "rescanProject", "revertBuffer", "reloadKeyMaps", "webReload");
        icon("stop", "cancelBackgroundProcess");
        icon("application-exit", "quit", "saveAllExit");
        icon("mail-message-new", "compose");
        icon("mail", "inbox");
    }

    static final class ActionItem implements FinderItem {
        private final Command command;
        private final String label;
        private final String text;
        private final String note;
        private final String keyText;

        ActionItem(Editor editor, Command command) {
            this.command = command;
            final String name = command.getName();
            label = humanize(name);
            String summary = CommandTable.getSummary(name);
            note = summary == null ? "" : summary;
            // The name, so a query in its exact case matches, and the summary.
            text = note.isEmpty() ? label + " " + name : label + " " + name + " " + note;
            keyText = keyText(editor, name);
        }

        private static String keyText(Editor editor, String name) {
            Object[] values = editor.getKeyMapping(name);
            if (!(values[0] instanceof KeyMapping mapping))
                return "";
            return values[1] instanceof Mode mode ? mapping.getKeyText() + " (" + mode + ")" : mapping.getKeyText();
        }

        @Override
        public String matchText() {
            return text;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public int labelOffset() {
            return 0;
        }

        @Override
        public String detail() {
            return command.getName();
        }

        @Override
        public int detailOffset() {
            return label.length() + 1;
        }

        @Override
        public String note() {
            return note;
        }

        @Override
        public String keyText() {
            return keyText;
        }

        @Override
        public Icon icon() {
            String icon = ICONS.get(command.getName().toLowerCase(Locale.ROOT));
            return Icons.getIconFromFile(icon != null ? icon : "action");
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {
            final String name = command.getName();
            if (!command.takesNoArgument()) {
                // Ask for the argument.
                editor.executeCommand();
                HistoryTextField field = editor.getLocationBarTextField();
                if (field != null) {
                    field.setText(name + " ");
                    field.setCaretPosition(field.getText().length());
                }
                return;
            }
            History history = new History(HISTORY, 30);
            history.append(name);
            history.save();
            editor.executeCommand(name, true);
        }
    }
}
