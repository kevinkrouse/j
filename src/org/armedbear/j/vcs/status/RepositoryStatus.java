/*
 * RepositoryStatus.java
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Log;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Diff;
import org.armedbear.j.util.PickState;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.Anchors;
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.selection.FileKey;
import org.armedbear.j.vcs.selection.Selections;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.signs.SignsUpdater;
import org.armedbear.j.vcs.signs.StatusView;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Item;
import org.armedbear.j.vcs.status.StatusData.Role;
import org.armedbear.j.vcs.status.StatusData.Section;

/**
 * What version control says of one repository, or of a project's several:
 * its sections and their diffs as last read, read again in the background
 * as the repositories change; the work tree's changes in their
 * changelists; and what is picked. One for each set of repositories,
 * shared by the status buffer that shows it, the sidebar's Changes tab,
 * and its files' change marks, which take its diffs and picks; open while
 * one of them holds it, or anything is picked or waits to be.
 */
public final class RepositoryStatus implements StatusView.Source, DiffWhitespace.Listener {
    /** What shows it: told as it reads, its lists change, or what is picked does. */
    interface Listener {
        /**
         * What was read, or the lists, changed: shown again, the picks it
         * dropped, if any, said with why.
         */
        void read(String droppedMessage);

        /** What is picked changed. */
        void picked();

        /** A read came, new or not: a file shown may have changed with nothing else. */
        default void refreshed() {}

        /** Whether it takes the diff of the file whose line is keyed key, as one shown open. */
        default boolean wantsDiff(FileKey key) {
            return false;
        }
    }

    // Those open, in the order opened.
    private static final List<RepositoryStatus> open = new ArrayList<>();

    private final List<VcsRepository> repositories;
    // What was last read of each repository, on the event thread.
    private final Map<VcsRepository, StatusData> data = new LinkedHashMap<>();
    // The roles of each repository's sections, by id, as reads have shown
    // them, and its first WORKING section's; ids are stable.
    private final Map<VcsRepository, Map<String, Role>> roles = new ConcurrentHashMap<>();
    private final Map<VcsRepository, String> workingIds = new ConcurrentHashMap<>();
    // Files whose diffs have been read, so that none means there is none.
    private final Set<FileKey> diffsRead = new HashSet<>();
    // Requests made in the status currently shown; earlier reads cannot
    // turn a currently unread diff into a successful empty one.
    private Set<FileKey> currentDiffReads = Set.of();
    private final Consumer<VcsRepository> changed = r -> refresh();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Set<Object> holders = new HashSet<>();
    private boolean reading;
    private boolean readAgain;
    private volatile boolean disposed;
    // A write is running, or its refresh not shown yet: what was read is old.
    private volatile boolean writing;
    private final AtomicLong clock = new AtomicLong();
    private volatile long wroteAt;

    private RepositoryStatus(List<VcsRepository> repositories) {
        this.repositories = List.copyOf(repositories);
        for (VcsRepository r : repositories)
            r.addListener(changed);
        SignsUpdater.addSource(this);
        DiffWhitespace.addListener(this);
        linger.setRepeats(false);
        // Its first read takes the diffs its changelists want.
        knowLists();
    }

    /** The status of these repositories open, or null. */
    static RepositoryStatus find(List<VcsRepository> repositories) {
        for (RepositoryStatus s : open)
            if (s.repositories.equals(repositories))
                return s;
        return null;
    }

    /** The status of these repositories, the one open if there is one, else one read. */
    static RepositoryStatus of(List<VcsRepository> repositories) {
        final RepositoryStatus found = find(repositories);
        if (found != null)
            return found;
        final RepositoryStatus made = new RepositoryStatus(repositories);
        open.add(made);
        made.refresh();
        return made;
    }

    public List<VcsRepository> getRepositories() {
        return repositories;
    }

    // Its diffs read again, ignoring the whitespace diffs ignore now.
    @Override
    public void diffWhitespaceChanged() {
        refresh();
    }

    // How long one let go stays open, read, as the Changes tab follows
    // buffers between repositories and back.
    private static final int LINGER_MILLIS = 30_000;
    private final Timer linger = new Timer(LINGER_MILLIS, e -> closeIfUnused());

    /** holder uses it, showing it to l, if not null, from now. */
    void hold(Object holder, Listener l) {
        linger.stop();
        holders.add(holder);
        if (l != null && !listeners.contains(l))
            listeners.add(l);
    }

    /**
     * holder, and l, are done with it: closed in a while if nothing else
     * holds it then, nor is picked.
     */
    void release(Object holder, Listener l) {
        holders.remove(holder);
        if (l != null)
            listeners.remove(l);
        if (holders.isEmpty())
            linger.restart();
    }

