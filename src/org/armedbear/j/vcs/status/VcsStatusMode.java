/*
 * VcsStatusMode.java
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

package org.armedbear.j.vcs.status;

import static org.armedbear.j.Constants.*;
import static org.armedbear.j.ToolBarIcon.*;

import java.awt.AWTEvent;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Formatter;
import org.armedbear.j.Frame;
import org.armedbear.j.FrameView;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.Menu;
import org.armedbear.j.Mode;
import org.armedbear.j.MotionCommands;
import org.armedbear.j.NavigationComponent;
import org.armedbear.j.ProjectRoot;
import org.armedbear.j.Property;
import org.armedbear.j.Region;
import org.armedbear.j.ToolBar;
import org.armedbear.j.View;
import org.armedbear.j.ViewBuffer;
import org.armedbear.j.ViewPane;
import org.armedbear.j.mode.compare.CompareBuffer;
import org.armedbear.j.mode.compare.CompareCommands;
import org.armedbear.j.mode.compare.DiffAlignment;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.Transient.Prefix;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.PickState;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Capability;
import org.armedbear.j.vcs.FileRevision;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.blame.BlameCommands;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.log.LogBuffer;
import org.armedbear.j.vcs.log.LogCommands;
import org.armedbear.j.vcs.selection.FileKey;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.status.StatusActions.Op;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * The status buffer's mode. Nothing in it is edited, so the same keys
 * work in both edit modes, but for the next and previous section: N and P,
 * and ]] and [[ in vim.
 */
public final class VcsStatusMode extends AbstractMode implements Mode {
    private static final VcsStatusMode mode = new VcsStatusMode();

    private VcsStatusMode() {
        super(VCS_REPOSITORY_STATUS_MODE, VCS_REPOSITORY_STATUS_MODE_NAME);
        setProperty(Property.VERTICAL_RULE, 0);
        setProperty(Property.SHOW_LINE_NUMBERS, false);
        setProperty(Property.SHOW_CHANGE_MARKS, false);
        setProperty(Property.HIGHLIGHT_MATCHING_BRACKET, false);
        setProperty(Property.HIGHLIGHT_BRACKETS, false);
        setProperty(Property.RAINBOW_DELIMITERS, false);
    }

    public static VcsStatusMode getMode() {
        return mode;
    }

    @Override
    public Formatter getFormatter(Buffer buffer) {
        return new VcsStatusFormatter(buffer);
    }

    /** The status's sections and files, the same for the status and its diff. */
    @Override
    public NavigationComponent getSidebarComponent(Editor editor) {
        final VcsStatusBuffer b = owner(editor);
        if (b == null || editor.getFrame() == null)
            return null;
        final StatusTree tree = b.tree(editor.getFrame());
        // Kept on the window's view, so the sidebar keeps it as the panes change.
        final View view = editor.getCurrentView();
        if (view != null)
            view.setSidebarComponent(tree);
        return tree;
    }

    @Override
    public String getHelpTopic() {
        return "git.html#status";
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        common(km);
        km.mapKey('z', "vcsStashPopup");
        // The next and previous section, as ]] and [[ are in vim.
        km.mapKey('N', "vcsStatusNextSection");
        km.mapKey('P', "vcsStatusPreviousSection");
        // The context menu, for the keyboard's menu key and Shift F10.
        km.mapKey(KeyEvent.VK_CONTEXT_MENU, 0, "vcsStatusContextMenu");
        km.mapKey(KeyEvent.VK_F10, SHIFT_MASK, "vcsStatusContextMenu");
    }

    private static KeyMap modalKeyMap;

    @Override
    public synchronized KeyMap getModalKeyMap() {
        if (modalKeyMap == null) {
            final KeyMap km = new KeyMap();
            common(km);
            // z begins vim's folds.
            km.mapKey('Z', "vcsStashPopup");
            modalKeyMap = km;
        }
        return modalKeyMap;
    }

    private static KeyMap paneKeyMap;
    private static KeyMap paneModalKeyMap;

    // The diff beside has its own keys: the status's for its hunks and lines, staging too.
    @Override
    public synchronized KeyMap getKeyMap(Buffer buffer) {
        if (!(buffer instanceof StatusDiffBuffer))
            return getKeyMap();
        if (paneKeyMap == null) {
            paneKeyMap = new KeyMap();
            paneKeys(paneKeyMap);
        }
        return paneKeyMap;
    }

    @Override
    public synchronized KeyMap getModalKeyMap(Buffer buffer) {
        if (!(buffer instanceof StatusDiffBuffer))
            return getModalKeyMap();
        if (paneModalKeyMap == null) {
            paneModalKeyMap = new KeyMap();
            paneKeys(paneModalKeyMap);
            // In vim, ; repeats an f or t in the diff, as side by side; on a selection it picks.
            paneModalKeyMap.unmapKey(';');
        }
        return paneModalKeyMap;
    }

    // As the status's where they mean the same, and as the log's diff's.
    private static void paneKeys(KeyMap km) {
        pickKeys(km);
        km.mapKey(KeyEvent.VK_CONTEXT_MENU, 0, "vcsStatusContextMenu");
        km.mapKey(KeyEvent.VK_F10, SHIFT_MASK, "vcsStatusContextMenu");
        km.mapKey(KeyEvent.VK_ENTER, 0, "vcsStatusVisit");
        km.mapKey(VK_DOUBLE_MOUSE_1, 0, "vcsStatusVisitHere");
        km.mapKey(KeyEvent.VK_TAB, 0, "vcsStatusToggle");
        km.mapKey(KeyEvent.VK_TAB, SHIFT_MASK, "vcsStatusCycle");
        km.mapKey('n', "vcsStatusNext");
        km.mapKey('p', "vcsStatusPrevious");
        km.mapKey('s', "vcsStage");
        km.mapKey('u', "vcsUnstage");
        km.mapKey('x', "vcsDiscard");
        km.mapKey('C', "vcsChangelistPopup");
        scrollKeys(km);
        km.mapKey(KeyEvent.VK_R, CTRL_MASK, "vcsStatusRefresh");
        km.mapKey('$', "vcsCommandLog");
        km.mapKey('q', "vcsStatusDiff");
        km.mapKey('?', "modePopup");
    }

    // =, and the diff beside scrolled from either side.
    private static void scrollKeys(KeyMap km) {
        km.mapKey('=', "vcsStatusDiffView");
        km.mapKey(' ', "vcsStatusScrollDiff");
        km.mapKey(KeyEvent.VK_SPACE, SHIFT_MASK, "vcsStatusScrollDiffBack");
        km.mapKey(KeyEvent.VK_BACK_SPACE, 0, "vcsStatusScrollDiffBack");
    }

