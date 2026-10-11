/*
 * RepositoryWatcher.java
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

package org.armedbear.j.vcs;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.armedbear.j.Log;
import org.armedbear.j.util.Background;

/**
 * Watches repositories' metadata (git's index, HEAD and refs) for changes
 * made outside j. A change marks the repository stale; one with listeners is
 * read again 150 ms after the last change. The working tree is not watched.
 */
public final class RepositoryWatcher {
    /** A directory to watch, and whether its subdirectories are watched too. */
    public record Watch(Path directory, boolean tree) {}

    static final long DEBOUNCE_MILLIS = 150;

    private record Registered(VcsRepository repository, boolean tree) {}

    private static WatchService service;
    private static final Map<WatchKey, Registered> keys = new HashMap<>();
    private static final Map<VcsRepository, ScheduledFuture<?>> pending = new HashMap<>();
    private static final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> Background.newThread("vcs watch timer", r));

    private RepositoryWatcher() {}

    static void watch(VcsRepository repository) {
        for (Watch w : repository.backend().watches(repository))
            register(w.directory(), repository, w.tree());
    }

    private static synchronized void register(Path dir, VcsRepository repository, boolean tree) {
        if (!Files.isDirectory(dir))
            return;
        try {
            if (service == null) {
                service = FileSystems.getDefault().newWatchService();
                Background.start("vcs watcher", RepositoryWatcher::loop);
            }
            keys.put(dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), new Registered(repository, tree));
            if (tree) {
                try (Stream<Path> subdirs = Files.list(dir)) {
                    subdirs.filter(Files::isDirectory).forEach(d -> register(d, repository, true));
                }
            }
        }
        catch (IOException e) {
            Log.debug(e);
        }
    }

    private static void loop() {
        while (true) {
            WatchKey key;
            try {
                key = service.take();
            }
            catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            Registered reg;
            synchronized (RepositoryWatcher.class) {
                reg = keys.get(key);
            }
            boolean changed = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == OVERFLOW) {
                    changed = true;
                    continue;
                }
                Path name = (Path) event.context();
                // A lock comes and goes around every write; the write itself is what counts.
                if (name.toString().endsWith(".lock"))
                    continue;
                changed = true;
                if (reg != null && reg.tree() && event.kind() == ENTRY_CREATE) {
                    Path child = ((Path) key.watchable()).resolve(name);
                    if (Files.isDirectory(child))
                        register(child, reg.repository(), true);
                }
            }
            if (!key.reset()) {
                synchronized (RepositoryWatcher.class) {
                    keys.remove(key);
                }
            }
            if (changed && reg != null)
                changed(reg.repository());
        }
    }

    private static synchronized void changed(VcsRepository repository) {
        repository.invalidate();
        ScheduledFuture<?> previous = pending.get(repository);
        if (previous != null)
            previous.cancel(false);
        pending.put(repository, timer.schedule(() -> {
            synchronized (RepositoryWatcher.class) {
                pending.remove(repository);
            }
            // Unwatched, it stays stale until someone asks for it.
            if (repository.hasListeners())
                repository.refresh();
        }, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS));
    }
}