    // Closed, with nothing holding it, picked, or waiting to be or on a read.
    private void closeIfUnused() {
        if (disposed || !holders.isEmpty() || !picks.isEmpty() || !pendingPicks.isEmpty() || !pendingReads.isEmpty())
            return;
        disposed = true;
        linger.stop();
        open.remove(this);
        DiffWhitespace.removeListener(this);
        for (VcsRepository r : repositories)
            r.removeListener(changed);
        // Its diffs gone from its files' signs.
        SignsUpdater.removeSource(this);
        signsChanged(u -> true);
    }

    /** Every one open closed, picks and all, as between tests. */
    static void closeAll() {
        for (RepositoryStatus s : List.copyOf(open)) {
            s.holders.clear();
            s.pendingPicks.clear();
            s.pendingReads.clear();
            s.picks.clear();
            s.closeIfUnused();
        }
    }

    /** Whether it has been closed. */
    boolean isDisposed() {
        return disposed;
    }

    // Reading.

    /** Reads its repositories again in the background, then shows them. */
    void refresh() {
        synchronized (this) {
            if (disposed)
                return;
            if (reading) {
                readAgain = true;
                return;
            }
            reading = true;
        }
        Background.start("vcs status", this::readLoop);
    }

    // Whether a read takes the diff of the file keyed key. Those listed
    // and viewed are in the working section, which a section no read has
    // shown may be.
    private boolean wants(FileKey key) {
        if (partlyPicked.contains(key) || wanted.contains(key))
            return true;
        final Map.Entry<VcsRepository, String> at = Map.entry(key.repository(), key.path());
        if ((listedPaths.contains(at) || viewed.contains(at)) && maybeWorking(key))
            return true;
        for (Listener l : listeners)
            if (l.wantsDiff(key))
                return true;
        return false;
    }

    private void readLoop() {
        try {
            while (true) {
                final long startedAt = clock.incrementAndGet();
                final long wallAt = System.currentTimeMillis();
                final Whitespace ignored = DiffWhitespace.ignored();
                final Map<VcsRepository, StatusData> read = new LinkedHashMap<>();
                final Set<FileKey> diffs = ConcurrentHashMap.newKeySet();
                for (VcsRepository r : repositories) {
                    StatusData d;
                    try {
                        d = r.backend().readStatus(r, (section, path) -> {
                            final FileKey key = new FileKey(r, section, path);
                            if (!wants(key))
                                return false;
                            diffs.add(key);
                            return true;
                        }, ignored);
                    }
                    catch (RuntimeException e) {
                        Log.error(e);
                        d = StatusData.failed(e.toString());
                    }
                    if (d != null)
                        learnRoles(r, d);
                    // Changelists take the work tree's changes whitespace and all.
                    if (d != null && d.error() == null && ignored != Whitespace.NONE && listing.contains(r))
                        d = withFullWorking(r, d, diffs);
                    read.put(r, d != null ? d : StatusData.failed(r.backend().name() + " has no status"));
                }
                SwingUtilities.invokeLater(() -> show(read, diffs, startedAt, wallAt, ignored));
                synchronized (this) {
                    if (!readAgain || disposed) {
                        reading = false;
                        return;
                    }
                    readAgain = false;
                }
            }
        }
        catch (Throwable e) {
            synchronized (this) {
                reading = false;
                readAgain = false;
            }
            throw e;
        }
    }

    // The roles of d's sections, learned.
    private void learnRoles(VcsRepository r, StatusData d) {
        final Map<String, Role> known = roles.computeIfAbsent(r, k -> new ConcurrentHashMap<>());
        for (Section s : d.sections())
            known.put(s.id(), s.role());
        final Section working = d.section(Role.WORKING);
        if (working != null)
            workingIds.put(r, working.id());
    }

    // Whether key's section is WORKING, or of a role no read has shown.
    private boolean maybeWorking(FileKey key) {
        final Role role = role(key.repository(), key.section());
        return role == null || role == Role.WORKING;
    }

    /** The role of r's section id, as a read has shown it; null if none has. */
    Role role(VcsRepository r, String section) {
        final Map<String, Role> known = roles.get(r);
        return known == null ? null : known.get(section);
    }

    /** The id of r's first WORKING section, which j's changelists sort; null if no read has shown one. */
    String workingSection(VcsRepository r) {
        final StatusData d = data.get(r);
        final Section s = d == null ? null : d.section(Role.WORKING);
        return s != null ? s.id() : workingIds.get(r);
    }