    private static KeyMap visualKeyMap;

    // Vim's V or v then s stages the lines selected, x discards them.
    @Override
    public synchronized KeyMap getVisualKeyMap() {
        if (visualKeyMap == null) {
            final KeyMap km = new KeyMap();
            km.mapKey('s', "vcsStage");
            km.mapKey('u', "vcsUnstage");
            km.mapKey('x', "vcsDiscard");
            // The changelists' popup, its move taking the lines selected.
            km.mapKey('C', "vcsChangelistPopup");
            pickKeys(km);
            visualKeyMap = km;
        }
        return visualKeyMap;
    }

    // ; with no Shift, and the file managers' Insert; Ctrl ; unpicks all,
    // as in a directory.
    static void pickKeys(KeyMap km) {
        km.mapKey(KeyEvent.VK_INSERT, 0, "vcsStatusPick");
        km.mapKey(';', "vcsStatusPick");
        km.mapKey(KeyEvent.VK_SEMICOLON, CTRL_MASK, "vcsStatusUnpickAll");
    }

    private static void common(KeyMap km) {
        km.mapKey('C', "vcsChangelistPopup");
        pickKeys(km);
        km.mapKey(KeyEvent.VK_ENTER, 0, "vcsStatusVisit");
        km.mapKey(VK_DOUBLE_MOUSE_1, 0, "vcsStatusVisitHere");
        km.mapKey(KeyEvent.VK_TAB, 0, "vcsStatusToggle");
        km.mapKey(KeyEvent.VK_TAB, SHIFT_MASK, "vcsStatusCycle");
        for (char c = '1'; c <= '4'; c++)
            km.mapKey(c, "vcsStatusDepth " + c);
        km.mapKey('n', "vcsStatusNext");
        km.mapKey('p', "vcsStatusPrevious");
        km.mapKey(KeyEvent.VK_N, ALT_MASK, "vcsStatusNextSection");
        km.mapKey(KeyEvent.VK_P, ALT_MASK, "vcsStatusPreviousSection");
        km.mapKey('$', "vcsCommandLog");
        km.mapKey('s', "vcsStage");
        km.mapKey('u', "vcsUnstage");
        km.mapKey('x', "vcsDiscard");
        km.mapKey('S', "vcsStageAll");
        km.mapKey('U', "vcsUnstageAll");
        km.mapKey(KeyEvent.VK_R, CTRL_MASK, "vcsStatusRefresh");
        km.mapKey('c', "vcsCommitPopup");
        km.mapKey('f', "vcsFetchPopup");
        km.mapKey('b', "vcsBranchPopup");
        km.mapKey('m', "vcsMergePopup");
        km.mapKey('l', "vcsLogPopup");
        km.mapKey('D', "vcsStatusDiff");
        km.mapKey('q', "killBuffer");
        km.mapKey('?', "modePopup");
        scrollKeys(km);
    }

    private static final String[][] HINTS = {
        { "vcsStage", "stage" },
        { "vcsUnstage", "unstage" },
        { "vcsDiscard", "discard" },
        { "vcsCommitPopup", "commit" },
        { "modePopup", "more" }, };

    private static final String[][] DIFF_HINTS = {
        { "vcsStatusVisit", "open at this line" },
        { "vcsStatusToggle", "fold" },
        { "vcsStatusDiffView", "side by side" },
        { "vcsStatusDiff", "close" },
        { "modePopup", "more" }, };

    // As the popup has them: staging only for a backend with an index.
    private static final String[][] DIFF_STAGING_HINTS = {
        { "vcsStage", "stage" },
        { "vcsUnstage", "unstage" },
        { "vcsDiscard", "discard" },
        { "vcsStatusVisit", "open at this line" },
        { "vcsStatusToggle", "fold" },
        { "vcsStatusDiffView", "side by side" },
        { "vcsStatusDiff", "close" },
        { "modePopup", "more" }, };

    @Override
    public String[][] getHints(Buffer buffer) {
        if (!buffer.getBooleanProperty(Property.VCS_HINTS))
            return null;
        if (buffer instanceof StatusDiffBuffer d)
            return stages(d.getOwner()) ? DIFF_STAGING_HINTS : DIFF_HINTS;
        return HINTS;
    }

    // The toolbar and the Mode menu, of the status and its diff; side by
    // side, the comparison's too.

    @Override
    protected ToolBar getDefaultToolBar(Frame frame) {
        final ToolBar tb = new ToolBar(frame);
        // Where staging runs: in the status or its diff, not a side of the comparison.
        final Supplier<Editor> at = () -> FrameView.target(frame.getCurrentEditor(), "vcsStage");
        final Supplier<VcsStatusBuffer> status = () -> at.get() == null ? null : owner(at.get());
        final BooleanSupplier diffShown = () -> status.get() != null && status.get().isDiffShown(frame);
        final BooleanSupplier sides = () -> status.get() != null && status.get().getCompare() != null;
        final Supplier<Set<Op>> able = tb.perUpdate(() -> StatusActions.able(at.get()));
        tb.addButton("New", ICON_NEW, "newBuffer");
        tb.addButton("Open", ICON_OPEN, "openFile");
        tb.addButton("Close", ICON_CLOSE, "killBuffer");
        tb.addSeparator();
        tb.addButton("Refresh", ICON_REFRESH, "vcsStatusRefresh");
        tb.addSeparator();
        tb.addButton("Stage", ICON_PLUS, "vcsStage").enabledWhen(() -> able.get().contains(Op.STAGE));
        tb.addButton("Unstage", ICON_MINUS, "vcsUnstage").enabledWhen(() -> able.get().contains(Op.UNSTAGE));
        tb.addButton("Discard", ICON_DELETE, "vcsDiscard").enabledWhen(() -> able.get().contains(Op.DISCARD));
        tb.addSeparator();
        tb.addButton("Commit", ICON_COMMIT, "vcsCommitPopup").enabledWhen(() -> stages(status.get()));
        tb.addButton("Log", ICON_HISTORY, "vcsLogPopup");
        tb.addSeparator();
        tb.addButton("Show Diff", ICON_DIFF, "vcsStatusDiff").selectedWhen(diffShown);
        tb.addButton("Side by Side", ICON_SIDE_BY_SIDE, "vcsStatusDiffView").selectedWhen(sides);
        tb.addSeparator();
        CompareCommands.addChangeButtons(tb, diffShown);
        tb.addSeparator();
        CompareCommands.addButtons(tb, sides);
        tb.addExtensionButtons();
        tb.addSeparator();
        tb.addButton("Exit", ICON_EXIT, "quit");
        return tb;
    }

