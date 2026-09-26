/*
 * KeyNotation.java
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
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111, USA.
 */

package org.armedbear.j.vim;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.armedbear.j.Constants;

/**
 * Vim's key notation: the "&lt;C-w&gt;" in ":map &lt;C-w&gt;h".
 *
 * A key sequence is a string of strokes. Most strokes are a single character
 * that stands for itself ("d", "$", "3"). The rest are written between angle
 * brackets, either a named key ("&lt;Esc&gt;", "&lt;CR&gt;", "&lt;Left&gt;") or a
 * modified key ("&lt;C-w&gt;", "&lt;S-Tab&gt;", "&lt;C-S-Right&gt;"). A literal "&lt;"
 * is written "&lt;lt&gt;".
 *
 * This is not j's own key text format, which is what {@code Utilities.getKeyStroke}
 * parses ("Ctrl Alt S"). The two notations coexist: j's for j's key maps, vim's
 * for modal key maps and for anything the user would recognise from a vimrc.
 *
 * Modifier masks are j's, from {@link Constants} -- the same masks a
 * {@code JEvent} carries -- so that a parsed stroke can be compared directly
 * against a dispatched event. {@link #awtModifiers} converts to the AWT
 * masks needed to synthesize a {@code KeyEvent}.
 */
public final class KeyNotation
{
    private KeyNotation()
    {
    }

    /**
     * One keystroke: what {@code JEvent} carries, before it is an event.
     *
     * A stroke that produces a character has a {@link #keyChar}; one that does
     * not (an arrow key, a function key) has {@code KeyEvent.CHAR_UNDEFINED}.
     * A stroke for a plain printable character has no {@link #keyCode}, because
     * which physical key produced it depends on the keyboard layout.
     */
    public static final class Stroke
    {
        public final int keyCode;
        public final char keyChar;
        public final int modifiers;

        Stroke(int keyCode, char keyChar, int modifiers)
        {
            this.keyCode = keyCode;
            this.keyChar = keyChar;
            this.modifiers = modifiers;
        }

        /** True if this stroke should also produce a KEY_TYPED event. */
        public boolean producesChar()
        {
            return keyChar != KeyEvent.CHAR_UNDEFINED
                && (modifiers & (Constants.CTRL_MASK | Constants.ALT_MASK
                                 | Constants.META_MASK)) == 0;
        }

        @Override
        public String toString()
        {
            return name(keyCode, keyChar, modifiers);
        }
    }

    // Named keys, as vim spells them. Several names share one key.
    private static final Map<String, int[]> NAMED = new HashMap<String, int[]>();
    // The name to use when going the other way, keyed by key code.
    private static final Map<Integer, String> CANONICAL = new HashMap<Integer, String>();

    private static void named(String name, int keyCode, char keyChar,
                              boolean canonical)
    {
        NAMED.put(name.toLowerCase(), new int[] {keyCode, keyChar});
        if (canonical)
            CANONICAL.put(Integer.valueOf(keyCode), name);
    }

    static {
        named("Esc",      KeyEvent.VK_ESCAPE,     (char) 0x1b, true);
        named("Escape",   KeyEvent.VK_ESCAPE,     (char) 0x1b, false);
        named("CR",       KeyEvent.VK_ENTER,      '\n',        true);
        named("Enter",    KeyEvent.VK_ENTER,      '\n',        false);
        named("Return",   KeyEvent.VK_ENTER,      '\n',        false);
        named("NL",       KeyEvent.VK_ENTER,      '\n',        false);
        named("BS",       KeyEvent.VK_BACK_SPACE, '\b',        true);
        named("Tab",      KeyEvent.VK_TAB,        '\t',        true);
        named("Del",      KeyEvent.VK_DELETE,     (char) 0x7f, true);
        named("Delete",   KeyEvent.VK_DELETE,     (char) 0x7f, false);
        named("Space",    KeyEvent.VK_SPACE,      ' ',         true);
        named("Up",       KeyEvent.VK_UP,         KeyEvent.CHAR_UNDEFINED, true);
        named("Down",     KeyEvent.VK_DOWN,       KeyEvent.CHAR_UNDEFINED, true);
        named("Left",     KeyEvent.VK_LEFT,       KeyEvent.CHAR_UNDEFINED, true);
        named("Right",    KeyEvent.VK_RIGHT,      KeyEvent.CHAR_UNDEFINED, true);
        named("Home",     KeyEvent.VK_HOME,       KeyEvent.CHAR_UNDEFINED, true);
        named("End",      KeyEvent.VK_END,        KeyEvent.CHAR_UNDEFINED, true);
        named("PageUp",   KeyEvent.VK_PAGE_UP,    KeyEvent.CHAR_UNDEFINED, true);
        named("PageDown", KeyEvent.VK_PAGE_DOWN,  KeyEvent.CHAR_UNDEFINED, true);
        named("Ins",      KeyEvent.VK_INSERT,     KeyEvent.CHAR_UNDEFINED, true);
        named("Insert",   KeyEvent.VK_INSERT,     KeyEvent.CHAR_UNDEFINED, false);
        for (int i = 1; i <= 12; i++)
            named("F" + i, KeyEvent.VK_F1 + i - 1, KeyEvent.CHAR_UNDEFINED, true);
        // Characters that cannot be written literally inside <>.
        named("lt",       0, '<',  false);
        named("gt",       0, '>',  false);
        named("Bar",      0, '|',  false);
        named("Bslash",   0, '\\', false);
    }