    /**
     * The key of r's file at path among its working changes: in the
     * WORKING section that has it, else the first; null if no read has
     * shown one.
     */
    FileKey workingKey(VcsRepository r, String path) {
        final StatusData d = data.get(r);
        if (d != null)
            for (Section s : d.sections())
                if (s.role() == Role.WORKING)
                    for (Item i : s.items())
                        if (i instanceof FileItem f && f.path().equals(path))
                            return new FileKey(r, s.id(), path);
        final String id = workingSection(r);
        return id == null ? null : new FileKey(r, id, path);
    }

    // d with its WORKING sections read again with whitespace shown, the
    // diffs it takes added to diffs; d as it is if that read fails.
    private StatusData withFullWorking(VcsRepository r, StatusData d, Set<FileKey> diffs) {
        final StatusData full;
        try {
            full = r.backend().readStatus(r, (section, path) -> {
                final FileKey key = new FileKey(r, section, path);
                if (role(r, section) != Role.WORKING || !wants(key))
                    return false;
                diffs.add(key);
                return true;
            }, Whitespace.NONE);
        }
        catch (RuntimeException e) {
            Log.error(e);
            return d;
        }
        if (full == null || full.error() != null)
            return d;
        final Map<String, Section> working = new HashMap<>();
        for (Section x : full.sections())
            if (x.role() == Role.WORKING)
                working.put(x.id(), x);
        final List<Section> sections = new ArrayList<>();
        for (Section x : d.sections())
            sections.add(x.role() == Role.WORKING ? working.getOrDefault(x.id(), x) : x);
        return new StatusData(d.header(), d.inProgress(), sections, d.error());
    }

    private void show(
            Map<VcsRepository, StatusData> read,
            Set<FileKey> diffs,
            long startedAt,
            long wallAt,
            Whitespace ignored) {
        if (disposed)
            return;
        currentDiffReads = Set.copyOf(diffs);
        final long before = shownReadAt;
        shownReadAt = wallAt;
        // Its files' signs, which its diffs place, and what of them is
        // picked, as read now: all, or with nothing new read, those saved
        // since the read before, whose picks were hidden till this one.
        if (showRead(read, diffs, startedAt, ignored))
            signsChanged(u -> true);
        else
            signsChanged(u -> u.getBuffer().getFileChangedAt() >= before);
        // The picks from files waiting on this read.
        final List<PendingPick> waiting = List.copyOf(pendingPicks);
        pendingPicks.clear();
        for (PendingPick p : waiting)
            pickUnstagedAt(p, diffs);
        // What waits on diffs this read took, or a second read whatever it took.
        final List<PendingRead> reads = List.copyOf(pendingReads);
        pendingReads.clear();
        for (PendingRead r : reads)
            if (diffs.containsAll(r.keys()) || r.tried()) {
                wanted.removeAll(r.keys());
                r.then().run();
            } else {
                pendingReads.add(new PendingRead(r.keys(), r.then(), true));
                refresh();
            }
        closeIfUnused();
    }

    // Whether it found anything new, and showed it.
    private boolean showRead(
            Map<VcsRepository, StatusData> read,
            Set<FileKey> diffs,
            long startedAt,
            Whitespace ignored) {
        final boolean newWhitespace = ignored != readIgnoring;
        readIgnoring = ignored;
        for (Listener l : listeners)
            l.refreshed();
        final boolean wrote = startedAt > wroteAt && writing;
        if (wrote) {
            writing = false;
            updateToolbars();
        }
        // A refresh comes with each save and each return to j's window: most
        // find nothing new, and leave what shows it as it is; but a pick
        // waiting on a diff that did not come is dropped.
        if (!data.isEmpty()
                && diffsRead.containsAll(diffs)
                && !picks.unread(this)
                && read.entrySet().stream().allMatch(e -> e.getValue().equals(data.get(e.getKey()))))
            return false;
        data.putAll(read);
        diffsRead.addAll(diffs);
        // j's own write takes the changes picks were of whichever way they
        // were chosen: a pick it drops is not news.
        shown(!wrote, newWhitespace);
        return true;
    }

