/*
 * CommitCommands.java
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.BufferCommands;
import org.armedbear.j.CommentRing;
import org.armedbear.j.Editor;
import org.armedbear.j.Frame;
import org.armedbear.j.Log;
import org.armedbear.j.MotionCommands;
import org.armedbear.j.WindowCommands;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.Transient.Switch;
import org.armedbear.j.transients.TransientArgs;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.transients.Transients;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.CommandLog;
import org.armedbear.j.vcs.CommitOption;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vim.VimInputHandler;
import org.armedbear.j.vim.VimMode;

/**
 * Committing from a status buffer: the commit popup (c), whose switches are
 * kept per buffer and saved as defaults with Ctrl S, and the message
 * buffer the commit, amend and reword commands open.
 */
public final class CommitCommands {
    private CommitCommands() {}

    static final String ID = "vcs.commit";

    /**
     * The commit popup: its switches, and what to commit. In a status of
     * several repositories, each line names the ones it commits to.
     */
    public static Transient popup(Editor editor) {
        final VcsStatusBuffer status =
                editor != null && editor.getBuffer() instanceof VcsStatusBuffer b && b.getRepositories().size() > 1
                        ? b
                        : null;
        String commit = "commit";
        String amend = "amend";
        String extend = "extend, keeping its message";
        String reword = "reword";
        if (status != null) {
            final VcsRepository caret = repository(editor, status);
            final List<VcsRepository> targets = targets(status, caret, options(editor));
            commit = targets.isEmpty() ? "commit: nothing is staged" : "commit to " + names(targets);
            final String last = caret.name() + "'s last commit";
            amend = "amend " + last;
            extend = "extend " + last + ", keeping its message";
            reword = "reword " + last;
        }
        return new Transient(ID,
                "Commit",
                "git.html#committing",
                List.of(
                    Group.of(
                        "Arguments",
                        Switch.of("-a", "--all", "stage every change first"),
                        Switch.of("-e", "--allow-empty", "allow an empty commit"),
                        Switch.of("-n", "--no-verify", "skip the hooks"),
                        Switch.of("-s", "--signoff", "sign off"),
                        Switch.of("-v", "--verbose", "show the diff under the message")),
                    Group.of("Create", Command.of("c", "vcsCommit", commit)),
                    Group.of(
                        "Edit the last commit",
                        Command.of("a", "vcsCommitAmend", amend),
                        Command.of("e", "vcsCommitExtend", extend),
                        Command.of("w", "vcsCommitReword", reword))));
    }

    /** "x", or "3 repositories: x, y, z". */
    static String names(List<VcsRepository> repositories) {
        if (repositories.size() == 1)
            return repositories.get(0).name();
        final List<String> names = new ArrayList<>();
        for (VcsRepository r : repositories)
            names.add(r.name());
        return repositories.size() + " repositories: " + String.join(", ", names);
    }

    /**
     * What a commit with options takes: changing the last commit, the
     * caret's repository; else in a status of several, each with something
     * staged, or with -a any tracked change, or with none and an empty
     * commit allowed, the caret's; else the caret's.
     */
    static List<VcsRepository> targets(VcsStatusBuffer status, VcsRepository caret, Set<CommitOption> options) {
        if (options.contains(CommitOption.AMEND) || status.getRepositories().size() == 1)
            return List.of(caret);
        final List<VcsRepository> out = new ArrayList<>();
        for (VcsRepository r : status.getRepositories())
            if (!status.files(r, "staged").isEmpty()
                    || options.contains(CommitOption.ALL) && !status.files(r, "unstaged").isEmpty())
                out.add(r);
        return out.isEmpty() && options.contains(CommitOption.ALLOW_EMPTY) ? List.of(caret) : out;
    }

    /** Shows a message buffer in a window of its own, beside the status; tests show it their own way. */
    static BiFunction<Editor, CommitMessageBuffer, Editor> show =
            (editor, buffer) -> editor.activateInOtherWindow(buffer);

    public static void vcsCommitPopup(Editor editor) {
        final Editor at = statusWindow(editor);
        if (at == null)
            return;
        if (!VcsStatusMode.stages(at.getBuffer())) {
            at.status("Nothing here stages to commit");
            return;
        }
        TransientPopup.open(at, popup(at));
    }

