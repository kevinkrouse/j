/*
 * EvalResult.java
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

/**
 * What came back from an evaluation: the printed value, anything the form
 * wrote to standard output, or an error report.
 *
 * <p>A value class rather than an interface so that a language client does not
 * have to write an implementation to hand back a string.
 */
public final class EvalResult
{
    private final String value;
    private final String output;
    private final String error;

    private EvalResult(String value, String output, String error)
    {
        this.value = value;
        this.output = output;
        this.error = error;
    }

    public static EvalResult of(String value)
    {
        return new EvalResult(value, null, null);
    }

    public static EvalResult of(String value, String output)
    {
        return new EvalResult(value, output, null);
    }

    public static EvalResult error(String error)
    {
        return new EvalResult(null, null, error != null ? error : "error");
    }

    /** The printed representation of the value, or null if the form errored. */
    public String getValue()
    {
        return value;
    }

    /** Anything written to standard output, when the request asked for it. */
    public String getOutput()
    {
        return output;
    }

    /** The error report, or null if the form succeeded. */
    public String getError()
    {
        return error;
    }

    public boolean isError()
    {
        return error != null;
    }

    /**
     * What to show the user: the captured output when there is any, otherwise
     * the value, otherwise the error.
     */
    public String display()
    {
        if (error != null)
            return error;
        if (output != null && output.length() > 0)
            return output;
        return value != null ? value : "";
    }

    @Override
    public String toString()
    {
        return "EvalResult[".concat(display()).concat("]");
    }
}