    // What was read shown again: its picks less what is gone, its listeners told, a pick
    // dropped said, why, unless null.
    private void shown(boolean reportDrops, boolean whitespaceChanged) {
        picks.newRead();
        knowLists();
        final int dropped = picks.retain(this);
        final String message = dropped > 0 && reportDrops ? dropMessage(dropped, whitespaceChanged) : null;
        for (Listener l : listeners)
            l.read(message);
        // Its listeners have shown the picks with what was read.
        showPicks(false);
        if (message != null) {
            for (Editor editor : Editor.getEditorList()) {
                final Buffer buffer = editor.getBuffer();
                // Status windows report during rendering. An editor being
                // initialized or closed may not have a buffer yet.
                if (buffer == null || buffer instanceof VcsStatusBuffer)
                    continue;
                final var signs = buffer.getSignsUpdater();
                if (buffer instanceof StatusDiffBuffer diff && diff.getOwner().model() == this
                        || signs != null && repositories.contains(signs.getRepository()))
                    editor.status(message);
            }
        }
    }

    private String dropMessage(int count, boolean whitespaceChanged) {
        final String reason =
                whitespaceChanged ? "whitespace shown or ignored changed " + (count == 1 ? "its hunk" : "their hunks")
                        : !picks.droppedProblems().isEmpty() ? picks.droppedProblems().get(0).message()
                        : count == 1 ? "its change is gone or changed"
                        : "their changes are gone or changed";
        return (count == 1 ? "A pick was dropped: " : count + " picks were dropped: ") + reason;
    }

    /** What was last read of a repository, or null. */
    StatusData getData(VcsRepository repository) {
        return data.get(repository);
    }

    /** Whether the diff of the file keyed key has been read, so that none means there is none. */
    boolean diffRead(FileKey key) {
        return diffsRead.contains(key);
    }

    /** The diff of the file keyed key read again, as one opened. */
    void readDiff(FileKey key) {
        diffsRead.remove(key);
        refresh();
    }

    /** The files of a repository's sections with role, as last read. */
    List<FileItem> files(VcsRepository repository, Role role) {
        final List<FileItem> files = new ArrayList<>();
        final StatusData d = data.get(repository);
        if (d != null)
            for (Section s : d.sections())
                if (s.role() == role)
                    for (Item item : s.items())
                        if (item instanceof FileItem f)
                            files.add(f);
        return files;
    }

    /** The files of a section of a repository, as last read. */
    List<FileItem> files(VcsRepository repository, String section) {
        final List<FileItem> files = new ArrayList<>();
        final StatusData d = data.get(repository);
        if (d != null)
            for (Section s : d.sections())
                if (s.id().equals(section))
                    for (Item item : s.items())
                        if (item instanceof FileItem f)
                            files.add(f);
        return files;
    }

    // Reading what something waits on.

    private record PendingRead(Set<FileKey> keys, Runnable then, boolean tried) {}

    private final List<PendingRead> pendingReads = new ArrayList<>();

    /**
     * Reads the diffs of the files whose lines are keyed keys, then runs
     * then, after the read that has them, or the second after, whatever
     * it took.
     */
    void readDiffsThen(Set<FileKey> keys, Runnable then) {
        wanted.addAll(keys);
        pendingReads.add(new PendingRead(Set.copyOf(keys), then, false));
        refresh();
    }

    /** Resolves immediately against the current read, without scheduling work. */
    Selections.Resolution resolveTargets(List<Target> captured) {
        return resolveTargetsRequiring(captured, key -> false);
    }

    Selections.Resolution resolveTargetsRequiring(List<Target> captured, Predicate<FileKey> requireDiff) {
        return Selections
                .resolve(captured, disposed ? Map.of() : data, currentDiffReads::contains, picks::idsOf, requireDiff);
    }

    /**
     * Resolves all targets, using the existing bounded diff-read mechanism
     * once if necessary. A read that still supplies no diff is a failure,
     * rather than another request that could loop forever.
     * Returns whether the callback is waiting on an asynchronous read.
     */
    boolean resolveTargets(List<Target> captured, Consumer<Selections.Resolution> done) {
        return resolveTargets(captured, key -> false, done);
    }

    boolean resolveTargets(
            List<Target> captured,
            Predicate<FileKey> requireDiff,
            Consumer<Selections.Resolution> done) {
        final List<Target> selected = List.copyOf(captured);
        final Selections.Resolution resolved = resolveTargetsRequiring(selected, requireDiff);
        if (!resolved.problems().isEmpty() || resolved.unread().isEmpty()) {
            done.accept(resolved);
            return false;
        }
        readDiffsThen(
            resolved.unread(),
            () -> done.accept(resolveTargetsRequiring(selected, requireDiff).readsFailed()));
        return true;
    }

    // The whitespace its diffs were last read ignoring.
    private Whitespace readIgnoring = Whitespace.NONE;

    /** The whitespace the diffs it has were read ignoring. */
    Whitespace readIgnoring() {
        return readIgnoring;
    }

    // Writing.

