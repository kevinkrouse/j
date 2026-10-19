/*
 * FilterBarTest.java
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

package org.armedbear.j.transients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dir mode's sort, limit and hidden files as chips over the listing. */
public class FilterBarTest {
    @TempDir
    Path tmp;

    private EditorHarness h;
    private DirectoryBuffer dir;

    @BeforeEach
    public void setUp() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "a\n");
        Files.writeString(tmp.resolve("b.java"), "class B {}\n");
        Files.writeString(tmp.resolve(".hidden"), "h\n");
        h = EditorHarness.create();
        onEdt(() -> {
            Editor.setCurrentEditor(h.editor());
            dir = new DirectoryBuffer(File.getInstance(tmp.toString()));
            dir.load();
            h.showBuffer(dir);
            h.editor().updateFilterBar();
        });
    }

    @AfterEach
    public void tearDown() throws Exception {
        onEdt(() -> {
            h.showBuffer(h.buffer());
            Editor.getBufferList().remove(dir);
            h.close();
        });
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private FilterBar bar() {
        return h.editor().getFilterBar();
    }

    private boolean lists(String name) {
        for (Line l = dir.getFirstLine(); l != null; l = l.next())
            if (l.getText().endsWith(" " + name))
                return true;
        return false;
    }

    // The chip of item described so.
    private Transient.Item item(String description) {
        for (Transient.Item i : h.editor().getMode().getFilters(h.editor()))
            if (i.description().equals(description))
                return i;
        throw new AssertionError("no " + description);
    }

    // A click on the chip described so, or on its "×".
    private void click(String description, boolean clear) throws Exception {
        onEdt(() -> {
            final Transient.Item i = item(description);
            Rectangle r = null;
            for (Transient.Item shown : chipsOf(bar()))
                if (shown.description().equals(description))
                    r = clear ? bar().clearBoundsOf(shown) : bar().boundsOf(shown);
            assertNotNull(r, i.description() + (clear ? "'s ×" : ""));
            bar().clicked(r.x + 1);
        });
    }

    // The items the bar draws, in order.
    private static List<Transient.Item> chipsOf(FilterBar bar) {
        final List<Transient.Item> out = new ArrayList<>();
        for (Transient.Item i : bar.items())
            out.add(i);
        return out;
    }

    @Test
    public void theListingHasChipsForItsSortLimitAndHiddenFiles() {
        assertNotNull(bar(), "a directory has a filter bar");
        assertEquals(List.of("-s sort by: name ▾", "-p limit to ▾", "-. show hidden"), bar().text());
    }

    @Test
    public void aCycleIsSetOnlyAwayFromItsFirstValueAndItsCrossGoesBack() throws Exception {
        click("sort by", false);
        assertEquals("-s sort by: date ▾ ×", bar().text().get(0));
        click("sort by", true);
        assertEquals("-s sort by: name ▾", bar().text().get(0));
    }

    @Test
    public void aSwitchTurnsOverOnAClick() throws Exception {
        assertFalse(lists(".hidden"), "hidden by default");
        click("show hidden", false);
        assertEquals("-. show hidden ×", bar().text().get(2));
        assertTrue(lists(".hidden"));
        click("show hidden", false);
        assertEquals("-. show hidden", bar().text().get(2));
        assertFalse(lists(".hidden"));
        click("show hidden", false);
        click("show hidden", true);
        assertFalse(lists(".hidden"), "its × turns it off");
    }

    @Test
    public void xClearsEveryFilter() throws Exception {
        onEdt(() -> {
            dir.limit("*.txt");
            dir.setShowHidden(true);
            dir.resort(DirectoryBuffer.SORT_BY_SIZE);
        });
        assertEquals(List.of("-s sort by: size ▾ ×", "-p limit to: *.txt ▾ ×", "-. show hidden ×"), bar().text());
        onEdt(() -> {
            try {
                h.editor().execute("dirClearFilters");
            }
            catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        });
        assertEquals(List.of("-s sort by: name ▾", "-p limit to ▾", "-. show hidden"), bar().text());
        assertTrue(lists("b.java"));
        assertFalse(lists(".hidden"), "as dirShowHidden says");
    }

    @Test
    public void theKeysChangeTheChipsToo() throws Exception {
        onEdt(DirectoryBuffer::dirToggleHidden);
        assertEquals("-. show hidden ×", bar().text().get(2));
        // l types the pattern over its chip, not in a dialog.
        onEdt(DirectoryBuffer::dirLimit);
        assertNotNull(bar().getField(), "the field over the limit's chip");
        onEdt(() -> bar().acceptField("*.txt"));
        assertEquals("*.txt", dir.getLimitPattern());
        assertEquals("-p limit to: *.txt ▾ ×", bar().text().get(1));
        assertTrue(lists("a.txt"));
        assertFalse(lists("b.java"));
    }

    private void press(int code) throws Exception {
        onEdt(() -> {
            final JTextField f = bar().getField();
            for (KeyListener l : f.getKeyListeners())
                l.keyPressed(new KeyEvent(f, KeyEvent.KEY_PRESSED, 0, 0, code, KeyEvent.CHAR_UNDEFINED));
        });
    }

    @Test
    public void upInTheLimitsFieldBringsBackALimit() throws Exception {
        onEdt(DirectoryBuffer::dirLimit);
        onEdt(() -> bar().getField().setText("*.java"));
        press(KeyEvent.VK_ENTER);
        assertEquals("*.java", dir.getLimitPattern());
        onEdt(dir::clearFilters);
        onEdt(DirectoryBuffer::dirLimit);
        press(KeyEvent.VK_UP);
        assertEquals("*.java", bar().getField().getText());
    }
}
