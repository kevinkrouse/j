/*
 * ChangelistMenu.java
 *
 * Copyright (C) 2026 Kevin Krouse
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

package org.armedbear.j.vcs.signs;

import java.awt.Component;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.InputDialog;
import org.armedbear.j.Line;
import org.armedbear.j.Region;
import org.armedbear.j.vcs.changelists.ChangeTracker;
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.changelists.RepositoryLists;

/**
 * Moving changes of a file's own buffer to a changelist: the change at the
 * caret, or the selection's lines. A small menu of the lists, as the hunk
 * preview's button and the status bar's list name open, those the move
 * wouldn't change dimmed, and a new one asked for; and the move itself,
 * which the command's prompt shares.
 */
public final class ChangelistMenu {
    private ChangelistMenu() {}

    /** Asks for a new list's name; replaced in tests. */
    public interface Ask {
        String ask(Editor editor);
    }

    public static Ask ask = editor -> InputDialog.showInputDialog(editor, "Name:", "New Changelist");

    /** Why a list can't be moved to: what is moved is in it already. */
    public static final String HERE = "already here";

    /** What a move takes in editor's file: the selection's changed lines, else the change at the caret; none for none. */
    public static Set<Line> linesAt(Editor editor, ChangeTracker tracker) {
        final Set<Line> lines = Collections.newSetFromMap(new IdentityHashMap<>());
        if (Region.isSelection(editor))
            lines.addAll(Region.selectedLines(editor));
        else
            lines.addAll(tracker.changeAt(editor.getDotLine()));
        lines.removeIf(l -> tracker.listOf(l) == null);
        return lines;
    }

    /** The lists of u's repository, each with why lines can't move to it, or null if they can. */
    public static Map<String, String> choices(SignsUpdater u, Set<Line> lines) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (Changelists.Changelist l : RepositoryLists.of(u.getRepository()).lists().lists())
            out.put(l.name(), u.tracker().wouldMove(lines, l.name()) ? null : HERE);
        return out;
    }

    /**
     * Moves lines of the file editor shows to the list to, made if new;
     * the statuses and signs show it: what is said.
     */
    public static String move(Editor editor, SignsUpdater u, Set<Line> lines, String to) {
        final RepositoryLists lists = RepositoryLists.of(u.getRepository());
        if (lists.lists().list(to) == null && !lists.update(c -> c.withList(to)))
            return NOT_KEPT;
        final ChangeTracker.Moved moved = u.tracker().move(lines, to);
        editor.setMark(null);
        SignsUpdater.listsChanged(u.getRepository());
        return switch (moved) {
            case NOTHING -> "No change here to move";
            case NOT_KEPT -> NOT_KEPT;
            case MOVED -> "Moved to " + to + (editor.getBuffer().isModified() ? ", kept as the file is saved" : "");
        };
    }

    /** What a change of the lists that couldn't be kept says. */
    public static final String NOT_KEPT = "Couldn't keep the changelists: see the log";

    /**
     * vcsChangelistMenu: the lists the change at the caret, or the
     * selection's lines, can move to, and a new one: under the hunk
     * preview's button if it shows, else under the caret.
     */
    public static void vcsChangelistMenu(Editor editor) {
        if (HunkPreview.showChangelistMenu(editor))
            return;
        final java.awt.Point p = editor.getDisplay().getCaretPoint();
        show(editor, editor.getDisplay(), p.x, p.y + Display.getCharHeight(), null);
    }

    /** As show, the menu's bottom at y, above what opens it, as the status bar. */
    public static void showAbove(Editor editor, Component c, int x, int y) {
        show(editor, c, x, -1 - y, null);
    }

    /**
     * Shows, at x and y in c, the lists the change at editor's caret, or
     * the selection's lines, can move to, and a new one; chosen, moves it.
     * Says why if there is nothing to move or no lists. A y below 0 puts
     * the menu's bottom at -1 - y; closed, chosen from or not, closed run
     * after, if not null.
     */
    public static void show(Editor editor, Component c, int x, int y, Runnable closed) {
        final SignsUpdater u = editor.getBuffer().getSignsUpdater();
        if (u == null || u.tracker() == null || RepositoryLists.shown(u.getRepository()) == null) {
            editor.status("No changelists here");
            return;
        }
        final Set<Line> lines = linesAt(editor, u.tracker());
        if (lines.isEmpty()) {
            editor.status("No change here to move");
            return;
        }
        final JPopupMenu menu = new JPopupMenu();
        for (Map.Entry<String, String> e : choices(u, lines).entrySet()) {
            final String to = e.getKey();
            final JMenuItem item = new JMenuItem(e.getValue() == null ? to : to + "  · " + e.getValue());
            item.setEnabled(e.getValue() == null);
            item.addActionListener(a -> editor.status(move(editor, u, lines, to)));
            menu.add(item);
        }
        menu.addSeparator();
        final JMenuItem made = new JMenuItem("New changelist…");
        made.addActionListener(a -> {
            final String name = ask.ask(editor);
            if (name == null || name.isBlank())
                return;
            final String to = name.strip();
            final String refused = RepositoryLists.of(u.getRepository()).lists().refusedName(to);
            editor.status(refused != null ? refused : move(editor, u, lines, to));
        });
        menu.add(made);
        if (closed != null)
            menu.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}

                // After the choice's action, which runs once the menu hides.
                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    SwingUtilities.invokeLater(closed);
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {}
            });
        menu.show(c, x, y >= 0 ? y : -1 - y - menu.getPreferredSize().height);
        // The first that can be chosen selected, for the keys.
        for (java.awt.Component item : menu.getComponents())
            if (item instanceof JMenuItem m && m.isEnabled()) {
                MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] { menu, m });
                break;
            }
    }
}