    // The window to commit from: editor's on a status; from the status's
    // diff or comparison, the status's; from another buffer, its
    // repository's status, opened. Made current; null for none.
    static Editor statusWindow(Editor editor) {
        if (editor.getBuffer() instanceof VcsStatusBuffer)
            return editor;
        final VcsStatusBuffer owner = VcsStatusMode.owner(editor);
        final Editor at = owner != null ? Editor.windowShowing(owner) : VcsStatusMode.openStatus(editor);
        if (at == null || !(at.getBuffer() instanceof VcsStatusBuffer))
            return null;
        if (at != editor && at.getFrame() != null && editor.getFrame() == at.getFrame())
            WindowCommands.switchWindow(editor, at);
        return at;
    }

    // The popup's switches, as options.
    static Set<CommitOption> options(Editor editor) {
        final TransientArgs args = Transients.args(editor, ID);
        final Set<CommitOption> options = EnumSet.noneOf(CommitOption.class);
        if (args.isOn("--all"))
            options.add(CommitOption.ALL);
        if (args.isOn("--allow-empty"))
            options.add(CommitOption.ALLOW_EMPTY);
        if (args.isOn("--no-verify"))
            options.add(CommitOption.NO_VERIFY);
        if (args.isOn("--signoff"))
            options.add(CommitOption.SIGNOFF);
        return options;
    }

    // The repository of the thing at the caret, or the buffer's only one.
    private static VcsRepository repository(Editor editor, VcsStatusBuffer status) {
        if (editor.getDot() != null && editor.getDotLine() instanceof StatusLine s && s.repository() != null)
            return s.repository();
        return status.getRepositories().get(0);
    }

    public static void vcsCommit(Editor editor) {
        start(editor, EnumSet.noneOf(CommitOption.class));
    }

    public static void vcsCommitAmend(Editor editor) {
        start(editor, EnumSet.of(CommitOption.AMEND));
    }

    /** Adds what is staged to the last commit, keeping its message: no message to write. */
    public static void vcsCommitExtend(Editor editor) {
        start(editor, EnumSet.of(CommitOption.AMEND, CommitOption.KEEP_MESSAGE));
    }

    /** Changes the last commit's message only. */
    public static void vcsCommitReword(Editor editor) {
        start(editor, EnumSet.of(CommitOption.AMEND, CommitOption.MESSAGE_ONLY));
    }

    private static void start(Editor editor, Set<CommitOption> kind) {
        if (!(editor.getBuffer() instanceof VcsStatusBuffer status))
            return;
        final VcsRepository caret = repository(editor, status);
        final Set<CommitOption> options = options(editor);
        options.addAll(kind);
        // A reword takes nothing staged, so nothing to stage first.
        if (options.contains(CommitOption.MESSAGE_ONLY))
            options.remove(CommitOption.ALL);
        final boolean takesStaged = !options.contains(CommitOption.MESSAGE_ONLY)
                && !(options.contains(CommitOption.AMEND) && !options.contains(CommitOption.KEEP_MESSAGE));
        final List<VcsRepository> targets = targets(status, caret, options);
        if (takesStaged
                && !options.contains(CommitOption.ALL)
                && !options.contains(CommitOption.ALLOW_EMPTY)
                && (targets.isEmpty() || status.files(targets.get(0), "staged").isEmpty())) {
            editor.status(
                (status.getRepositories().size() > 1 ? "Nothing is staged in any repository" : "Nothing is staged")
                        + ": s stages the thing at the caret, or -a in the popup every change");
            return;
        }
        if (targets.isEmpty()) {
            editor.status("Nothing to commit in any repository");
            return;
        }
        if (options.contains(CommitOption.KEEP_MESSAGE)) {
            commit(editor, status, targets, null, options, null);
            return;
        }
        final boolean verboseSwitch = Transients.args(editor, ID).isOn("--verbose");
        Background.start("vcs commit message", () -> {
            final String last = options.contains(CommitOption.AMEND) ? caret.backend().lastMessage(caret) : "";
            if (verboseSwitch || caret.backend().verboseCommits(caret))
                options.add(CommitOption.VERBOSE);
            // Each repository's diff, headed by its name when there are several.
            final StringBuilder diffs = new StringBuilder();
            if (options.contains(CommitOption.VERBOSE))
                for (VcsRepository r : targets) {
                    final byte[] diff = r.backend().readCommitDiff(r, options);
                    if (targets.size() > 1)
                        diffs.append("# ")
                                .append("=".repeat(20))
                                .append(' ')
                                .append(r.name())
                                .append(' ')
                                .append("=".repeat(20))
                                .append('\n');
                    if (diff != null)
                        diffs.append(new String(diff, StandardCharsets.UTF_8));
                }
            SwingUtilities.invokeLater(() -> {
                if (last == null) {
                    editor.status("There is no commit to change");
                    return;
                }
                // A message with # lines of its own keeps them, with no template's.
                if (last.lines().anyMatch(l -> l.startsWith("#")))
                    options.add(CommitOption.VERBATIM);
                final String diffText = options.contains(CommitOption.VERBOSE) ? diffs.toString() : null;
                open(editor, status, targets, options, diffText, template(status, targets, options, last, diffText));
            });
        });
    }