    // Each frame's toolbar, as what its buttons can do changes.
    static void updateToolbars() {
        for (int i = 0; i < Editor.getFrameCount(); i++)
            Editor.getFrame(i).updateToolbar();
    }

    /** Whether a write is running, or its refresh not shown yet. */
    boolean isWriting() {
        return writing;
    }

    /**
     * A write starts. Until it is done no read can count as after it, even
     * one already running, whose data may be from before it.
     */
    void startWriting() {
        wroteBefore = wroteAt;
        wroteAt = Long.MAX_VALUE;
        writing = true;
        ++writes;
    }

    // wroteAt before the write started, for one given up.
    private long wroteBefore;

    /** A write started is not made after all: as before it, with no read to wait for. */
    void gaveUpWriting() {
        wroteAt = wroteBefore;
        writing = false;
        --writes;
        updateToolbars();
    }

    // How many writes have started: a diff shown is stale until it has
    // been shown from a read after the last.
    private int writes;

    int writes() {
        return writes;
    }

    /**
     * A write is done: writing until a read that starts from now is shown.
     * The repositories' refresh, which tells it, starts it.
     */
    void wrote() {
        wroteAt = clock.incrementAndGet();
    }

    // Changelists.

    // The working files whose changes are kept in changelists one by one,
    // by repository and path, whose diffs a read takes to sort them.
    private volatile Set<Map.Entry<VcsRepository, String>> listedPaths = Set.of();
    // The repositories whose changelists it shows; read with their work
    // trees' changes whitespace and all.
    private volatile Set<VcsRepository> listing = Set.of();
    // The last sorting of each repository's files: of which lists, and of
    // which read.
    private final Map<VcsRepository, Listed> lastListed = new HashMap<>();

    private record Listed(Changelists lists, StatusData data, ListedChanges sorted) {}

    @Override
    public void listsChanged(VcsRepository r) {
        if (repositories.contains(r))
            shown(false, false);
    }

    // The status the file's buffer's status command shows: the one its picks go to.
    @Override
    public boolean prefers(SignsUpdater u) {
        return !disposed && repositories.equals(VcsStatusMode.repositoriesFor(u.getBuffer()));
    }

    // A file renamed in the index or the work tree: its old path, as read.
    @Override
    public String renamedFrom(VcsRepository r, String path) {
        final StatusData d = repositories.contains(r) ? data.get(r) : null;
        if (d == null)
            return null;
        for (Section s : d.sections())
            for (Item i : s.items())
                if (i instanceof FileItem f && f.path().equals(path) && f.change().origPath() != null)
                    return f.change().origPath();
        return null;
    }

    /**
     * r's unstaged files, as last read, in its changelists; null if it
     * doesn't sort them. Those kept one change at a time whose diffs
     * aren't read, read.
     */
    ListedChanges listed(VcsRepository r) {
        final StatusData d = data.get(r);
        final RepositoryLists lists = d == null ? null : RepositoryLists.shown(r);
        if (lists == null)
            return null;
        Changelists c = lists.lists();
        final Listed last = lastListed.get(r);
        if (last != null && last.lists() == c && last.data() == d)
            return last.sorted();
        // What no change has any more once a commit moved HEAD, as a file
        // committed while closed, kept from taking an unrelated edit later.
        // Only then: a stash, or a change undone and done again elsewhere,
        // leaves HEAD, and the lists, as they were.
        final String head = headOf(d);
        final String seenHead = heads.put(r, head);
        if (d.error() == null
                && seenHead != null
                && !seenHead.equals(head)
                && !pruned(c, d, readIgnoring).equals(c)
                && lists.update(x -> pruned(x, d, readIgnoring))) {
            c = lists.lists();
            SignsUpdater.listsChanged(r);
        }
        final String working = workingSection(r);
        final List<FileItem> files = working == null ? List.of() : files(r, working);
        final Set<Map.Entry<VcsRepository, String>> paths = pathsOf(r, c);
        for (FileItem f : files) {
            final FileKey key = new FileKey(r, working, f.path());
            if (paths.contains(Map.entry(r, f.path())) && f.diff() == null && !diffsRead.contains(key)) {
                SwingUtilities.invokeLater(this::refresh);
                break;
            }
        }
        final ListedChanges sorted = ListedChanges.of(c, working, files, picks::ids);
        lastListed.put(r, new Listed(c, d, sorted));
        return sorted;
    }

    /**
     * c less what d says no change has any more: a path in neither the
     * work tree's changes nor what is staged, both sections whole; of a
     * path whose diffs are read, the changes neither diff has, a change
     * staged kept so unstaging puts it back in its list. Staged diffs read
     * ignoring whitespace aren't taken as the changes'.
     */
    // The Head a read last showed of each repository, its commit with it.
    private final Map<VcsRepository, String> heads = new HashMap<>();