    // The items run where the status says (VcsStatusBuffer.windowFor), with
    // the keys of the window the menu is opened in.
    @Override
    public void populateModeMenu(Editor editor, Menu menu) {
        final Editor at = FrameView.target(editor, "vcsStage");
        final VcsStatusBuffer b = owner(at);
        final boolean stages = stages(b);
        final boolean diffShown = b != null && b.isDiffShown(at.getFrame());
        final boolean sides = b != null && b.getCompare() != null;
        final Set<Op> able = StatusActions.able(at);
        menu.add(editor, "Stage", 'S', "vcsStage", able.contains(Op.STAGE));
        menu.add(editor, "Unstage", 'U', "vcsUnstage", able.contains(Op.UNSTAGE));
        menu.add(editor, "Discard", 'D', "vcsDiscard", able.contains(Op.DISCARD));
        menu.add(editor, "Stage All", 'A', "vcsStageAll", stages);
        menu.add(editor, "Unstage All", 'L', "vcsUnstageAll", stages);
        menu.addSeparator();
        menu.add(editor, "Commit", 'C', "vcsCommit", stages);
        menu.add(editor, "Amend", 'M', "vcsCommitAmend", stages);
        menu.add(editor, "Extend", 'E', "vcsCommitExtend", stages);
        menu.add(editor, "Reword", 'W', "vcsCommitReword", stages);
        menu.addSeparator();
        menu.addCheckBox(editor, "Show Diff", 'F', "vcsStatusDiff", diffShown);
        menu.addCheckBox(editor, "Side by Side", 'B', "vcsStatusDiffView", sides);
        if (diffShown) {
            menu.addSeparator();
            CompareCommands.addChangeItems(editor, menu);
        }
        if (sides) {
            menu.addSeparator();
            CompareCommands.addItems(editor, menu);
        } else
            CompareCommands.addWhitespaceItems(editor, menu);
        menu.addSeparator();
        menu.add(editor, "Next", 'N', "vcsStatusNext");
        menu.add(editor, "Previous", 'P', "vcsStatusPrevious");
        menu.add(editor, "Next Section", 'X', "vcsStatusNextSection");
        menu.add(editor, "Previous Section", 'R', "vcsStatusPreviousSection");
        menu.add(editor, "Show Sections", '1', "vcsStatusDepth 1");
        menu.add(editor, "Show Files", '2', "vcsStatusDepth 2");
        menu.add(editor, "Show Hunks", '3', "vcsStatusDepth 3");
        menu.add(editor, "Show Diffs", '4', "vcsStatusDepth 4");
        menu.addSeparator();
        menu.add(editor, "Log...", 'G', "vcsLogPopup");
        menu.add(editor, "Command Log", 'O', "vcsCommandLog");
        menu.add(editor, "Refresh", 'H', "vcsStatusRefresh");
    }

    @Override
    public Transient getTransient(Editor editor) {
        final boolean vim = editor != null && editor.getInputHandler() != null;
        if (editor != null && editor.getBuffer() instanceof StatusDiffBuffer d)
            return diffTransient(stages(d.getOwner()));
        final boolean stages = editor == null || stages(editor.getBuffer());
        final List<Group> groups = new ArrayList<>();
        groups.add(
            Group.of(
                "Show",
                new Command("1", "vcsStatusDepth 1", "sections", true),
                new Command("2", "vcsStatusDepth 2", "files", true),
                new Command("3", "vcsStatusDepth 3", "hunks", true),
                new Command("4", "vcsStatusDepth 4", "diffs", true),
                new Command(Transient.TAB, "vcsStatusToggle", "toggle", true)));
        groups.add(
            Group.of(
                "Go",
                Command.of(Transient.ENTER, "vcsStatusVisit", "go to file"),
                new Command("n", "vcsStatusNext", "next", true),
                new Command("p", "vcsStatusPrevious", "previous", true),
                new Command(vim ? "]]" : "N", "vcsStatusNextSection", "next section", true),
                new Command(vim ? "[[" : "P", "vcsStatusPreviousSection", "previous section", true)));
        // Staging, and so committing what is staged, is for a backend that has an index.
        final List<Transient.Item> changes = new ArrayList<>();
        if (stages)
            changes.addAll(
                List.of(
                    new Command("s", "vcsStage", "stage", true),
                    new Command("u", "vcsUnstage", "unstage", true),
                    new Command("S", "vcsStageAll", "stage all", true),
                    new Command("U", "vcsUnstageAll", "unstage all", true),
                    new Command("x", "vcsDiscard", "discard", true)));
        changes.add(new Command(Transient.ctrl('r'), "vcsStatusRefresh", "refresh", true));
        groups.add(new Group("Changes", changes));
        groups.add(PICK);
        final List<Transient.Item> commands = new ArrayList<>();
        commands.add(new Prefix("l", "log…", () -> LogCommands.openingPopup(editor)));
        commands.add(Command.of("D", "vcsStatusDiff", "diff"));
        if (stages)
            commands.add(new Prefix("c", "commit…", () -> CommitCommands.popup(editor)));
        commands.add(Command.of("b", "vcsBranchPopup", "branch…"));
        commands.add(Command.of("m", "vcsMergePopup", "merge…"));
        commands.add(Command.of("f", "vcsFetchPopup", "fetch…"));
        commands.add(Command.of(vim ? "Z" : "z", "vcsStashPopup", "stash…"));
        commands.add(Command.of("C", "vcsChangelistPopup", "changelists…"));
        commands.add(Command.of("$", "vcsCommandLog", "command log"));
        commands.add(Command.of("=", "vcsStatusDiffView", "diff layout"));
        commands.add(Command.of("q", "killBuffer", "close"));
        groups.add(new Group("Commands", commands));
        return new Transient("vcs.status", "Status", "git.html#status", groups);
    }

    // The status's and the diff beside's picking.
    private static final Group PICK = Group.of(
        "Pick",
        new Command(";", "vcsStatusPick", "pick", true),
        Command.of(Transient.ctrl(';'), "vcsStatusUnpickAll", "unpick all"));