    /** git's line over a verbose message's diff: nothing from it down is in the message. */
    static final String SCISSORS = "# ------------------------ >8 ------------------------";

    // The message, then what the commit takes as # lines, which git leaves
    // out: what is staged, and with -a the rest of the tracked changes; with
    // a diff, the diff under the scissors line.
    static String template(
            VcsStatusBuffer status,
            List<VcsRepository> repositories,
            Set<CommitOption> options,
            String message,
            String diff) {
        final String below = diff == null
                ? ""
                : "\n" + SCISSORS + "\n# Do not change or remove the line above.\n"
                        + "# Everything below it is left out.\n" + diff;
        if (options.contains(CommitOption.VERBATIM))
            return message + below;
        final StringBuilder sb = new StringBuilder(message);
        sb.append("\n\n");
        final String reach = reach(status, repositories, options);
        if (reach != null)
            sb.append(reach).append('\n');
        sb.append("# Lines starting with # are left out.\n");
        if (options.contains(CommitOption.MESSAGE_ONLY)) {
            sb.append("# Only the message changes.\n");
            return sb + below;
        }
        for (VcsRepository repository : repositories) {
            final List<FileItem> files = new ArrayList<>(status.files(repository, "staged"));
            if (options.contains(CommitOption.ALL))
                for (FileItem f : status.files(repository, "unstaged"))
                    if (files.stream().noneMatch(s -> s.path().equals(f.path())))
                        files.add(f);
            if (files.isEmpty())
                continue;
            final String what = options.contains(CommitOption.ALL) ? "to commit" : "staged";
            sb.append("# ")
                    .append(repositories.size() > 1 ? repository.name() + ", " + what : capitalized(what))
                    .append(":\n");
            for (FileItem f : files)
                sb.append("#   ").append(f.letter()).append("  ").append(f.path()).append('\n');
        }
        return sb + below;
    }

    /**
     * In a status of several repositories, the line saying which the commit
     * takes: "# COMMITS TO 3 REPOSITORIES, ONE EACH: x, y, z", or which one
     * alone and why not the others; null with one.
     */
    static String reach(VcsStatusBuffer status, List<VcsRepository> repositories, Set<CommitOption> options) {
        if (status == null || status.getRepositories().size() < 2)
            return null;
        if (repositories.size() > 1) {
            final List<String> names = new ArrayList<>();
            for (VcsRepository r : repositories)
                names.add(r.name());
            return "# COMMITS TO " + repositories.size() + " REPOSITORIES, ONE EACH: " + String.join(", ", names);
        }
        final VcsRepository r = repositories.get(0);
        if (options.contains(CommitOption.AMEND))
            return "# CHANGES " + r.name() + "'S LAST COMMIT ALONE, not the other repositories'";
        final List<String> others = new ArrayList<>();
        for (VcsRepository o : status.getRepositories())
            if (o != r)
                others.add(o.name());
        return "# COMMITS TO " + r.name() + " ALONE: " + String.join(", ", others)
                + (others.size() == 1 ? " has" : " have") + " nothing "
                + (options.contains(CommitOption.ALL) ? "to commit" : "staged");
    }

    /** Whether text is reach's line. */
    static boolean isReach(String text) {
        return text.startsWith("# COMMITS TO ") || text.startsWith("# CHANGES ");
    }

