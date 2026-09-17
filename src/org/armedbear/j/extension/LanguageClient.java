/*
 * LanguageClient.java
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

package org.armedbear.j.extension;

import org.armedbear.j.File;

/**
 * Talks to one language runtime on the editor's behalf.
 *
 * <p>Named after Conjure's clients rather than javax.script's ScriptEngine,
 * both to avoid the collision and because the job is closer to Conjure's: a
 * client owns one or more {@link Session}s, any of which may be an in-process
 * interpreter, a subprocess, or something on the other end of a socket.
 *
 * <p>Registered with {@link ExtensionContext#registerLanguageClient}. Core
 * reaches it through {@link Extensions#languageClient()}, which is never null
 * &mdash; it is {@link #NONE} until an extension registers one.
 */
public interface LanguageClient
{
    /** A client that is not there. Every session is unready and refuses to evaluate. */
    LanguageClient NONE = new LanguageClient()
    {
        private final Session session = new Session()
        {
            public String getKey()      { return DEFAULT_SESSION; }
            public boolean isReady()    { return false; }

            public void eval(EvalRequest request, EvalHandler handler)
            {
                if (handler != null)
                    handler.onResult(EvalResult.error(NOT_INSTALLED));
            }

            public EvalResult evalSync(EvalRequest request) throws EvalException
            {
                throw new EvalException(NOT_INSTALLED);
            }

            public void loadFile(File file) throws EvalException
            {
                throw new EvalException(NOT_INSTALLED);
            }
        };

        public String getName()              { return "none"; }
        public Session getSession(String key) { return session; }
        public Session getDefaultSession()   { return session; }
        public boolean isAvailable()         { return false; }
        public void shutdown()               {}
    };

    String DEFAULT_SESSION = "default";

    String NOT_INSTALLED =
        "No language client is installed. Evaluation needs an extension, such as abcl.";

    /** Short name, matching the extension that registered it: "abcl". */
    String getName();

    /**
     * The session for a key, creating it if this client supports more than
     * one. Clients that only ever have a single connection return the default
     * session for every key.
     */
    Session getSession(String key);

    default Session getDefaultSession()
    {
        return getSession(DEFAULT_SESSION);
    }

    /** False for {@link #NONE}; true for any real client. */
    default boolean isAvailable()
    {
        return true;
    }

    /**
     * Open a session against a runtime somewhere else, Conjure's
     * :ConjureConnect. Clients that cannot do this say so.
     */
    default Session connect(String host, int port) throws EvalException
    {
        throw new EvalException(getName().concat(" cannot connect to a remote runtime"));
    }

    /**
     * Where the runtime is installed, when that means something &mdash; ABCL
     * uses it to find swank-loader.lisp. Null when it does not apply.
     */
    default String getHomeDirectory()
    {
        return null;
    }

    /**
     * The class path needed to start this runtime in a fresh JVM, or null.
     * Lisp shell buffers use it to launch an external ABCL now that core's jar
     * no longer carries abcl.jar on its manifest.
     */
    default String getRuntimeClassPath()
    {
        return null;
    }

    /** Close every session. Called when the editor exits. */
    void shutdown();
}
