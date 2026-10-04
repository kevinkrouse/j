/*
 * AbclClient.java
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.armedbear.j.extension.abcl;

import java.net.URISyntaxException;
import org.armedbear.j.Log;
import org.armedbear.j.extension.LanguageClient;
import org.armedbear.j.extension.Session;
import org.armedbear.lisp.Interpreter;
import org.armedbear.lisp.Pathname;
import org.armedbear.lisp.Site;

/**
 * ABCL, embedded in j's own JVM.
 *
 * <p>One session, because one JVM can hold one ABCL.
 */
public final class AbclClient implements LanguageClient {
    private final AbclSession session = new AbclSession(DEFAULT_SESSION);

    @Override
    public String getName() {
        return "abcl";
    }

    @Override
    public Session getSession(String key) {
        return session;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    /** Where ABCL is installed; swank-loader.lisp is looked for beside it. */
    @Override
    public String getHomeDirectory() {
        try {
            Object home = Site.getLispHome();
            return home instanceof Pathname pathname
                ? pathname.getNamestring()
                : null;
        }
        catch (Throwable t) {
            Log.debug(t);
            return null;
        }
    }

    /**
     * abcl.jar, so that "M-x abcl" can start one in a fresh JVM. j.jar no
     * longer names it on its manifest Class-Path, so the path is read back off
     * the loader that actually has it.
     */
    @Override
    public String getRuntimeClassPath() {
        try {
            java.security.CodeSource source =
                Interpreter.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null)
                return null;
            return new java.io.File(source.getLocation().toURI()).getPath();
        }
        catch (URISyntaxException e) {
            Log.debug("abcl: cannot locate abcl.jar: " + e);
            return null;
        }
    }

    @Override
    public void shutdown() {
        session.close();
    }
}