    private static String capitalized(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void open(
            Editor editor,
            VcsStatusBuffer status,
            List<VcsRepository> repositories,
            Set<CommitOption> options,
            String diff,
            String text) {
        final CommitMessageBuffer buffer = new CommitMessageBuffer(repositories, options, status, text);
        buffer.setDiff(diff);
        buffer.setStarted(buffer.getMessage());
        final Editor ed = show.apply(editor, buffer);
        if (ed == null)
            return;
        ed.setDot(buffer.getFirstLine(), buffer.getFirstLine().length());
        ed.moveCaretToDotCol();
        // A message is for writing: in vim edit mode, start inserting.
        if (ed.getInputHandler() instanceof VimInputHandler vim)
            vim.getState().beginInsert(ed, VimMode.INSERT);
        ed.updateDisplay();
    }

    /** Commits the message being written. */
    public static void vcsCommitFinish(Editor editor) {
        if (!(editor.getBuffer() instanceof CommitMessageBuffer buffer) || buffer.isCommitting())
            return;
        final String message = buffer.getMessage();
        if (message.isEmpty()) {
            editor.status("The message is empty");
            return;
        }
        commit(editor, buffer.getStatus(), buffer.getRepositories(), message, buffer.getOptions(), buffer);
    }

    private static void commit(
            Editor editor,
            VcsStatusBuffer status,
            List<VcsRepository> repositories,
            String message,
            Set<CommitOption> options,
            CommitMessageBuffer buffer) {
        // A commit changes what the status shows, as staging does.
        if (status != null && status.isWriting()) {
            editor.status("Wait: the last change is still being made");
            return;
        }
        if (buffer != null)
            buffer.setCommitting(true);
        if (status != null)
            status.startWriting();
        editor.status(repositories.size() == 1 ? "Committing…" : "Committing to " + names(repositories) + "…");
        Background.start("vcs commit", () -> {
            // One after another; whatever happens, the message and the
            // status take keys again.
            final List<VcsResult> results = new ArrayList<>();
            for (VcsRepository repository : repositories) {
                VcsResult result = null;
                try {
                    result = repository.backend().commit(repository, message, options);
                }
                catch (RuntimeException e) {
                    Log.error(e);
                }
                results.add(result);
            }
            if (status != null)
                status.wrote();
            for (VcsRepository repository : repositories) {
                repository.invalidate();
                repository.refresh();
            }
            SwingUtilities.invokeLater(() -> committed(editor, status, repositories, message, buffer, results));
        });
    }

    // Each commit made said; the message closed if all were, else kept
    // for those that failed, saying which they are.
    private static void committed(
            Editor editor,
            VcsStatusBuffer status,
            List<VcsRepository> repositories,
            String message,
            CommitMessageBuffer buffer,
            List<VcsResult> results) {
        final List<String> done = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<VcsRepository> left = new ArrayList<>();
        for (int i = 0; i < repositories.size(); i++) {
            final VcsRepository r = repositories.get(i);
            final VcsResult result = results.get(i);
            if (result != null && result.succeeded())
                done.add(said(r, result, repositories.size()));
            else {
                left.add(r);
                failed.add(
                    (repositories.size() > 1 ? r.name() + ": " : "")
                            + (result == null ? r.backend().name() + " couldn't commit" : firstLine(result)));
            }
        }
        if (!done.isEmpty() && message != null)
            CommentRing.getInstance().appendNew(message);
        if (left.isEmpty()) {
            if (buffer != null)
                close(editor, buffer);
            Editor.currentEditor()
                    .status(repositories.size() == 1 ? done.get(0) : "Committed to " + String.join(", ", done));
            return;
        }
        if (buffer != null) {
            buffer.setCommitting(false);
            if (!done.isEmpty()) {
                buffer.setRepositories(left);
                for (Editor ed : Editor.getEditorList())
                    if (ed.getBuffer() == buffer)
                        replace(
                            ed,
                            buffer,
                            template(status, left, buffer.getOptions(), buffer.getMessage(), buffer.getDiff()));
            }
        }
        final String failures = repositories.size() == 1
                ? repositories.get(0).backend().name() + ": " + failed.get(0)
                : "Commit failed in " + String.join("; ", failed);
        editor.status(
            done.isEmpty()
                    ? failures
                    : "Committed to " + String.join(", ", done) + "; " + failures
                            + (buffer != null ? ": Ctrl Enter commits to " + names(left) : ""));
        if (results.stream().anyMatch(r -> r != null) && editor.getFrame() != null)
            CommandLog.vcsCommandLog();
    }

    // git's "[main 1a2b3c4] subject", "[main (root-commit) 1a2b3c4] subject",
    // "[detached HEAD f10f71e] subject".
    private static final Pattern SAID = Pattern.compile("^\\[(?:.*? )?([0-9a-f]{4,40})\\]");

    // git's line; with several, "x (1a2b3c4)".
    private static String said(VcsRepository r, VcsResult result, int of) {
        final String out = result.out().strip();
        final String first = out.isEmpty() ? "Committed" : out.split("\n", 2)[0];
        if (of == 1)
            return first;
        final Matcher m = SAID.matcher(first);
        return m.find() ? r.name() + " (" + m.group(1) + ")" : r.name();
    }

    private static String firstLine(VcsResult r) {
        final String err = r.stderr().strip();
        if (!err.isEmpty())
            return err.split("\n", 2)[0];
        final String out = r.out().strip();
        return out.isEmpty() ? "exit code " + r.exitCode() : out.split("\n", 2)[0];
    }

    /** Gives up the message being written, asking first if it has one. */
    public static void vcsCommitCancel(Editor editor) {
        if (!(editor.getBuffer() instanceof CommitMessageBuffer buffer))
            return;
        if (!CommitMessageMode.getMode().confirmClose(editor, buffer))
            return;
        buffer.setClosing(); // Asked already.
        close(editor, buffer);
    }

    /** Gives up the message without asking, as vim's :q! and ZQ do; it goes to the comment ring. */
    public static void vcsCommitAbort(Editor editor) {
        if (!(editor.getBuffer() instanceof CommitMessageBuffer buffer))
            return;
        if (buffer.isCommitting()) {
            editor.status("Committing: it closes when the commit is made");
            return;
        }
        if (!buffer.getMessage().isEmpty())
            CommentRing.getInstance().appendNew(buffer.getMessage());
        close(editor, buffer);
    }

    // The message's window goes, if it has one of its own, and the buffer with it.
    private static void close(Editor editor, CommitMessageBuffer buffer) {
        buffer.setClosing();
        Editor shown = null;
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == buffer)
                shown = ed;
        if (shown == null) {
            buffer.kill();
            return;
        }
        final Frame frame = shown.getFrame();
        if (frame != null && frame.getEditorCount() > 1) {
            frame.closeEditor(shown);
            if (Editor.getBufferList().contains(buffer))
                BufferCommands.maybeKillBuffer(Editor.currentEditor(), buffer);
        } else
            BufferCommands.maybeKillBuffer(shown, buffer);
    }