    // The diff beside's: what its keys do there.
    private static Transient diffTransient(boolean stages) {
        final List<Group> groups = new ArrayList<>();
        groups.add(
            Group.of(
                "Go",
                new Command("n", "vcsStatusNext", "next hunk", true),
                new Command("p", "vcsStatusPrevious", "previous hunk", true)));
        // The hunk at the caret, or the lines selected.
        final List<Transient.Item> changes = new ArrayList<>();
        if (stages)
            changes.addAll(
                List.of(
                    new Command("s", "vcsStage", "stage", true),
                    new Command("u", "vcsUnstage", "unstage", true),
                    new Command("x", "vcsDiscard", "discard", true)));
        changes.add(new Command(Transient.ctrl('r'), "vcsStatusRefresh", "refresh", true));
        groups.add(new Group("Changes", changes));
        groups.add(PICK);
        groups.add(
            Group.of(
                "Diff",
                Command.of("$", "vcsCommandLog", "command log"),
                Command.of("=", "vcsStatusDiffView", "side by side"),
                Command.of("q", "vcsStatusDiff", "close")));
        return new Transient("vcs.status.diff", "Diff", "git.html#status-diff", groups);
    }

    static boolean stages(Buffer buffer) {
        return buffer instanceof VcsStatusBuffer b
                && b.getRepositories().stream().allMatch(r -> r.backend().capabilities().contains(Capability.STAGING));
    }

    @Override
    public List<Line> getChangeStarts(Buffer buffer) {
        return StatusLine.hunks(buffer);
    }

    // Folding: a node's lines are left out of the buffer, not hidden.

    @Override
    public boolean isFoldStart(Buffer buffer, Line line) {
        if (!(line instanceof StatusLine s))
            return false;
        return buffer instanceof DiffPane ? s.kind() == Kind.HUNK : s.isExpandable();
    }

    // Not a range of lines: the gutter draws no extent.
    @Override
    public Line[] getFoldBelow(Buffer buffer, Line line) {
        return null;
    }

    @Override
    public boolean isFolded(Line line) {
        return line instanceof StatusLine s && s.isExpandable() && !s.isOpen();
    }

    @Override
    public void toggleFoldAt(Editor editor, Line line) {
        if (editor.getBuffer() instanceof VcsStatusBuffer b && line instanceof StatusLine s)
            b.toggle(s);
        else if (editor.getBuffer() instanceof DiffPane<?> d && line instanceof StatusLine s)
            d.toggle(s);
    }

    @Override
    public boolean setFoldAt(Editor editor, Line line, Boolean open) {
        if (editor.getBuffer() instanceof DiffPane<?> d && line instanceof StatusLine s) {
            d.setFold(s, open);
            return true;
        }
        if (!(editor.getBuffer() instanceof VcsStatusBuffer b) || !(line instanceof StatusLine s))
            return false;
        StatusLine head = StatusLine.head(s);
        // As vim's zc does, closing what is closed already closes the fold around it.
        if (Boolean.FALSE.equals(open) && (head == null || !head.isOpen()))
            head = b.parent(s);
        if (head != null && head.isExpandable())
            b.setExpanded(head, open != null ? open : !head.isOpen());
        return true;
    }

    @Override
    public boolean setAllFolds(Editor editor, boolean open) {
        if (editor.getBuffer() instanceof DiffPane<?> d) {
            d.setAll(open);
            return true;
        }
        if (!(editor.getBuffer() instanceof VcsStatusBuffer b))
            return false;
        b.setDepth(open ? 4 : 1);
        return true;
    }

    @Override
    public void foldAll(Editor editor) {
        setAllFolds(editor, false);
    }

    @Override
    public Line sectionFrom(Line from, boolean forward) {
        for (Line l = forward ? from.next() : from.previous(); l != null; l = forward ? l.next() : l.previous())
            if (l instanceof StatusLine s && (s.kind() == Kind.SECTION || s.kind() == Kind.REPOSITORY))
                return l;
        return from;
    }

    // Commands.

    /** Opens the status of the current buffer's repository, or of the project's repositories. */
    public static void vcsStatus(Editor editor) {
        openStatus(editor);
    }

    /**
     * Picks the unstaged changes of the file editor shows, r's, as it is
     * saved, in the status of the repositories vcsStatus opens from
     * editor's buffer, read if it isn't, no status buffer opened: the
     * change at row from, from 0, or the nearest in its hunk, or with
     * lines, the changed lines of rows from to to; or, each picked,
     * unpicks them; then runs then.
     */
    public static void pickUnstagedAt(Editor editor, VcsRepository r, int from, int to, boolean lines, Runnable then) {
        final List<VcsRepository> repositories = repositoriesFor(editor.getBuffer());
        final List<VcsRepository> rs = repositories.contains(r) ? repositories : List.of(r);
        RepositoryStatus.of(rs).pickUnstagedAt(editor, from, to, lines, then);
    }

    /** vcsStatus: the window it shows the status in, or null if there is none. */
    static Editor openStatus(Editor editor) {
        final List<VcsRepository> repositories = repositoriesFor(editor.getBuffer());
        if (repositories.isEmpty()) {
            editor.status("Not in a repository");
            return null;
        }
        final boolean opening = VcsStatusBuffer.find(repositories) == null;
        final VcsStatusBuffer buffer = VcsStatusBuffer.of(repositories);
        final Editor shown = editor.show(buffer);
        if (opening && "right".equals(Editor.preferences().getStringProperty(Property.VCS_STATUS_DIFF)))
            buffer.openDiff(editor.getFrame());
        return shown;
    }

    /**
     * The VCS menu: the status, the log, the file's diff and blame and committing, of
     * editor's buffer's repository, as each applies to it.
     */
    public static void populateVcsMenu(Editor editor, Menu menu) {
        final Buffer buffer = editor.getBuffer();
        final List<VcsRepository> repositories = repositoriesFor(buffer);
        final boolean inRepository = !repositories.isEmpty();
        final File file = buffer.getFile();
        final boolean tracked = buffer.getType() == Buffer.TYPE_NORMAL
                && file != null
                && !file.isRemote()
                && Repositories.forFile(file) != null;
        menu.add(editor, "Status", 'S', "vcsStatus", inRepository);
        menu.add(editor, "Log", 'L', "vcsLog", inRepository);
        menu.add(editor, "Diff with What Is Staged", 'D', "vcsDiff", tracked);
        BlameCommands.addMenuItem(editor, menu);
        menu.add(
            editor,
            "Commit...",
            'C',
            "vcsCommitPopup",
            inRepository
                    && repositories.stream().allMatch(r -> r.backend().capabilities().contains(Capability.STAGING)));
        menu.addSeparator();
        menu.add(editor, "Command Log", 'O', "vcsCommandLog");
    }

