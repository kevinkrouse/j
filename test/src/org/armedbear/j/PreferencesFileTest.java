/*
 * PreferencesFileTest.java
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

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The prefs file as it is read. */
public class PreferencesFileTest {
    @Test
    public void itIsReadAsUtf8() throws Exception {
        final Preferences p = read("foldSymbol=\nwrapCol=\\u0037\\u0032\n", StandardCharsets.UTF_8);
        assertEquals("", p.getStringProperty(Property.FOLD_SYMBOL));
        assertEquals(72, p.getIntegerProperty(Property.WRAP_COL), "an escape still read");
    }

    @Test
    public void oneSavedAsLatin1IsReadAsBefore() throws Exception {
        final Preferences p = read("attribution=café\n", StandardCharsets.ISO_8859_1);
        assertEquals("café", p.getStringProperty(Property.ATTRIBUTION));
    }

    // Preferences from a prefs file of text in charset: the harness's, under
    // a home of its own, never the user's.
    private static Preferences read(String text, Charset charset) throws Exception {
        EditorHarness.create().close();
        final Path home = Path.of(Directories.getUserHomeDirectory().canonicalPath());
        assertNotEquals(
            Path.of(System.getProperty("user.home")).toRealPath(),
            home.toRealPath(),
            "not the user's home");
        final Path prefs = Path.of(Preferences.getPreferencesFile().canonicalPath());
        assertTrue(prefs.startsWith(home), "under the harness's home");
        Files.createDirectories(prefs.getParent());
        Files.writeString(prefs, text, charset);
        try {
            final Preferences p = new Preferences();
            p.reload();
            return p;
        }
        finally {
            Files.delete(prefs);
        }
    }
}