    public static void vcsCommitPreviousMessage(Editor editor) {
        ringMessage(editor, -1);
    }

    public static void vcsCommitNextMessage(Editor editor) {
        ringMessage(editor, 1);
    }

    // Steps through the comment ring: the messages committed or given up.
    private static void ringMessage(Editor editor, int step) {
        if (!(editor.getBuffer() instanceof CommitMessageBuffer buffer))
            return;
        final CommentRing ring = CommentRing.getInstance();
        if (ring.size() == 0) {
            editor.status("No earlier messages");
            return;
        }
        // Ctrl P goes back from the newest, Ctrl N forward to the message
        // being written, as a shell's history does; -1 is that message.
        final int at = buffer.ringIndex < 0 ? ring.size() : buffer.ringIndex;
        final int next = at + step;
        if (next < 0) {
            editor.status("No earlier messages");
            return;
        }
        if (next >= ring.size()) {
            if (buffer.ringIndex < 0) {
                editor.status("No later messages");
                return;
            }
            buffer.ringIndex = -1;
            replace(
                editor,
                buffer,
                template(
                    buffer.getStatus(),
                    buffer.getRepositories(),
                    buffer.getOptions(),
                    buffer.getStarted(),
                    buffer.getDiff()));
            return;
        }
        buffer.ringIndex = next;
        final String message = ring.get(next);
        if (message == null)
            return;
        replace(
            editor,
            buffer,
            template(buffer.getStatus(), buffer.getRepositories(), buffer.getOptions(), message, buffer.getDiff()));
    }

    /** Replaces the message buffer's text, as one edit undo takes back. */
    static void replace(Editor editor, CommitMessageBuffer buffer, String text) {
        buffer.withWriteLock(() -> {
            final CompoundEdit edit = editor.beginCompoundEdit();
            MotionCommands.selectAll(editor);
            editor.deleteRegion();
            editor.insertString(text);
            editor.endCompoundEdit(edit);
        });
        editor.setDot(buffer.getFirstLine(), buffer.getFirstLine().length());
        editor.moveCaretToDotCol();
        editor.setUpdateFlag(REPAINT);
        editor.updateDisplay();
    }
}