    // The project's, if it has more than one and the buffer is in one of
    // them; else the buffer's own.
    public static List<VcsRepository> repositoriesFor(Buffer buffer) {
        // A part of a view, as a side of the status's comparison: the view's.
        if (buffer.getFrameView() instanceof Buffer view && view != buffer)
            return repositoriesFor(view);
        if (buffer instanceof VcsStatusBuffer s)
            return s.getRepositories();
        if (buffer instanceof StatusDiffBuffer d)
            return d.getOwner().getRepositories();
        // A comparison following a buffer, as vcsDiff's of a file: the file's.
        final CompareBuffer compare = CompareBuffer.of(buffer);
        if (compare != null && compare.followed() != null && compare.followed() != buffer)
            return repositoriesFor(compare.followed());
        // A log's, from its list or its files or diff.
        final Buffer view = buffer instanceof ViewPane<?> pane ? pane.getOwner() : buffer;
        if (view instanceof LogBuffer log)
            return log.getScope().repositories();
        // A file at a commit, as blaming a parent opens.
        if (buffer instanceof FileRevision r)
            return List.of(r.repository());
        final File file = buffer.getFile();
        final VcsRepository own = file != null ? Repositories.forFile(file) : null;
        final File root = ProjectRoot.find(buffer);
        if (root != null) {
            final List<VcsRepository> project = Repositories.forProject(root);
            if (project.size() > 1 && (own == null || project.contains(own)))
                return project;
        }
        return own != null ? List.of(own) : List.of();
    }

    private static VcsStatusBuffer statusBuffer(Editor editor) {
        return editor.getBuffer() instanceof VcsStatusBuffer b ? b : null;
    }

    // The status of editor's buffer: the buffer itself, or the status whose
    // diff it is, unified or side by side.
    static VcsStatusBuffer owner(Editor editor) {
        final Buffer b = editor.getBuffer();
        if (b instanceof StatusDiffBuffer d)
            return d.getOwner();
        if (CompareBuffer.of(b) != null && b.getFrameView() instanceof VcsStatusBuffer s)
            return s;
        return statusBuffer(editor);
    }

    /**
     * q in the diff: the diff of the file at the caret beside the status, or
     * not, back to the status; open but not laid out, as with its window
     * closed by hand, laid out again.
     */
    public static void vcsStatusDiff(Editor editor) {
        final VcsStatusBuffer b = owner(editor);
        if (b != null && !showBeside(editor, b)) {
            b.closeDiffToStatus(editor.getFrame());
            toStatus(editor.getFrame(), b);
        }
    }

    // b's diff beside opened, or laid out again; false, left as it is, if it is shown.
    private static boolean showBeside(Editor editor, VcsStatusBuffer b) {
        final Frame frame = editor.getFrame();
        if (b.getDiff() == null)
            b.openDiff(frame);
        else if (frame != null && !b.isDiffShown(frame))
            frame.showView(b, editor.getBuffer());
        else
            return false;
        return true;
    }

    // The status focused in frame, in the current window if none shows it.
    private static void toStatus(Frame frame, VcsStatusBuffer b) {
        if (frame == null || !Editor.getBufferList().contains(b))
            return;
        final Editor ed = frame.findEditor(b);
        if (ed == null) {
            Editor.currentEditor().switchToBuffer(b);
            return;
        }
        Editor.setCurrentEditor(ed);
        ed.setFocusToDisplay();
    }

    /**
     * ; or Insert: picks the thing at the caret, a file, or a hunk, the
     * caret on one of its lines too, or each file and hunk or the lines a
     * selection covers; if each is picked, unpicks them. In the diff
     * beside, the same; side by side, the hunk at the caret's row. The commands that
     * take targets take a selection, else what is picked, else the caret's,
     * and those that act on picks unpick them. The caret goes on to the next
     * thing of its kind.
     */
    public static void vcsStatusPick(Editor editor) {
        final VcsStatusBuffer b = owner(editor);
        if (b == null || editor.getDot() == null)
            return;
        // A side of the comparison: the hunk at the caret's row.
        final CompareBuffer c = CompareBuffer.of(editor.getBuffer());
        if (c != null) {
            b.comparedPicks().pick(editor, editor.getBuffer() == c.getOld());
            return;
        }
        if (editor.getBuffer() != b && editor.getBuffer() != b.getDiff())
            return;
        final boolean selection = Region.isSelection(editor);
        final List<StatusLine> ls = StatusActions.selected(editor);
        if (ls.stream().noneMatch(l -> l != null && StatusPicks.pickable(l))) {
            editor.status("Nothing to pick here");
            return;
        }
        b.pick(ls.stream().filter(l -> l != null).toList());
        if (selection)
            editor.setMark(null);
        else if (!ls.isEmpty() && ls.get(0) != null)
            nextLike(editor, ls.get(0));
    }