    private static String headOf(StatusData d) {
        for (StatusData.Field f : d.header())
            if (f.label().equals("Head"))
                return f.value();
        return "";
    }

    static Changelists pruned(Changelists c, StatusData d, Whitespace ignored) {
        final Section unstaged = d.section(Role.WORKING);
        final Section staged = d.section(Role.STAGED);
        final boolean whole = (unstaged == null || unstaged.total() == unstaged.items().size())
                && (staged == null || staged.total() == staged.items().size());
        Changelists out = c;
        for (Map.Entry<String, Changelists.Assignment> e : c.files().entrySet()) {
            final String path = e.getKey();
            final FileItem u = fileAt(unstaged, path);
            final FileItem st = fileAt(staged, path);
            if (u == null && st == null) {
                if (whole)
                    out = out.withFile(path, null);
                continue;
            }
            if (e.getValue().whole() != null
                    || u != null && u.diff() == null
                    || st != null && (st.diff() == null || ignored != Whitespace.NONE))
                continue;
            final Set<String> now = new HashSet<>();
            for (FileItem f : Arrays.asList(u, st))
                if (f != null)
                    for (Hunk h : f.diff().hunks())
                        for (Hunk.Run run : h.runs())
                            now.add(Anchors.hash(h, run));
            final List<Changelists.Change> kept = new ArrayList<>();
            for (Changelists.Change ch : e.getValue().changes())
                if (now.contains(ch.hash()))
                    kept.add(ch);
            if (kept.size() != e.getValue().changes().size())
                out = out.withFile(path, kept.isEmpty() ? null : Changelists.Assignment.of(kept));
        }
        return out;
    }

    // s's file at path, or renamed from it; null for none.
    private static FileItem fileAt(Section s, String path) {
        if (s != null)
            for (Item i : s.items())
                if (i instanceof FileItem f && (f.path().equals(path) || path.equals(f.change().origPath())))
                    return f;
        return null;
    }

    // r's files c keeps change by change, whose diffs a read takes.
    private static Set<Map.Entry<VcsRepository, String>> pathsOf(VcsRepository r, Changelists c) {
        final Set<Map.Entry<VcsRepository, String>> out = new HashSet<>();
        for (Map.Entry<String, Changelists.Assignment> e : c.files().entrySet())
            if (e.getValue().whole() == null)
                out.add(Map.entry(r, e.getKey()));
        return out;
    }

    // Which repositories' lists it shows, and the files whose diffs they
    // want read, as they are now: read again if a repository's lists now
    // show while whitespace is ignored, as they take the full diff.
    private void knowLists() {
        final Set<Map.Entry<VcsRepository, String>> paths = new HashSet<>();
        final Set<VcsRepository> shown = new HashSet<>();
        for (VcsRepository r : repositories) {
            final RepositoryLists lists = RepositoryLists.shown(r);
            if (lists != null) {
                shown.add(r);
                paths.addAll(pathsOf(r, lists.lists()));
            }
        }
        listedPaths = Set.copyOf(paths);
        final boolean more = !listing.containsAll(shown);
        listing = Set.copyOf(shown);
        if (more && readIgnoring != Whitespace.NONE)
            refresh();
    }

    // Picking.

    private final StatusPicks picks = new StatusPicks();
    // Whether picks were last shown, to show once when the last goes.
    private boolean picksShown;
    // The files partly picked, for a refresh to read their diffs, open or not.
    private volatile Set<FileKey> partlyPicked = Set.of();

    /** What is picked. */
    StatusPicks picks() {
        return picks;
    }

    /** Picks each of ls that can be, or, if each is picked, unpicks them; and shows it. */
    void pick(List<StatusLine> ls) {
        picks.toggle(StatusLine.capture(this, ls.stream().filter(StatusPicks::pickable).toList()));
        showPicks();
    }

    /** Picks what ls show if on, else unpicks it, and shows it. */
    void pick(List<StatusLine> ls, boolean on) {
        picks.set(StatusLine.capture(this, ls.stream().filter(StatusPicks::pickable).toList()), on);
        showPicks();
    }

    void pickTargets(List<Target> targets) {
        picks.toggle(targets);
        showPicks();
    }

    /** Unpicks everything, and shows it. */
    boolean unpickAll() {
        if (picks.isEmpty())
            return false;
        picks.clear();
        showPicks();
        return true;
    }

