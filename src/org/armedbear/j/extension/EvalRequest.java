/*
 * EvalRequest.java
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
 * What to evaluate, and where.
 *
 * <p>Modelled on Conjure's options table rather than a bare string, because a
 * string cannot say which package or namespace the form belongs in. Immutable;
 * the setters return a copy.
 */
public final class EvalRequest
{
    private final String code;
    private final String context;
    private final File file;
    private final String origin;
    private final boolean captureOutput;

    private EvalRequest(String code, String context, File file, String origin,
                        boolean captureOutput)
    {
        if (code == null)
            throw new IllegalArgumentException("code is required");
        this.code = code;
        this.context = context;
        this.file = file;
        this.origin = origin;
        this.captureOutput = captureOutput;
    }

    public static EvalRequest of(String code)
    {
        return new EvalRequest(code, null, null, null, false);
    }

    /** The package or namespace to evaluate in; null means the session's own. */
    public EvalRequest context(String context)
    {
        return new EvalRequest(code, context, file, origin, captureOutput);
    }

    /** The file the code came from, if any. */
    public EvalRequest file(File file)
    {
        return new EvalRequest(code, context, file, origin, captureOutput);
    }

    /** Where the request came from ("command-line", "keymap", "hook", ...); for logging. */
    public EvalRequest origin(String origin)
    {
        return new EvalRequest(code, context, file, origin, captureOutput);
    }

    /** Collect what the form writes to standard output as well as its value. */
    public EvalRequest captureOutput(boolean captureOutput)
    {
        return new EvalRequest(code, context, file, origin, captureOutput);
    }

    public String getCode()          { return code; }
    public String getContext()       { return context; }
    public File getFile()            { return file; }
    public String getOrigin()        { return origin; }
    public boolean isCaptureOutput() { return captureOutput; }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("EvalRequest[");
        sb.append(code.length() > 60 ? code.substring(0, 60).concat("...") : code);
        if (context != null)
            sb.append(" in ").append(context);
        if (origin != null)
            sb.append(" from ").append(origin);
        return sb.append(']').toString();
    }
}