    // The caret on to the next file, or hunk, after line's.
    private static void nextLike(Editor editor, StatusLine line) {
        final Kind kind = line.kind() == Kind.FILE ? Kind.FILE : Kind.HUNK;
        for (Line l = line.next(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == kind) {
                editor.moveDotTo(s, 0);
                return;
            }
    }

    /** Unpicks everything picked in the status. */
    public static void vcsStatusUnpickAll(Editor editor) {
        final VcsStatusBuffer b = owner(editor);
        if (b == null)
            return;
        editor.status(b.unpickAll() ? "Nothing is picked" : "Nothing was picked");
    }

    /** =: the diff beside opened as last shown, or switched between unified and side by side. */
    public static void vcsStatusDiffView(Editor editor) {
        final VcsStatusBuffer b = owner(editor);
        if (b != null)
            b.diffView(editor.getFrame(), editor.getBuffer());
    }

    /** Space: the diff beside a page on, from the status or the diff. */
    public static void vcsStatusScrollDiff(Editor editor) {
        scrollDiff(editor, true);
    }

    public static void vcsStatusScrollDiffBack(Editor editor) {
        scrollDiff(editor, false);
    }

    private static void scrollDiff(Editor editor, boolean forward) {
        final VcsStatusBuffer b = owner(editor);
        final StatusDiffBuffer d = b == null ? null : b.getDiff();
        final CompareBuffer c = b == null ? null : b.getCompare();
        final Buffer commit = b == null || b.details() == null ? null : b.details().diffShown();
        final List<Editor> windows = commit instanceof ViewBuffer v ? v.windows()
                : c != null ? c.windows()
                : d == null ? List.of()
                : d.windows();
        // None: Space and Backspace move as they would.
        if (windows.isEmpty()) {
            if (forward)
                MotionCommands.right(editor);
            else
                MotionCommands.left(editor);
            return;
        }
        final Editor ed = windows.contains(editor) ? editor : windows.get(0);
        if (forward)
            MotionCommands.pageDown(ed);
        else
            MotionCommands.pageUp(ed);
        ed.updateDisplay();
    }

    /** The repository of the thing at the caret in the status or its diff, or the status's first; null for none. */
    static VcsRepository repositoryAt(Editor editor) {
        final VcsStatusBuffer status = owner(editor);
        if (status == null)
            return null;
        if (editor.getBuffer() == status && editor.getDotLine() instanceof StatusLine s && s.repository() != null)
            return s.repository();
        return status.getRepositories().get(0);
    }

    private static StatusLine dotLine(Editor editor) {
        return editor.getDot() != null && editor.getDotLine() instanceof StatusLine s ? s : null;
    }

    public static void vcsStatusToggle(Editor editor) {
        if (editor.getBuffer() instanceof StatusDiffBuffer d) {
            final StatusLine line = dotLine(editor);
            if (line == null || !d.toggle(line))
                editor.status("No hunk here");
            return;
        }
        final VcsStatusBuffer b = statusBuffer(editor);
        final StatusLine line = dotLine(editor);
        if (b != null && line != null && !b.toggle(line))
            editor.status("Nothing to expand here");
    }

    /** Steps every node through the depths: sections, files, hunks, diffs. */
    public static void vcsStatusCycle(Editor editor) {
        if (editor.getBuffer() instanceof StatusDiffBuffer d) {
            d.toggleAll();
            return;
        }
        final VcsStatusBuffer b = statusBuffer(editor);
        if (b != null)
            b.setDepth(b.getDepth() % 4 + 1);
    }

    public static void vcsStatusDepth(Editor editor, String arg) {
        final VcsStatusBuffer b = statusBuffer(editor);
        if (b == null)
            return;
        try {
            b.setDepth(Integer.parseInt(arg.strip()));
        }
        catch (NumberFormatException e) {
            editor.status("vcsStatusDepth: expected 1 to 4");
        }
    }

    public static void vcsStatusRefresh(Editor editor) {
        final VcsStatusBuffer b = owner(editor);
        if (b == null)
            return;
        for (VcsRepository r : b.getRepositories()) {
            r.invalidate();
            r.refresh();
        }
        b.refresh();
    }

    public static void vcsStatusNext(Editor editor) {
        move(editor, true, false);
    }

    public static void vcsStatusPrevious(Editor editor) {
        move(editor, false, false);
    }

    public static void vcsStatusNextSection(Editor editor) {
        move(editor, true, true);
    }

    public static void vcsStatusPreviousSection(Editor editor) {
        move(editor, false, true);
    }

    // To the next or previous heading: of a section, or of anything that opens.
    private static void move(Editor editor, boolean forward, boolean sections) {
        if (editor.getDot() == null)
            return;
        for (Line l = forward ? editor.getDotLine().next() : editor.getDotLine().previous();
                l != null;
                l = forward ? l.next() : l.previous()) {
            if (l instanceof StatusLine s
                    && (sections
                            ? s.kind() == Kind.SECTION || s.kind() == Kind.REPOSITORY
                            : s.isExpandable()
                                    || s.kind() == Kind.COMMIT
                                    || s.kind() == Kind.MESSAGE && s.key().endsWith(StatusRenderer.MORE))) {
                editor.moveDotTo(l, 0);
                return;
            }
        }
    }

    /**
     * Enter: on a picked file or hunk, opens each file picked; else visits
     * the thing at the caret, as vcsStatusVisitHere does.
     */
    public static void vcsStatusVisit(Editor editor) {
        // On a file or hunk picked, whole or in part, each file picked; on
        // anything else, as ever.
        final VcsStatusBuffer status = owner(editor);
        if (status != null
                && editor.getBuffer() == status
                && !status.picks().isEmpty()
                && dotLine(editor) instanceof StatusLine at
                && at.kind() != Kind.DIFF
                && status.picks().state(at) != PickState.NONE) {
            final List<String> gone = new ArrayList<>();
            for (FileKey p : status.picks().paths()) {
                final File f = File.getInstance(p.repository().root().resolve(p.path()).toString());
                if (f == null || !f.isFile() && !f.isDirectory())
                    gone.add(p.path());
                else
                    visit(editor, f, 0);
            }
            if (!gone.isEmpty())
                editor.status(
                    String.join(", ", gone) + (gone.size() == 1 ? " is" : " are") + " not in the working tree");
            return;
        }
        vcsStatusVisitHere(editor);
    }

    /**
     * A double click: visits the thing at the caret, picks or none: a file,
     * at the line of the hunk or diff line, a staged one's through the
     * working tree's changes since; or a commit, shown as a diff.
     */
    public static void vcsStatusVisitHere(Editor editor) {
        if (CompareBuffer.of(editor.getBuffer()) != null) {
            visitCompared(editor);
            return;
        }
        final StatusLine line = dotLine(editor);
        if (line == null)
            return;
        // A stash, or a line in it: its details beside.
        final StatusLine commit = VcsStatusBuffer.commitOf(line);
        if (commit != null && commit.stash() != null) {
            final VcsStatusBuffer b = owner(editor);
            if (b != null)
                showBeside(editor, b);
            return;
        }
        if (line.kind() == Kind.COMMIT) {
            showCommit(editor, line);
            return;
        }
        // The parent's line: another asked for.
        if (line.kind() == Kind.FIELD && line.key().endsWith("|field|Parent")) {
            ParentCommands.gitSetParent(editor);
            return;
        }
        if (line.kind() == Kind.MESSAGE && line.key().endsWith(StatusRenderer.MORE)) {
            showMore(editor, line);
            return;
        }
        final FileItem file = line.file();
        if (file == null) {
            editor.status("Nothing to visit here");
            return;
        }
        visit(editor, line.repository(), file, lineNumber(line));
    }

    // Side by side: the file at the new side's line at the caret's row.
    private static void visitCompared(Editor editor) {
        final VcsStatusBuffer b = owner(editor);
        if (b != null)
            b.comparedLineAt(editor, n -> {
                if (b.getDiff() != null)
                    visit(editor, b.getDiff().repository(), b.getDiff().item(), n);
            });
    }

    // The working tree's file at the line, from 1, of its diff: a staged
    // diff's line through what is not staged since.
    static void visit(Editor editor, VcsRepository repository, FileItem file, int lineNumber) {
        final File f = File.getInstance(repository.root().resolve(file.path()).toString());
        if (f == null || !f.isFile()) {
            editor.status(file.path() + " is not in the working tree");
            return;
        }
        if (!file.staged() || lineNumber <= 0) {
            visit(editor, f, lineNumber);
            return;
        }
        Background.start("vcs visit", () -> {
            final List<FileDiff> unstaged =
                    repository.backend().readDiff(repository, false, List.of(file.path()), Whitespace.NONE);
            final int mapped = unstaged == null || unstaged.isEmpty()
                    ? lineNumber
                    : throughHunks(unstaged.get(0).hunks(), lineNumber);
            SwingUtilities.invokeLater(() -> visit(editor, f, mapped));
        });
    }

    /**
     * The file's line a diff's line shows, from 1: an added or context
     * line's own, a removed line's place on the new side; a hunk's or a
     * file's first change. 0 for none.
     */
    public static int lineNumber(StatusLine line) {
        if (line.kind() == Kind.DIFF)
            return Math.max(1, line.newLine());
        if (line.hunk() != null)
            return firstChange(line.hunk());
        if (line.file() == null)
            return 0;
        final FileDiff diff = line.file().diff();
        return diff != null && !diff.hunks().isEmpty() ? firstChange(diff.hunks().get(0)) : 0;
    }

    // The new side's line of a hunk's first change.
    static int firstChange(Hunk hunk) {
        int n = hunk.newStart();
        for (DiffLine d : hunk.lines()) {
            if (!d.isContext())
                return Math.max(1, n);
            n++;
        }
        return Math.max(1, hunk.newStart());
    }

    /**
     * Where line n of the old side of a diff is on its new side: moved by
     * the hunks before it, at the start of the hunk it is in.
     */
    public static int throughHunks(List<Hunk> hunks, int n) {
        int shift = 0;
        for (Hunk h : hunks) {
            // A side with no lines starts after the line its start names.
            final int oldStart = h.oldCount() == 0 ? h.oldStart() + 1 : h.oldStart();
            final int newStart = h.newCount() == 0 ? h.newStart() + 1 : h.newStart();
            if (n < oldStart)
                break;
            if (n < oldStart + h.oldCount())
                return Math.max(1, newStart);
            shift = newStart + h.newCount() - (oldStart + h.oldCount());
        }
        return Math.max(1, n + shift);
    }

    /**
     * Line n, from 0, of side, ours or theirs, as the working tree's line,
     * from 1, by a diff of the two: a line the working tree has not, the
     * nearest before it there.
     */
    public static int sideToWorking(List<String> side, List<String> working, int n) {
        if (side.isEmpty() || n >= side.size())
            return 1;
        return Math.max(0, DiffAlignment.of(side, working).otherLine(n, true)) + 1;
    }

    private static void visit(Editor editor, File file, int lineNumber) {
        final Buffer buffer = Editor.getBuffer(file);
        if (buffer != null)
            showAt(editor, buffer, lineNumber);
    }

    /** Shows buffer in editor's window at its line lineNumber, from 1; 0 for where it was. Returns the editor showing it. */
    public static Editor showAt(Editor editor, Buffer buffer, int lineNumber) {
        final Editor ed = editor.show(buffer);
        if (lineNumber > 0) {
            ed.jumpToLine(lineNumber - 1);
            ed.getDisplay().setUpdateFlag(REFRAME);
        }
        ed.updateDisplay();
        return ed;
    }

    // The log of the commits of line's section.
    private static void showMore(Editor editor, StatusLine line) {
        final VcsStatusBuffer b = owner(editor);
        final StatusData data = b == null ? null : b.getData(line.repository());
        if (data != null)
            for (StatusData.Section s : data.sections())
                if (s.id().equals(line.section()) && s.range() != null) {
                    LogCommands.showRange(editor, line.repository(), s.range());
                    return;
                }
        editor.status("Nothing to visit here");
    }

    private static void showCommit(Editor editor, StatusLine line) {
        final VcsRepository repository = line.repository();
        final String hash = line.commit().hash();
        final Whitespace ignored = DiffWhitespace.ignored();
        Background.then("vcs show", () -> repository.backend().readCommit(repository, hash, ignored), text -> {
            if (text == null) {
                editor.status("Can't show " + hash);
                return;
            }
            final DiffOutputBuffer buffer = new DiffOutputBuffer(File.getInstance(repository.root().toString()),
                    new String(text, StandardCharsets.UTF_8),
                    repository.backend().id());
            buffer.setTitle(repository.backend().name() + " show " + hash);
            editor.show(buffer);
        });
    }

    // Right-click menus.

    /**
     * The status buffer's context menu: the git things to do with the
     * line under the mouse, the caret moved there first unless the click
     * is inside the selection, which the menu then takes.
     */
    @Override
    public JPopupMenu getContextMenu(Editor editor) {
        if (owner(editor) == null || editor.getDot() == null)
            return null;
        final AWTEvent e = editor.getDispatcher().getLastEvent();
        if (!(e instanceof MouseEvent mouse))
            return null;
        final Line line = editor.getDisplay().positionFromPoint(mouse.getPoint()).getLine();
        if (!(line instanceof StatusLine s))
            return null;
        // The caret moves to the line unless the click is inside the selection.
        if (!insideSelection(editor, line))
            editor.moveDotTo(line, 0);
        return menuFor(editor);
    }

    /**
     * vcsStatusContextMenu: the context menu at the caret, for the
     * keyboard's menu key and Shift F10.
     */
    public static void vcsStatusContextMenu(Editor editor) {
        if (owner(editor) == null || !(editor.getDotLine() instanceof StatusLine))
            return;
        final JPopupMenu menu = menuFor(editor);
        if (menu == null)
            return;
        final java.awt.Point at = editor.getDisplay().getCaretPoint();
        menu.show(editor.getDisplay(), at.x + 4, at.y + 16);
    }

    // Whether line is within the editor's selection.
    private static boolean insideSelection(Editor editor, Line line) {
        if (!Region.isSelection(editor))
            return false;
        final int mark = editor.getMark().lineNumber();
        final int dot = editor.getDot().lineNumber();
        final int at = line.lineNumber();
        return at >= Math.min(mark, dot) && at <= Math.max(mark, dot);
    }

    /**
     * The menu for what the caret and selection are on, by line kind. The
     * items run their commands by name on this editor, whose caret the
     * menu put on the line.
     */
    static JPopupMenu menuFor(Editor editor) {
        final VcsStatusBuffer status = owner(editor);
        if (status == null || !(editor.getDotLine() instanceof StatusLine line))
            return null;
        final RepositoryStatus model = status.model();
        final Function<String, Runnable> run = name -> () -> runNamed(editor, name);
        final VcsMenu menu = new VcsMenu(editor, null);
        switch (line.kind()) {
            case FILE, MESSAGE -> {
                if (line.file() == null)
                    return null;
                VcsContextMenus.fileItems(
                    menu,
                    editor,
                    model,
                    StatusActions.targets(model, editor),
                    StatusActions.targets(model, editor, false),
                    line,
                    "Show diff beside",
                    "vcsStatusDiff",
                    Menu.keyText(editor, "vcsStatusDiff"),
                    run);
            }
            case HUNK, DIFF -> hunkItems(menu, editor, model, run);
            case LIST -> {
                final ListedChanges listed = model.listed(line.repository());
                final boolean active = listed != null && listed.active().equals(line.list());
                VcsContextMenus.changelistItems(menu, editor, line.list(), active, run);
            }
            case SECTION -> sectionItems(menu, editor, model, line, run);
            case COMMIT -> commitItems(menu, editor, model, line, run);
            case REPOSITORY -> repositoryItems(menu, editor, run);
            default -> {
                return null;
            }
        }
        menu.separator();
        menu.add("Copy", "copyRegion", true, null, e -> runNamed(editor, "copyRegion"));
        menu.add("Select All", "selectAll", true, null, e -> runNamed(editor, "selectAll"));
        return menu.build();
    }

    // A hunk heading, or diff lines selected: stage, unstage, discard,
    // move, commit alone, pick.
    private static void hunkItems(VcsMenu menu, Editor editor, RepositoryStatus model, Function<String, Runnable> run) {
        final List<Target> targets = StatusActions.targets(model, editor);
        menu.add(
            "Stage",
            "vcsStage",
            StatusActions.refusal(editor, model, Op.STAGE, targets, false) == null,
            StatusActions.refusal(editor, model, Op.STAGE, targets, false),
            e -> run.apply("vcsStage").run());
        menu.add(
            "Unstage",
            "vcsUnstage",
            StatusActions.refusal(editor, model, Op.UNSTAGE, targets, false) == null,
            StatusActions.refusal(editor, model, Op.UNSTAGE, targets, false),
            e -> run.apply("vcsUnstage").run());
        menu.add(
            "Discard…",
            "vcsDiscard",
            StatusActions.refusal(editor, model, Op.DISCARD, targets, false) == null,
            StatusActions.refusal(editor, model, Op.DISCARD, targets, false),
            e -> run.apply("vcsDiscard").run());
        menu.add(
            "Move to changelist…",
            "vcsChangelistMove",
            VcsContextMenus.moveWhy(model, targets) == null,
            VcsContextMenus.moveWhy(model, targets),
            e -> run.apply("vcsChangelistMove").run());
        // Commit alone… takes the hunk or lines under the caret, never the
        // picks, so its why is worked out from those, not the targets.
        final List<Target> commitTargets = StatusActions.targets(model, editor, false);
        menu.add(
            "Commit alone…",
            "vcsCommitFiles",
            VcsContextMenus.commitWhy(model, commitTargets) == null,
            VcsContextMenus.commitWhy(model, commitTargets),
            e -> run.apply("vcsCommitFiles").run());
        menu.add(
            "Pick",
            "vcsStatusPick",
            StatusActions.selected(editor).stream().anyMatch(l -> l != null && StatusPicks.pickable(l)),
            "Nothing to pick here",
            e -> run.apply("vcsStatusPick").run());
    }

    // A section heading: stage, unstage, discard all of it, open or close.
    private static void sectionItems(
            VcsMenu menu,
            Editor editor,
            RepositoryStatus model,
            StatusLine line,
            Function<String, Runnable> run) {
        final List<Target> targets = StatusActions.targets(model, editor);
        menu.add(
            "Stage all",
            "vcsStage",
            StatusActions.refusal(editor, model, Op.STAGE, targets, false) == null,
            StatusActions.refusal(editor, model, Op.STAGE, targets, false),
            e -> run.apply("vcsStage").run());
        menu.add(
            "Unstage all",
            "vcsUnstage",
            StatusActions.refusal(editor, model, Op.UNSTAGE, targets, false) == null,
            StatusActions.refusal(editor, model, Op.UNSTAGE, targets, false),
            e -> run.apply("vcsUnstage").run());
        menu.add(
            "Discard all…",
            "vcsDiscard",
            StatusActions.refusal(editor, model, Op.DISCARD, targets, false) == null,
            StatusActions.refusal(editor, model, Op.DISCARD, targets, false),
            e -> run.apply("vcsDiscard").run());
        menu.add(
            line.isOpen() ? "Close" : "Open",
            "vcsStatusToggle",
            line.isExpandable(),
            "Nothing to open here",
            e -> run.apply("vcsStatusToggle").run());
    }

    // A commit line: show it, copy its hash, show it in the log, reword if HEAD.
    // A stash line: apply, pop, drop.
    private static void commitItems(
            VcsMenu menu,
            Editor editor,
            RepositoryStatus model,
            StatusLine line,
            Function<String, Runnable> run) {
        if (line.stash() != null) {
            menu.add("Apply", "vcsStashApply", true, null, e -> run.apply("vcsStashApply").run());
            menu.add("Pop", "vcsStashPop", true, null, e -> run.apply("vcsStashPop").run());
            menu.add("Drop…", "vcsStashDrop", true, null, e -> run.apply("vcsStashDrop").run());
            return;
        }
        menu.add("Show commit", "vcsStatusVisit", true, null, e -> run.apply("vcsStatusVisit").run());
        menu.add(
            "Copy hash",
            "vcsCopyHash",
            line.commit() != null,
            "No commit here",
            e -> run.apply("vcsCopyHash").run());
        menu.add(
            "Show in log",
            "vcsLogCommit",
            line.commit() != null,
            "No commit here",
            e -> run.apply("vcsLogCommit").run());
        if (isHead(model, line))
            menu.add("Reword", "vcsCommitReword", true, null, e -> run.apply("vcsCommitReword").run());
    }

    // Whether line is HEAD's commit: a ref on it is HEAD.
    private static boolean isHead(RepositoryStatus model, StatusLine line) {
        return line.commit() != null
                && line.commit()
                        .refs()
                        .stream()
                        .anyMatch(r -> r.head() && r.kind() == org.armedbear.j.vcs.log.LogRef.Kind.BRANCH);
    }

    // A repository heading: fetch, refresh, log.
    private static void repositoryItems(VcsMenu menu, Editor editor, Function<String, Runnable> run) {
        menu.add("Fetch", "vcsFetchPopup", true, null, e -> run.apply("vcsFetchPopup").run());
        menu.add("Refresh", "vcsStatusRefresh", true, null, e -> run.apply("vcsStatusRefresh").run());
        menu.add("Log", "vcsLog", true, null, e -> run.apply("vcsLog").run());
    }

    // Runs command on editor through its dispatcher, as a menu item does.
    private static void runNamed(Editor editor, String command) {
        final ActionEvent e = new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, command);
        editor.getDispatcher().actionPerformed(e);
    }
}