    /** Unpicks those of keys still picked, and shows it. */
    void unpick(Set<StatusPicks.Key> keys) {
        if (keys.isEmpty())
            return;
        picks.remove(keys);
        showPicks();
    }

    /**
     * What is picked shown: by its listeners, and its files' signs; the
     * diffs of files newly partly picked read. Closed if nothing holds it
     * and the last pick is gone.
     */
    void showPicks() {
        showPicks(true);
    }

    // As showPicks, its listeners told if tell.
    private void showPicks(boolean tell) {
        // Nothing picked now nor before: nothing to show.
        if (picks.isEmpty() && !picksShown)
            return;
        picksShown = !picks.isEmpty();
        // A file newly partly picked whose diff is unread, as one closed
        // in the status picked beside: read again, its diff with it.
        final Set<FileKey> was = partlyPicked;
        partlyPicked = Set.copyOf(picks.partlyPicked());
        if (!was.containsAll(partlyPicked) && picks.unread(this))
            refresh();
        if (tell)
            for (Listener l : listeners)
                l.picked();
        // The signs of the files picked now or before, in their buffers.
        final List<FileKey> now = picks.paths();
        final List<FileKey> touched = new ArrayList<>(signsPicked);
        touched.addAll(now);
        signsPicked = now;
        signsChanged(
            u -> touched.stream().anyMatch(f -> f.repository() == u.getRepository() && f.path().equals(u.getPath())));
        closeIfUnused();
    }

    // The files picked as their signs last showed.
    private List<FileKey> signsPicked = List.of();

    // Files' change marks.

    // The signs of those of its files in their buffers which shows again.
    private void signsChanged(Predicate<SignsUpdater> which) {
        // The views of buffers closed, gone.
        final org.armedbear.j.BufferList bufferList = Editor.getBufferList();
        for (var it = views.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<Buffer, FileView> e = it.next();
            if (!bufferList.contains(e.getKey())) {
                viewed.remove(e.getValue().at);
                it.remove();
            }
        }
        for (Buffer b : bufferList) {
            final SignsUpdater u = b.getSignsUpdater();
            if (u != null && repositories.contains(u.getRepository()) && which.test(u))
                u.statusChanged();
        }
    }

    // When the read shown began, by j's clock.
    private long shownReadAt;

    /**
     * Whether buffer's file was saved, or reloaded, since the read shown
     * began: its diff, as read, may not be the file's now.
     */
    boolean savedSinceRead(Buffer buffer) {
        final long at = buffer == null ? 0 : buffer.getFileChangedAt();
        return at != 0 && at >= shownReadAt;
    }

    /**
     * Of r's file at path, the rows, from 0, as the working tree has them,
     * of its unstaged changes picked; null for none.
     */
    StatusView.Picked pickedRows(VcsRepository r, String path) {
        final FileKey key = workingKey(r, path);
        final FileItem item = key == null ? null : picks.item(this, key);
        return item == null ? null : picks.rows(key, item);
    }

    // Its views of its files' buffers, those closed dropped as it tells
    // the signs; and the keys of their files, whose diffs each read
    // takes, so that their signs are by the backend's diff.
    private final Map<Buffer, FileView> views = new HashMap<>();
    private final Set<Map.Entry<VcsRepository, String>> viewed = ConcurrentHashMap.newKeySet();

    @Override
    public StatusView viewOf(SignsUpdater u) {
        if (disposed || !repositories.contains(u.getRepository()))
            return null;
        final FileView v = views.get(u.getBuffer());
        if (v != null && v.repository == u.getRepository() && v.path.equals(u.getPath()))
            return v;
        if (v != null)
            viewed.remove(v.at);
        final FileView made = new FileView(u.getBuffer(), u.getRepository(), u.getPath());
        views.put(u.getBuffer(), made);
        // Its diff read, if it has the file and hasn't.
        if (viewed.add(made.at)) {
            final FileItem item = unstaged(made.repository, made.path);
            if (item != null && item.diff() == null)
                refresh();
        }
        return made;
    }

    /**
     * Its view of r's file at path, shown in buffer: the backend's diff of
     * its working changes, as read, its picks, and its boxes. The diff and
     * the picks none while the buffer is edited, or saved since the read.
     */
    private final class FileView implements StatusView {
        final Buffer buffer;
        final VcsRepository repository;
        final String path;
        final Map.Entry<VcsRepository, String> at;
        final RowPicks rows;
        private EditorPickColumn column;

        FileView(Buffer buffer, VcsRepository r, String path) {
            this.buffer = buffer;
            repository = r;
            this.path = path;
            at = Map.entry(r, path);
            rows = new RowPicks(RepositoryStatus.this, this::key, () -> {
                final FileItem item = current() ? unstaged(repository, path) : null;
                return item == null || item.diff() == null ? null : item;
            }, () -> "No change to pick here");
        }