    /**
     * Parses a key sequence into its strokes.
     *
     * @throws IllegalArgumentException if a &lt;&gt; form is unrecognised, so
     *         that a typo in a key map is reported rather than silently
     *         treated as seven literal characters.
     */
    public static List<Stroke> parse(String keys)
    {
        final List<Stroke> strokes = new ArrayList<Stroke>();
        for (String token : tokenize(keys)) {
            if (token.charAt(0) == '<')
                strokes.add(parseBracketed(
                    token.substring(1, token.length() - 1), keys));
            else
                // A surrogate pair is typed as two events, as AWT sends it.
                for (int i = 0; i < token.length(); i++)
                    strokes.add(new Stroke(0, token.charAt(i), 0));
        }
        return strokes;
    }

    /**
     * Splits a key sequence into one token per keystroke, without interpreting
     * any of them: "cw&lt;Esc&gt;" becomes ["c", "w", "&lt;Esc&gt;"].
     *
     * Key maps need the split without the interpretation, because a map's
     * left-hand side may contain placeholders such as
     * &lt;character&gt; that stand for a whole class of keys rather than for
     * one.
     *
     * A '&lt;' that does not open a bracketed name is the key itself, as it
     * is in a vimrc: {@code &lt;&lt;} is two keys, not a malformed one. It is
     * normalised to {@code &lt;lt&gt;} so that a binding and a keystroke spell
     * that key the same way.
     */
    public static List<String> tokenize(String keys)
    {
        final List<String> tokens = new ArrayList<String>();
        final int length = keys.length();
        int i = 0;
        while (i < length) {
            final int end = keys.charAt(i) == '<' ? keys.indexOf('>', i + 1) : -1;
            if (end > i + 1) {
                tokens.add(keys.substring(i, end + 1));
                i = end + 1;
            } else if (keys.charAt(i) == '<') {
                tokens.add("<lt>");
                ++i;
            } else {
                // A surrogate pair is one key, as an emoji typed after f is.
                final int n = Character.charCount(keys.codePointAt(i));
                tokens.add(keys.substring(i, i + n));
                i += n;
            }
        }
        return tokens;
    }

    /** Convenience for a sequence that is known to be a single stroke. */
    public static Stroke parseOne(String key)
    {
        final List<Stroke> strokes = parse(key);
        if (strokes.size() != 1)
            throw new IllegalArgumentException(
                "expected a single stroke, got " + strokes.size()
                + " in \"" + key + "\"");
        return strokes.get(0);
    }

    private static Stroke parseBracketed(String body, String whole)
    {
        int modifiers = 0;
        // Strip leading modifier prefixes: C- S- A- M- D-.
        while (body.length() > 2 && body.charAt(1) == '-') {
            final int bit = modifierBit(body.charAt(0));
            if (bit == 0)
                break;
            modifiers |= bit;
            body = body.substring(2);
        }
        if (body.isEmpty())
            throw new IllegalArgumentException(
                "empty key name in \"" + whole + "\"");

        final int[] key = NAMED.get(body.toLowerCase());
        if (key != null)
            return new Stroke(key[0], (char) key[1], modifiers);

        if (body.length() == 1) {
            char c = body.charAt(0);
            // <S-a> means A; <C-a> keeps the letter and the mask, because the
            // control character is not what the key map matches on.
            if ((modifiers & Constants.SHIFT_MASK) != 0
                && Character.isLetter(c)) {
                c = Character.toUpperCase(c);
                modifiers &= ~Constants.SHIFT_MASK;
            }
            return new Stroke(0, c, modifiers);
        }
        throw new IllegalArgumentException(
            "unrecognised key name \"<" + body + ">\" in \"" + whole + "\"");
    }

    private static int modifierBit(char c)
    {
        switch (Character.toUpperCase(c)) {
            case 'C': return Constants.CTRL_MASK;
            case 'S': return Constants.SHIFT_MASK;
            case 'A': return Constants.ALT_MASK;
            case 'M': return Constants.ALT_MASK;
            case 'D': return Constants.META_MASK;
            default:  return 0;
        }
    }

