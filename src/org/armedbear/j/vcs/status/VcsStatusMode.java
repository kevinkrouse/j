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

import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Formatter;
import org.armedbear.j.Frame;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.MotionCommands;
import org.armedbear.j.ProjectRoot;
import org.armedbear.j.Property;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.Transient.Prefix;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.Capability;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.ViewPane;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.log.LogBuffer;
import org.armedbear.j.vcs.log.LogCommands;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * The status buffer's mode. Nothing in it is edited, so the same letters
 * work in both edit modes, but for what vim keeps for moving: refresh is r
 * there, as gg must still go to the top.
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

    @Override
    public String getHelpTopic() {
        return "git.html#status";
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        common(km);
        km.mapKey('g', "vcsStatusRefresh");
    }

    private static KeyMap modalKeyMap;

    @Override
    public synchronized KeyMap getModalKeyMap() {
        if (modalKeyMap == null) {
            final KeyMap km = new KeyMap();
            common(km);
            km.mapKey('r', "vcsStatusRefresh");
            modalKeyMap = km;
        }
        return modalKeyMap;
    }

    private static KeyMap paneKeyMap;
    private static KeyMap paneModalKeyMap;

    // The diff beside has its own keys: none of staging's, which act on the status's lines.
    @Override
    public synchronized KeyMap getKeyMap(Buffer buffer) {
        if (!(buffer instanceof StatusDiffBuffer))
            return getKeyMap();
        if (paneKeyMap == null) {
            paneKeyMap = new KeyMap();
            paneKeys(paneKeyMap, false);
        }
        return paneKeyMap;
    }

    @Override
    public synchronized KeyMap getModalKeyMap(Buffer buffer) {
        if (!(buffer instanceof StatusDiffBuffer))
            return getModalKeyMap();
        if (paneModalKeyMap == null) {
            paneModalKeyMap = new KeyMap();
            paneKeys(paneModalKeyMap, true);
        }
        return paneModalKeyMap;
    }

    // As the status's where they mean the same, and as the log's diff's.
    private static void paneKeys(KeyMap km, boolean vim) {
        km.mapKey(KeyEvent.VK_ENTER, 0, "vcsStatusVisit");
        km.mapKey(VK_DOUBLE_MOUSE_1, 0, "vcsStatusVisit");
        km.mapKey(KeyEvent.VK_TAB, 0, "vcsStatusToggle");
        km.mapKey(KeyEvent.VK_TAB, SHIFT_MASK, "vcsStatusCycle");
        km.mapKey('n', "vcsStatusNext");
        km.mapKey('p', "vcsStatusPrevious");
        scrollKeys(km);
        km.mapKey(vim ? 'r' : 'g', "vcsStatusRefresh");
        km.mapKey('$', "vcsCommandLog");
        km.mapKey('q', "vcsStatusDiff");
        km.mapKey('?', "modePopup");
    }

    // D, and the diff beside scrolled from either side.
    private static void scrollKeys(KeyMap km) {
        km.mapKey('D', "vcsStatusDiff");
        km.mapKey(' ', "vcsStatusScrollDiff");
        km.mapKey(KeyEvent.VK_SPACE, SHIFT_MASK, "vcsStatusScrollDiffBack");
        km.mapKey(KeyEvent.VK_BACK_SPACE, 0, "vcsStatusScrollDiffBack");
    }

    private static KeyMap visualKeyMap;

    // Vim's V or v then s stages the lines selected, d discards them.
    @Override
    public synchronized KeyMap getVisualKeyMap() {
        if (visualKeyMap == null) {
            final KeyMap km = new KeyMap();
            km.mapKey('s', "vcsStage");
            km.mapKey('u', "vcsUnstage");
            km.mapKey('d', "vcsDiscard");
            visualKeyMap = km;
        }
        return visualKeyMap;
    }

    private static void common(KeyMap km) {
        km.mapKey(KeyEvent.VK_ENTER, 0, "vcsStatusVisit");
        km.mapKey(VK_DOUBLE_MOUSE_1, 0, "vcsStatusVisit");
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
        // d, as lazygit's: x clears filters in every view that has them.
        km.mapKey('d', "vcsDiscard");
        km.mapKey('S', "vcsStageAll");
        km.mapKey('U', "vcsUnstageAll");
        km.mapKey('c', "vcsCommitPopup");
        km.mapKey('l', "vcsLogPopup");
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
        { "vcsStatusDiff", "close" },
        { "modePopup", "more" }, };

    @Override
    public String[][] getHints(Buffer buffer) {
        if (!buffer.getBooleanProperty(Property.VCS_HINTS))
            return null;
        return buffer instanceof StatusDiffBuffer ? DIFF_HINTS : HINTS;
    }

    @Override
    public Transient getTransient(Editor editor) {
        final boolean vim = editor != null && editor.getInputHandler() != null;
        if (editor != null && editor.getBuffer() instanceof StatusDiffBuffer)
            return diffTransient(vim);
        final List<Group> groups = new ArrayList<>();
        groups.add(
            Group.of(
                "Show",
                new Command("1", "vcsStatusDepth 1", "sections", true),
                new Command("2", "vcsStatusDepth 2", "files", true),
                new Command("3", "vcsStatusDepth 3", "hunks", true),
                new Command("4", "vcsStatusDepth 4", "diffs", true)));
        groups.add(
            Group.of(
                "Go",
                new Command("n", "vcsStatusNext", "next", true),
                new Command("p", "vcsStatusPrevious", "previous", true)));
        groups.add(Group.of("Diff", Command.of("D", "vcsStatusDiff", "beside, or not")));
        // Staging, and so committing what is staged, is for a backend that has an index.
        if (editor == null || stages(editor.getBuffer())) {
            groups.add(
                Group.of(
                    "Stage",
                    new Command("s", "vcsStage", "stage", true),
                    new Command("u", "vcsUnstage", "unstage", true),
                    new Command("d", "vcsDiscard", "discard", true),
                    new Command("S", "vcsStageAll", "stage all", true),
                    new Command("U", "vcsUnstageAll", "unstage all", true)));
            groups.add(Group.of("Commit", new Prefix("c", "commit…", CommitCommands::popup)));
        }
        groups.add(Group.of("Log", new Prefix("l", "log…", () -> LogCommands.openingPopup(editor))));
        groups.add(
            Group.of(
                "Repository",
                Command.of(vim ? "r" : "g", "vcsStatusRefresh", "refresh"),
                Command.of("$", "vcsCommandLog", "command log"),
                Command.of("q", "killBuffer", "close")));
        return new Transient("vcs.status", "Status", "git.html#status", groups);
    }

    // The diff beside's: what its keys do there.
    private static Transient diffTransient(boolean vim) {
        return new Transient("vcs.status.diff",
                "Diff",
                "git.html#status-diff",
                List.of(
                    Group.of(
                        "Go",
                        new Command("n", "vcsStatusNext", "next hunk", true),
                        new Command("p", "vcsStatusPrevious", "previous hunk", true)),
                    Group.of(
                        "Diff",
                        Command.of(vim ? "r" : "g", "vcsStatusRefresh", "refresh"),
                        Command.of("$", "vcsCommandLog", "command log"),
                        Command.of("q", "vcsStatusDiff", "close"))));
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
        StatusLine head = b.head(s);
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
        final List<VcsRepository> repositories = repositoriesFor(editor.getBuffer());
        if (repositories.isEmpty()) {
            editor.status("Not in a repository");
            return;
        }
        final boolean opening = VcsStatusBuffer.find(repositories) == null;
        final VcsStatusBuffer buffer = VcsStatusBuffer.of(repositories);
        editor.show(buffer);
        if (opening && "right".equals(Editor.preferences().getStringProperty(Property.VCS_STATUS_DIFF)))
            buffer.openDiff(editor.getFrame());
    }

    // The project's, if it has more than one and the buffer is in one of
    // them; else the buffer's own.
    public static List<VcsRepository> repositoriesFor(Buffer buffer) {
        if (buffer instanceof VcsStatusBuffer s)
            return s.getRepositories();
        if (buffer instanceof StatusDiffBuffer d)
            return d.getOwner().getRepositories();
        // A log's, from its list or its files or diff.
        final Buffer view = buffer instanceof ViewPane<?> pane ? pane.getOwner() : buffer;
        if (view instanceof LogBuffer log)
            return log.getScope().repositories();
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

    // The status of editor's buffer: the buffer itself, or the status whose diff it is.
    private static VcsStatusBuffer owner(Editor editor) {
        return editor.getBuffer() instanceof StatusDiffBuffer d ? d.getOwner() : statusBuffer(editor);
    }

    /**
     * D: the diff of the file at the caret beside the status, or not; open
     * but not laid out, as with its window closed by hand, laid out again.
     */
    public static void vcsStatusDiff(Editor editor) {
        final VcsStatusBuffer b = owner(editor);
        if (b == null)
            return;
        final Frame frame = editor.getFrame();
        if (b.getDiff() == null)
            b.openDiff(frame);
        else if (frame != null && !b.isDiffShown(frame))
            frame.showView(b, editor.getBuffer());
        else
            b.closeDiff(frame);
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
        final List<Editor> windows = d == null ? List.of() : d.windows();
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
                            : s.isExpandable() || s.kind() == Kind.COMMIT)) {
                editor.moveDotTo(l, 0);
                return;
            }
        }
    }

    /**
     * Visits the thing at the caret: a file, at the line of the hunk or diff
     * line, a staged one's through the working tree's changes since; or a
     * commit, shown as a diff.
     */
    public static void vcsStatusVisit(Editor editor) {
        final StatusLine line = dotLine(editor);
        if (line == null)
            return;
        if (line.kind() == Kind.COMMIT) {
            showCommit(editor, line);
            return;
        }
        final FileItem file = line.file();
        if (file == null) {
            editor.status("Nothing to visit here");
            return;
        }
        final VcsRepository repository = line.repository();
        final File f = File.getInstance(repository.root().resolve(file.path()).toString());
        if (f == null || !f.isFile()) {
            editor.status(file.path() + " is not in the working tree");
            return;
        }
        final int lineNumber = lineNumber(line);
        if (!file.staged() || lineNumber <= 0) {
            visit(editor, f, lineNumber);
            return;
        }
        Background.start("vcs visit", () -> {
            final List<FileDiff> unstaged = repository.backend().readDiff(repository, false, List.of(file.path()));
            final int mapped = unstaged.isEmpty() ? lineNumber : throughHunks(unstaged.get(0).hunks(), lineNumber);
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
    private static int firstChange(Hunk hunk) {
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

    private static void visit(Editor editor, File file, int lineNumber) {
        final Buffer buffer = Editor.getBuffer(file);
        if (buffer != null)
            showAt(editor, buffer, lineNumber);
    }

    /** Shows buffer in editor's window at its line lineNumber, from 1; 0 for where it was. */
    public static void showAt(Editor editor, Buffer buffer, int lineNumber) {
        final Editor ed = editor.show(buffer);
        if (lineNumber > 0) {
            ed.jumpToLine(lineNumber - 1);
            ed.getDisplay().setUpdateFlag(REFRAME);
        }
        ed.updateDisplay();
    }

    private static void showCommit(Editor editor, StatusLine line) {
        final VcsRepository repository = line.repository();
        final String hash = line.commit().hash();
        Background.start("vcs show", () -> {
            final byte[] text = repository.backend().readCommit(repository, hash);
            SwingUtilities.invokeLater(() -> {
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
        });
    }
}