        // Its file's key in the section that has its working changes; null for none.
        FileKey key() {
            return workingKey(repository, path);
        }

        // Whether its diff, as read, may be the buffer's.
        private boolean current() {
            return !disposed && !buffer.isModified() && !savedSinceRead(buffer);
        }

        @Override
        public List<Diff.Edit> edits() {
            final FileItem item = current() ? unstaged(repository, path) : null;
            if (item == null || item.diff() == null)
                return null;
            final List<Diff.Edit> out = new ArrayList<>();
            for (Hunk h : item.diff().hunks())
                out.addAll(h.edits());
            return out;
        }

        @Override
        public StatusView.Picked picked() {
            return current() ? pickedRows(repository, path) : null;
        }

        @Override
        public boolean hasPicks() {
            final FileKey key = key();
            return !disposed && key != null && picks.has(key);
        }

        @Override
        public org.armedbear.j.GutterColumn column() {
            if (column == null)
                column = new EditorPickColumn(buffer, rows);
            return column;
        }

        @Override
        public PickState stateAt(int row) {
            return rows.changeState(row);
        }

        @Override
        public void pick(Editor editor, int from, int to, boolean lines, Runnable then) {
            pickUnstagedAt(new PendingPick(editor, this, editor.getDotLineNumber(), from, to, lines, then), null);
        }
    }

    /** r's file at path as its WORKING section has it, or null. */
    FileItem unstaged(VcsRepository r, String path) {
        final FileKey key = workingKey(r, path);
        return key == null ? null : picks.item(this, key);
    }

    // A pick from a file's buffer, view's, the caret at row caret: of the
    // change at row from, or of the changed lines in rows from to to if
    // lines; waiting on a read or the file's diff, said in editor, then
    // then run if editor still shows the file there.
    private record PendingPick(Editor editor, FileView view, int caret, int from, int to, boolean lines,
            Runnable then) {}

    private final List<PendingPick> pendingPicks = new ArrayList<>();
    // The keys of files whose diffs a read takes for something waiting on them.
    private final Set<FileKey> wanted = ConcurrentHashMap.newKeySet();

    /**
     * Picks the unstaged changes of the file editor shows, as it is saved,
     * at rows from to to, as its view picks them; its diff read first if
     * it hasn't been, and the status itself.
     */
    void pickUnstagedAt(Editor editor, int from, int to, boolean lines, Runnable then) {
        final SignsUpdater u = editor.getBuffer().getSignsUpdater();
        final StatusView v = u == null ? null : viewOf(u);
        if (v != null)
            v.pick(editor, from, to, lines, then);
    }

    // p picked, or left waiting on a read, read the read just shown, whose
    // diffs it took; null for none yet. A file saved since the read waits
    // for the next, its diff then the file's.
    private void pickUnstagedAt(PendingPick p, Set<FileKey> read) {
        final FileView v = p.view();
        if (data.get(v.repository) == null || savedSinceRead(v.buffer)) {
            pendingPicks.add(p);
            refresh();
            return;
        }
        final FileKey fk = v.key();
        final FileItem item = fk == null ? null : picks.item(this, fk);
        if (item != null && item.diff() == null) {
            if (read == null || !read.contains(fk)) {
                wanted.add(fk);
                pendingPicks.add(p);
                refresh();
                return;
            }
            wanted.remove(fk);
            done(p, "Couldn't read " + v.path + "'s diff");
            return;
        }
        if (fk != null)
            wanted.remove(fk);
        done(p, item == null ? "No unstaged change here to pick" : pick(p, item));
    }

    // p done, said; its then run if its editor is still where it was.
    private void done(PendingPick p, String said) {
        if (p.editor().getBuffer() != p.view().buffer || p.editor().getDotLineNumber() != p.caret())
            return;
        p.editor().status(said);
        p.then().run();
    }

    // p's change or lines of item, fk its line's key, picked or unpicked: what is said.
    private String pick(PendingPick p, FileItem item) {
        final FileView v = p.view();
        final Map<String, BitSet> idx = p.lines() ? v.rows.signs(p.from(), p.to()) : v.rows.changeAt(p.from());
        if (idx == null)
            return "No unstaged change here to pick";
        picks.toggle(v.rows.targets(idx));
        showPicks();
        final String what = p.lines() ? " lines of " : " a change of ";
        return (picks.linesState(v.key(), idx) == PickState.NONE ? "Unpicked" : "Picked") + what + v.path;
    }
}
