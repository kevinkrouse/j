/*
 * EvalRequestTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * The request and result value types.
 *
 * <p>An evaluation carries a context because a bare string cannot say which
 * package or namespace it belongs in -- the thing that makes "evaluate this
 * defun in the buffer's package" expressible.
 */
public class EvalRequestTest {
    @Test
    public void codeIsRequired() {
        try {
            EvalRequest.of(null);
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException expected) {}
    }

    @Test
    public void defaultsAreEmpty() {
        EvalRequest request = EvalRequest.of("(+ 1 2)");
        assertEquals("(+ 1 2)", request.getCode());
        assertNull(request.getContext());
        assertNull(request.getFile());
        assertNull(request.getOrigin());
        assertFalse(request.isCaptureOutput());
    }

    @Test
    public void settersReturnACopyAndLeaveTheOriginalAlone() {
        EvalRequest original = EvalRequest.of("(+ 1 2)");
        EvalRequest derived = original.context("COMMON-LISP-USER")
            .origin("command-line")
            .captureOutput(true);

        assertNull(original.getContext(), "the original must not have been mutated");
        assertFalse(original.isCaptureOutput());

        assertEquals("(+ 1 2)", derived.getCode());
        assertEquals("COMMON-LISP-USER", derived.getContext());
        assertEquals("command-line", derived.getOrigin());
        assertTrue(derived.isCaptureOutput());
    }

    @Test
    public void toStringTruncatesLongFormsAndNamesTheContext() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 40; i++)
            code.append("(f x)");
        String text = EvalRequest.of(code.toString()).context("FOO").toString();
        assertTrue(text.contains("..."), text);
        assertTrue(text.contains("in FOO"), text);
        assertTrue(text.length() < code.length());
    }

    @Test
    public void aResultCarriesEitherAValueOrAnError() {
        EvalResult value = EvalResult.of("3");
        assertFalse(value.isError());
        assertEquals("3", value.getValue());
        assertNull(value.getError());
        assertEquals("3", value.display());

        EvalResult error = EvalResult.error("The value X is unbound.");
        assertTrue(error.isError());
        assertNull(error.getValue());
        assertEquals("The value X is unbound.", error.display());
    }

    @Test
    public void capturedOutputWinsOverTheValueWhenDisplaying() {
        // A compile command wrapped in with-output-to-string wants what the
        // form printed, not the string object it returned.
        EvalResult result = EvalResult.of("\"warnings\"", "warnings");
        assertEquals("warnings", result.display());
    }

    @Test
    public void anErrorWithNoMessageStillReadsAsAnError() {
        EvalResult result = EvalResult.error(null);
        assertTrue(result.isError());
        assertEquals("error", result.display());
    }
}
