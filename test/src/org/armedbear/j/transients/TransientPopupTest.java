/*
 * TransientPopupTest.java
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.DirectoryFilenameFilter;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.transients.Transient.Binding;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Cycle;
import org.armedbear.j.transients.Transient.Field;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.Transient.Item;
import org.armedbear.j.transients.Transient.Option;
import org.armedbear.j.transients.Transient.Prefix;
import org.armedbear.j.transients.Transient.Switch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TransientPopupTest {
    @TempDir
    Path tmp;

    private EditorHarness h;
    private TransientPopup popup;

    private static final Switch ALL = Switch.of("-a", "--all", "all branches");
    private static final Option ORDER = new Option("o", "--order=", "order", new Cycle(List.of("date", "topo")), null);
    private static final Option AUTHOR = new Option("=a", "--author=", "author", new Field(), null);
    private static final Command END = Command.of("e", "eob", "end");
    private static final Command TOP = new Command("t", "bob", "top", true);

    private static final Transient INNER = new Transient("test.inner",
            "Inner",
            "commands.html",
            List.of(Group.of("Inside", Command.of("x", "eob", "x"))));
    private static final Transient LOG = new Transient("test.log",
            "Log",
            "commands.html",
            List.of(
                Group.of("Arguments", ALL, ORDER, AUTHOR),
                Group.of("Run", END, TOP, new Prefix("i", "inner", () -> INNER))));

    @BeforeEach
    public void setUp() {
        Transients.reset(tmp.resolve("defaults.properties"));
        h = EditorHarness.create("one\ntwo\nthree\n");
        Editor.setCurrentEditor(h.editor());
        popup = TransientPopup.open(h.editor(), LOG);
    }

    @AfterEach
    public void tearDown() {
        popup.close(false);
        h.close();
        Transients.reset(null);
    }

    private TransientArgs args() {
        return Transients.args(h.editor(), "test.log");
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {});
    }

    private void escape() {
        popup.pressed(KeyEvent.VK_ESCAPE, false, false);
    }

    @Test
    public void showsTheGroupsKeysAndHelp() {
        final List<String> text = popup.text();
        assertEquals("Log", text.get(0));
        assertTrue(text.get(1).startsWith("Arguments"), text.toString());
        assertTrue(text.get(1).contains("Run"), "groups side by side: " + text);
        assertTrue(text.get(2).startsWith("-a all branches (--all)"), text.toString());
        assertTrue(text.get(text.size() - 1).startsWith("? help"), text.toString());
    }

    @Test
    public void aSwitchTogglesWithATwoLetterKey() {
        popup.typed('-');
        assertTrue(popup.text().get(0).contains("-"), "the half typed key shows");
        popup.typed('a');
        assertTrue(args().isOn("--all"));
        popup.typed('-');
        popup.typed('a');
        assertFalse(args().isOn("--all"));
        assertFalse(popup.isClosed());
    }

    @Test
    public void anOptionCyclesThroughItsValues() {
        popup.typed('o');
        assertEquals("date", args().get("--order="));
        popup.typed('o');
        assertEquals("topo", args().get("--order="));
        popup.typed('o');
        assertEquals("date", args().get("--order="));
        assertEquals(List.of("--order=date"), args().arguments());
    }

    @Test
    public void anOptionsFieldSetsAndUnsetsIt() {
        popup.typed('=');
        popup.typed('a');
        assertNotNull(popup.getField());
        popup.acceptField(" kk ");
        assertNull(popup.getField());
        assertEquals("kk", args().get("--author="));
        popup.typed('=');
        popup.typed('a');
        popup.acceptField("");
        assertNull(args().get("--author="));
    }

    @Test
    public void escapeLeavesTheFieldThenThePopup() {
        popup.typed('=');
        popup.typed('a');
        escape();
        assertNull(popup.getField());
        assertFalse(popup.isClosed());
        escape();
        assertTrue(popup.isClosed());
        assertNull(TransientPopup.getOpen());
    }

    @Test
    public void aPrefixReplacesTheContentsAndEscapeComesBack() {
        popup.typed('-');
        popup.typed('a');
        popup.typed('i');
        assertSame(INNER, popup.getTransient());
        assertEquals("Inner", popup.text().get(0));
        escape();
        assertSame(LOG, popup.getTransient());
        assertTrue(args().isOn("--all"), "values set before are kept");
        escape();
        assertTrue(popup.isClosed());
    }

    @Test
    public void aCommandRunsAndCloses() throws Exception {
        popup.typed('e');
        assertTrue(popup.isClosed());
        flush();
        assertEquals(2, h.editor().getDotLineNumber(), "the last line");
    }

    @Test
    public void aCommandMarkedToStayLeavesItOpen() throws Exception {
        h.cursor(2, 0);
        popup.typed('t');
        flush();
        assertFalse(popup.isClosed());
        assertEquals(0, h.editor().getDotLineNumber());
    }

    @Test
    public void anUnknownKeySaysSoAndEscapeStillCloses() {
        popup.typed('z');
        assertTrue(popup.text().get(0).contains("z is not a key here"), popup.text().toString());
        escape();
        assertTrue(popup.isClosed());
    }

    @Test
    public void qCloses() {
        popup.typed('q');
        assertTrue(popup.isClosed());
    }

    @Test
    public void savingOnePopupsDefaultsKeepsAnothersWhoseIdItStarts() throws Exception {
        final Transient git = new Transient("test",
                "Git",
                "commands.html",
                List.of(Group.of("A", Switch.of("-v", "--verbose", "verbose"))));
        popup.typed('o');
        popup.pressed(KeyEvent.VK_S, true, false); // test.log's
        popup = TransientPopup.open(h.editor(), git);
        popup.pressed(KeyEvent.VK_S, true, false); // test's, with nothing set
        Transients.reset(tmp.resolve("defaults.properties"));
        assertEquals("date", Transients.args(h.buffer(), "test.log").get("--order="));
    }

    @Test
    public void ctrlSWithOnlyTheBuffersSettingsSaysThereIsNothingToSave() {
        final Transient dir = org.armedbear.j.mode.dir.DirectoryMode.getMode().getTransient(null);
        popup = TransientPopup.open(h.editor(), dir);
        popup.pressed(KeyEvent.VK_S, true, false);
        assertTrue(popup.text().get(0).contains("nothing to save"), popup.text().toString());
        assertFalse(Files.exists(tmp.resolve("defaults.properties")));
    }

    @Test
    public void aClickDoesWhatTheKeyDoes() {
        final Rectangle r = popup.boundsOf(ALL);
        popup.clicked(r.x + 1, r.y + 1);
        assertTrue(args().isOn("--all"));
        final Rectangle o = popup.boundsOf(ORDER);
        popup.clicked(o.x + o.width - 1, o.y + o.height - 1);
        assertEquals("date", args().get("--order="));
        popup.clicked(0, 0);
        assertFalse(popup.isClosed(), "a click on nothing does nothing");
    }

    @Test
    public void ctrlHThenAKeyAsksForHelpWithoutActing() {
        popup.pressed(KeyEvent.VK_H, true, false);
        assertTrue(popup.text().get(0).contains("help for which key?"));
        popup.pressed(KeyEvent.VK_ESCAPE, false, false);
        assertFalse(popup.text().get(0).contains("help"));
        popup.typed('o');
        assertEquals("date", args().get("--order="));
    }

    @Test
    public void valuesArePerBufferAndANewBufferStartsFromTheLast() {
        popup.typed('-');
        popup.typed('a');
        final EditorHarness h2 = EditorHarness.create("x");
        try {
            final Buffer other = h2.buffer();
            assertTrue(Transients.args(other, "test.log").isOn("--all"), "seeded from the last values");
            popup.typed('-');
            popup.typed('a');
            assertTrue(Transients.args(other, "test.log").isOn("--all"), "its own copy");
        }
        finally {
            h2.close();
        }
    }

    @Test
    public void ctrlSSavesTheDefaults() throws Exception {
        popup.typed('o');
        popup.pressed(KeyEvent.VK_S, true, false);
        assertTrue(Files.readString(tmp.resolve("defaults.properties")).contains("test.log.value.--order\\==date"));
        Transients.reset(tmp.resolve("defaults.properties"));
        assertEquals("date", Transients.args(h.buffer(), "test.log").get("--order="), "read back");
    }

    @Test
    public void aBoundValueLivesWhereItsBindingSays() {
        final AtomicBoolean hidden = new AtomicBoolean();
        final AtomicReference<String> sort = new AtomicReference<>("name");
        final Transient t = new Transient("test.bound",
                "Bound",
                "commands.html",
                List.of(
                    Group.of(
                        "Filters",
                        new Switch("h", "ls -a", "hidden", binding(hidden::get, hidden::set)),
                        new Option("s",
                                "sort",
                                "sort",
                                new Cycle(List.of("name", "date")),
                                binding(sort::get, sort::set)))));
        popup = TransientPopup.open(h.editor(), t);
        popup.typed('h');
        popup.typed('s');
        assertTrue(hidden.get());
        assertEquals("date", sort.get());
        assertTrue(Transients.args(h.editor(), "test.bound").arguments().isEmpty());
    }

    private static <T> Binding<T> binding(java.util.function.Supplier<T> get, java.util.function.Consumer<T> set) {
        return new Binding<>() {
            @Override
            public T get(Editor editor) {
                return get.get();
            }

            @Override
            public void set(Editor editor, T value) {
                set.accept(value);
            }
        };
    }

    @Test
    public void openingAnotherClosesTheFirst() {
        final TransientPopup second = TransientPopup.open(h.editor(), INNER);
        assertTrue(popup.isClosed());
        assertSame(second, TransientPopup.getOpen());
        popup = second;
    }

    @Test
    public void keysMustBeUniqueAndNotStartOthers() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new Transient("x", "X", "h", List.of(Group.of("G", END, Command.of("e", "bob", "again")))));
        assertThrows(
            IllegalArgumentException.class,
            () -> new Transient("x", "X", "h", List.of(Group.of("G", END, Command.of("ex", "bob", "longer")))));
    }

    @Test
    public void dirModesPopupHasItsFiltersAndModePopupOpensIt() {
        final Transient dir = org.armedbear.j.mode.dir.DirectoryMode.getMode().getTransient(null);
        assertEquals("directories.html#popup", dir.help());
        assertNotNull(dir.item("s"));
        assertNotNull(dir.item("."));
        assertEquals("dirRescan", ((Command) dir.item("r")).command());
        TransientCommands.modePopup(h.editor());
        assertSame(popup, TransientPopup.getOpen(), "plain text has no popup");
    }

    @Test
    public void hiddenNamesAreLeftOutButNotDotOrDotDot() {
        final DirectoryFilenameFilter hide = new DirectoryFilenameFilter(null, true);
        assertTrue(hide.hides(".git"));
        assertFalse(hide.hides("."));
        assertFalse(hide.hides(".."));
        assertFalse(hide.accepts(".env"));
        assertTrue(hide.accepts("src"));
        final DirectoryFilenameFilter both = new DirectoryFilenameFilter("*.java", true);
        assertTrue(both.accepts("A.java"));
        assertFalse(both.accepts(".A.java"));
        assertFalse(both.accepts("A.txt"));
        assertTrue(new DirectoryFilenameFilter(null, false).accepts(".git"));
    }

    @Test
    public void dirPopupKeysAreTheBuffersOwnInEitherEditMode() {
        final org.armedbear.j.mode.dir.DirectoryMode mode = org.armedbear.j.mode.dir.DirectoryMode.getMode();
        final EditorHarness vim = EditorHarness.create().vim();
        try {
            assertKeysMapped(mode.getTransient(h.editor()), mode.getKeyMap());
            assertKeysMapped(mode.getTransient(vim.editor()), mode.getModalKeyMap());
            assertEquals("-", mode.getTransient(vim.editor()).groups().get(1).items().get(1).key());
        }
        finally {
            vim.close();
        }
    }

    // Each item's key does in the buffer what it does in the popup.
    private static void assertKeysMapped(Transient t, org.armedbear.j.KeyMap km) {
        for (Group g : t.groups())
            for (Item item : g.items()) {
                final String command = switch (item) {
                    case Command c -> c.command();
                    case Option o when o.name().equals("sort") -> "dirCycleSortBy";
                    case Option o -> "dirLimit";
                    case Switch s -> "dirToggleHidden";
                    default -> throw new AssertionError(item);
                };
                final org.armedbear.j.KeyMapping m = km.lookup(item.key().charAt(0), 0, 0);
                assertNotNull(m, item.key());
                assertEquals(command, m.getCommand(), item.key());
            }
    }

    @Test
    public void linksGetTheHandCursor() {
        final Rectangle o = popup.boundsOf(ORDER);
        popup.dispatchEvent(
            new java.awt.event.MouseEvent(popup,
                    java.awt.event.MouseEvent.MOUSE_MOVED,
                    0,
                    0,
                    o.x + 1,
                    o.y + 1,
                    0,
                    false));
        assertEquals(java.awt.Cursor.HAND_CURSOR, popup.getCursor().getType());
        popup.dispatchEvent(
            new java.awt.event.MouseEvent(popup, java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0, 0, 0, 0, false));
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, popup.getCursor().getType());
    }

    @Test
    public void aBadPatternMatchesNothing() {
        assertFalse(new DirectoryFilenameFilter("[", false).accepts("["));
    }

    @Test
    public void aBlankFieldHighlightsTheFirstCandidateUnlessAValueWasErased() {
        assertEquals(0, OptionField.highlight("", false), "unset: Enter takes the first");
        assertEquals(-1, OptionField.highlight("", true), "erased: Enter clears it");
        assertEquals(-1, OptionField.highlight("  ", true));
        assertEquals(0, OptionField.highlight("ma", true), "typed: the first match");
    }

    @Test
    public void candidatesThatAreTheTextComeFirstThenThoseThatStartWithIt() {
        final List<String> all = List.of("domain-x", "origin/main", "main", "maintenance", "MAIN-2");
        assertEquals(
            List.of("main", "maintenance", "MAIN-2", "domain-x", "origin/main"),
            OptionField.matches(all, "main", 10));
        assertEquals(List.of("main", "maintenance"), OptionField.matches(all, " Main ", 2));
        assertEquals(all, OptionField.matches(all, "", 10), "blank: all, in order");
        assertEquals(List.of(), OptionField.matches(all, "zzz", 10));
    }
}
