/*
 * VcsBackends.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.armedbear.j.Log;
import org.armedbear.j.vcs.git.GitBackend;
import org.armedbear.j.vcs.svn.SvnBackend;

/** The registered version control backends. */
public final class VcsBackends {
    // Detection asks them in this order at each directory: an extension's
    // first, then core's.
    private static final List<VcsBackend> backends =
        new CopyOnWriteArrayList<VcsBackend>(List.of(new SvnBackend(), new GitBackend()));
    private static int registered;

    private VcsBackends() {}

    public static synchronized void register(VcsBackend backend) {
        if (get(backend.id()) != null) {
            Log.error("version control id " + backend.id() + " is already registered");
            return;
        }
        backends.add(registered++, backend);
    }

    /** The backend with this id, or null. */
    public static VcsBackend get(int id) {
        for (VcsBackend backend : backends) {
            if (backend.id() == id)
                return backend;
        }
        return null;
    }

    public static List<VcsBackend> all() {
        return Collections.unmodifiableList(backends);
    }
}
