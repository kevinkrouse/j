/*
 * LispFunctionTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.armedbear.j.KeyMap;
import org.armedbear.j.extension.EvalException;
import org.armedbear.j.extension.EvalRequest;
import org.armedbear.j.extension.ScriptFunction;
import org.armedbear.lisp.Interpreter;
import org.armedbear.lisp.LispObject;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A Lisp closure bound to a key.
 *
 * <p>This is the end-to-end of the {@code instanceof LispObject} case that core
 * used to carry: init.lisp binds a lambda, {@code KeyMapping} holds it, the
 * dispatcher invokes it and describeKey prints it -- with core naming no ABCL
 * type anywhere along the way.
 */
public class LispFunctionTest
{
    @BeforeClass
    public static void startTheRuntime() throws EvalException
    {
        AbclSession.ensureInitialized();
    }

    private static LispObject lambda(String form)
    {
        return Interpreter.evaluate(form);
    }

    @Test
    public void aClosureCanBeInvokedThroughTheInterface()
    {
        // A lambda with a side effect, so we can see that it really ran.
        Interpreter.evaluate("(defparameter cl-user::*ran* 0)");
        LispFunction function = new LispFunction(
            lambda("(lambda () (setq cl-user::*ran* (1+ cl-user::*ran*)))"));

        ScriptFunction opaque = function;
        opaque.invoke();
        opaque.invoke();

        assertEquals("2", Interpreter.evaluate("cl-user::*ran*").printObject());
    }

    @Test
    public void describeIsWhatTheKeyBindingListingPrints()
    {
        String description = new LispFunction(lambda("(lambda () nil)")).describe();
        assertNotNull(description);
        assertTrue(description, description.length() > 0);
        // Help used to call LispObject.printObject() directly; same string.
        assertTrue(description, description.indexOf("FUNCTION") >= 0
                                || description.indexOf("LAMBDA") >= 0);
    }

    @Test
    public void aBrokenBindingReportsRatherThanThrows()
    {
        // Runs from the dispatcher: an error here must not take the editor
        // down, so invoke() logs and returns.
        new LispFunction(lambda("(lambda () (error \"boom\"))")).invoke();
    }

    @Test
    public void itSurvivesTheRoundTripThroughAKeyMap()
    {
        LispFunction function = new LispFunction(lambda("(lambda () nil)"));
        KeyMap keyMap = new KeyMap();
        assertTrue(keyMap.mapKey("Ctrl F12", function));
        assertSame(function, keyMap.getMappings()[0].getCommand());
    }

    @Test
    public void mappingAKeyFromLispStoresAScriptFunction()
    {
        // j:global-map-key with a function, the way init.lisp writes it. What
        // lands in the key map must be something core can name.
        Interpreter.evaluate("(j:global-map-key \"Ctrl F11\" (lambda () nil))");
        Object command = null;
        for (org.armedbear.j.KeyMapping mapping : KeyMap.getGlobalKeyMap().getMappings())
            if ("Ctrl F11".equals(mapping.getKeyText()))
                command = mapping.getCommand();
        if (command == null)
            fail("the binding was not installed");
        assertTrue(command.getClass().getName(),
                   command instanceof ScriptFunction);
    }

    @Test
    public void aFunctionIsRequired()
    {
        try {
            new LispFunction(null);
            fail("null should not be accepted");
        }
        catch (IllegalArgumentException expected) {
        }
    }
}