    /**
     * Spells a dispatched key the way a key map writes it.
     *
     * The inverse of {@link #parse} for everything {@link #parse} can produce.
     */
    public static String name(int keyCode, char keyChar, int modifiers)
    {
        final String named = CANONICAL.get(Integer.valueOf(keyCode));
        if (named != null)
            return bracket(named, modifiers);

        if ((modifiers & Constants.CTRL_MASK) != 0) {
            final char body = controlBody(keyCode, keyChar);
            if (body != 0)
                return bracket(String.valueOf(body), shiftFolded(body,
                                                                 modifiers));
        }

        if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar != 0) {
            if (modifiers == 0) {
                switch (keyChar) {
                    case '<':  return "<lt>";
                    case ' ':  return "<Space>";
                    default:   return String.valueOf(keyChar);
                }
            }
            return bracket(String.valueOf(keyChar),
                           shiftFolded(keyChar, modifiers));
        }
        // A key with neither a name nor a character: spell it by code so that
        // it is at least greppable rather than silently dropped.
        return bracket("k" + keyCode, modifiers);
    }

    /**
     * The key a control keystroke names: the "r" in "&lt;C-r&gt;".
     *
     * AWT reports a control key press by the character it <em>produces</em> --
     * Ctrl-R arrives as 0x12, not as 'r' -- so naming the key after its
     * character spells it in a way no key map can match, and it falls through
     * to j's own bindings instead. The key code says which key was pressed;
     * for the few control characters that are not letters, the C0 table does.
     * Returns 0 for a control key that is neither.
     */
    private static char controlBody(int keyCode, char keyChar)
    {
        if (keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z)
            return (char) ('a' + keyCode - KeyEvent.VK_A);
        if (keyChar >= 1 && keyChar <= 26)
            return (char) ('a' + keyChar - 1);
        switch (keyChar) {
            case 0x1b: return '[';
            case 0x1c: return '\\';
            case 0x1d: return ']';
            case 0x1e: return '^';
            case 0x1f: return '_';
            default:   return 0;
        }
    }

    /**
     * Drops Shift for a character that is not a letter.
     *
     * For ^ or _ or ! the Shift is only how the keyboard makes the character,
     * and vim writes <C-^> for Ctrl-Shift-6. AWT hands over the character
     * with Shift still held -- keyCode VK_6, '^', Ctrl and Shift, seen with a
     * key probe -- so without this the key is named <C-S-^> and matches
     * nothing. A letter keeps its Shift: <C-S-r> is not <C-r>.
     */
    private static int shiftFolded(char c, int modifiers)
    {
        return Character.isLetter(c) ? modifiers
            : modifiers & ~Constants.SHIFT_MASK;
    }

    private static String bracket(String body, int modifiers)
    {
        final StringBuilder sb = new StringBuilder("<");
        if ((modifiers & Constants.CTRL_MASK) != 0)
            sb.append("C-");
        if ((modifiers & Constants.SHIFT_MASK) != 0)
            sb.append("S-");
        if ((modifiers & Constants.ALT_MASK) != 0)
            sb.append("A-");
        if ((modifiers & Constants.META_MASK) != 0)
            sb.append("D-");
        sb.append(body);
        sb.append('>');
        return sb.toString();
    }

    /**
     * The character a key stands for as a code point: {@link #characterOf},
     * but a key that is one surrogate pair gives the whole character.
     */
    public static int codePointOf(String key)
    {
        if (key != null && key.length() == 2
            && Character.isSurrogatePair(key.charAt(0), key.charAt(1)))
            return key.codePointAt(0);
        return characterOf(key);
    }

    /**
     * The character a key name stands for, or 0 for a key that is not one.
     *
     * A key map placeholder captures the key's <em>name</em>, so the character
     * {@code <} arrives as {@code "<lt>"} and a space as {@code "<Space>"}.
     * Anything that takes a character argument -- {@code f}, {@code r},
     * {@code m}, {@code "} -- has to come back through here.
     */
    public static char characterOf(String key)
    {
        if (key == null || key.isEmpty())
            return 0;
        if (key.length() == 1)
            return key.charAt(0);
        if (key.charAt(0) != '<' || key.charAt(key.length() - 1) != '>')
            return 0;
        final int[] named = NAMED.get(
            key.substring(1, key.length() - 1).toLowerCase());
        if (named == null)
            return 0;
        final char c = (char) named[1];
        return c == KeyEvent.CHAR_UNDEFINED ? 0 : c;
    }

    /** Converts j's modifier mask to the AWT extended mask. */
    public static int awtModifiers(int modifiers)
    {
        int ex = 0;
        if ((modifiers & Constants.SHIFT_MASK) != 0)
            ex |= java.awt.event.InputEvent.SHIFT_DOWN_MASK;
        if ((modifiers & Constants.CTRL_MASK) != 0)
            ex |= java.awt.event.InputEvent.CTRL_DOWN_MASK;
        if ((modifiers & Constants.META_MASK) != 0)
            ex |= java.awt.event.InputEvent.META_DOWN_MASK;
        if ((modifiers & Constants.ALT_MASK) != 0)
            ex |= java.awt.event.InputEvent.ALT_DOWN_MASK;
        return ex;
    }
}
