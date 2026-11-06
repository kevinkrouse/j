/*
 * RepositoryLists.java
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

package org.armedbear.j.vcs.changelists;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.armedbear.j.Editor;
import org.armedbear.j.Property;
import org.armedbear.j.vcs.VcsRepository;

/**
 * A repository's changelists as j has them: read from its store once,
 * changed through it, one for each store's file. On the event thread.
 */
public final class RepositoryLists {
    private static final Map<Path, RepositoryLists> open = new HashMap<>();
    // By repository, looked up once, as asked for at each render; empty for none.
    private static final Map<VcsRepository, Optional<RepositoryLists>> byRepository = new IdentityHashMap<>();

    private final ChangelistStore store;
    private Changelists lists;

    private RepositoryLists(ChangelistStore store) {
        this.store = store;
    }

    /** repository's lists; null if its version control keeps none of j's. */
    public static RepositoryLists of(VcsRepository repository) {
        return byRepository.computeIfAbsent(repository, r -> {
            final ChangelistStore store = ChangelistStore.of(r);
            return Optional.ofNullable(
                store == null ? null : open.computeIfAbsent(store.file(), f -> new RepositoryLists(store)));
        }).orElse(null);
    }

    /**
     * repository's lists if they sort its changes: vcsChangelists isn't
     * false, and it has one besides the default; else null.
     */
    public static RepositoryLists shown(VcsRepository repository) {
        if (!Editor.preferences().getBooleanProperty(Property.VCS_CHANGELISTS))
            return null;
        final RepositoryLists lists = of(repository);
        return lists != null && lists.lists().lists().size() > 1 ? lists : null;
    }

    /** Forgets every repository's lists, to be read again: for tests. */
    static void forget() {
        open.clear();
        byRepository.clear();
    }

    /** The lists, read if they haven't been. */
    public Changelists lists() {
        if (lists == null)
            lists = store.read();
        return lists;
    }

    /**
     * The lists changed by change, and kept; if another writer changed
     * them since, read again and changed again, once. False if they
     * couldn't be kept, said in the log, the lists then as they were.
     */
    public boolean update(UnaryOperator<Changelists> change) {
        final Changelists next = change.apply(lists());
        if (store.write(next)) {
            lists = next;
            return true;
        }
        lists = store.read();
        final Changelists again = change.apply(lists);
        if (!store.write(again))
            return false;
        lists = again;
        return true;
    }
}
