/*
 * ScriptFunctionTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.armedbear.j.KeyMap;
import org.armedbear.j.KeyMapping;
import org.junit.Test;

/**
 * A function defined in another language, bound to a key.
 *
 * <p>{@link KeyMapping} has always stored its command as an untyped Object so
 * that init.lisp could bind a closure. These cover the replacement of that
 * {@code instanceof LispObject} case with an interface: core must be able to
 * carry the thing around and describe it without naming its type.
 */
public class ScriptFunctionTest {
    /** Stands in for a closure defined in whatever language is installed. */
    private static final class FakeFunction implements ScriptFunction {
        int invocations;

        public void invoke() {
            ++invocations;
        }

        public String describe() {
            return "#<FUNCTION (LAMBDA ()) {1234}>";
        }
    }

    @Test
    public void aFunctionSurvivesTheRoundTripThroughAKeyMap() {
        FakeFunction function = new FakeFunction();
        KeyMap keyMap = new KeyMap();
        assertTrue(keyMap.mapKey("Ctrl F12", function));

        KeyMapping[] mappings = keyMap.getMappings();
        assertEquals(1, mappings.length);
        // Identity, not equality: core must hand back the very object the
        // extension gave it, or invoking it would run something else.
        assertSame(function, mappings[0].getCommand());
    }

    @Test
    public void rebindingTheSameKeyReplacesTheFunction() {
        FakeFunction first = new FakeFunction();
        FakeFunction second = new FakeFunction();
        KeyMap keyMap = new KeyMap();
        assertTrue(keyMap.mapKey("Ctrl F12", first));
        assertTrue(keyMap.mapKey("Ctrl F12", second));

        KeyMapping[] mappings = keyMap.getMappings();
        assertEquals(1, mappings.length);
        assertSame(second, mappings[0].getCommand());
    }

    @Test
    public void coreInvokesItWithoutKnowingWhatItIs() {
        FakeFunction function = new FakeFunction();
        KeyMap keyMap = new KeyMap();
        keyMap.mapKey("Ctrl F12", function);

        Object command = keyMap.getMappings()[0].getCommand();
        assertTrue(command instanceof ScriptFunction);
        ((ScriptFunction) command).invoke();
        assertEquals(1, function.invocations);
    }

    @Test
    public void describeIsWhatTheKeyBindingListingShows() {
        // Help writes this into the key binding listing, where it used to call
        // LispObject.printObject().
        String description = new FakeFunction().describe();
        assertNotNull(description);
        assertTrue(description.startsWith("#<FUNCTION"));
    }
}
