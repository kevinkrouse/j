/*
 * OptionField.java
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

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.armedbear.j.CompletionPopup;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.transients.Transient.Option;
import org.armedbear.j.transients.Transient.Pick;

/**
 * The field an option's value is typed in, with a {@link Pick}'s candidates
 * listed under it as they match: the popup's, and the filter bar's.
 * Enter accepts the candidate highlighted, or the text when none is; Escape gives it up.
 */
final class OptionField {
    // More matches than this aren't listed: a repository's paths can be many.
    private static final int MAX_SHOWN = 1000;

    private final JTextField field;
    private CompletionPopup<String> completion;
    private List<String> candidates;
    private static final int WAIT_STEP = 250;
    private static final int WAIT_MAX = 10_000;
    private Timer waiting;
    private int waited;
    // Whether the option had a value when the field opened, which erasing clears.
    private final boolean wasSet;

    OptionField(Option o, Editor editor, String value, Consumer<String> accept, Runnable escape) {
        field = new JTextField(value == null ? "" : value);
        wasSet = value != null && !value.isBlank();
        field.setFont(Display.getPlainFont());
        field.setBorder(BorderFactory.createEmptyBorder());
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                final int code = e.getKeyCode();
                if (code == KeyEvent.VK_ENTER) {
                    final String selected =
                            completion != null && completion.isShowing() ? completion.getSelected() : null;
                    accept.accept(selected != null ? selected : field.getText());
                    e.consume();
                } else if (code == KeyEvent.VK_ESCAPE) {
                    escape.run();
                    e.consume();
                } else if (completion != null
                        && completion.isShowing()
                        && (code == KeyEvent.VK_DOWN || code == KeyEvent.VK_UP)) {
                    completion.move(code == KeyEvent.VK_DOWN ? 1 : -1, true);
                    e.consume();
                }
            }
        });
        if (o.chooser() instanceof Pick pick) {
            completion = new CompletionPopup<>(field, 10);
            completion.setOnClick(accept);
            candidates = pick.candidates().apply(editor);
            // Still being read, as a log's names when its popup opens: asked again until they come.
            if (candidates.isEmpty()) {
                waiting = new Timer(WAIT_STEP, null);
                waiting.addActionListener(e -> {
                    candidates = pick.candidates().apply(editor);
                    if (!candidates.isEmpty() || ++waited * WAIT_STEP >= WAIT_MAX) {
                        waiting.stop();
                        if (!candidates.isEmpty() && field.isShowing())
                            filter();
                    }
                });
                waiting.start();
            }
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    filter();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    filter();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {}
            });
        }
    }

    /**
     * Which candidate is highlighted, for Enter to take; -1 for none, when
     * Enter takes the text. The first match is, unless the field is blank
     * after erasing a value it had, when Enter clears it.
     */
    static int highlight(String text, boolean wasSet) {
        return text.isBlank() && wasSet ? -1 : 0;
    }

    JTextField field() {
        return field;
    }

    /** Once the field is placed: its text selected, the focus in it, and the candidates listed. */
    void start() {
        field.selectAll();
        field.requestFocusInWindow();
        if (candidates != null)
            filter();
    }

    void close() {
        if (waiting != null)
            waiting.stop();
        if (completion != null) {
            completion.hide();
            completion = null;
        }
    }

    private void filter() {
        if (completion == null)
            return;
        completion.show(matches(candidates, field.getText(), MAX_SHOWN), highlight(field.getText(), wasSet));
    }

    /**
     * The candidates that have text in them, ignoring case, at most max:
     * one that is text first, then those that start with it, then the rest,
     * each in their order, so that Enter on "main" takes main, not domain-x.
     */
    static List<String> matches(List<String> candidates, String text, int max) {
        final String t = text.strip();
        final List<String> exact = new ArrayList<>();
        final List<String> starting = new ArrayList<>();
        final List<String> within = new ArrayList<>();
        for (String s : candidates) {
            if (s.equalsIgnoreCase(t))
                exact.add(s);
            else if (s.regionMatches(true, 0, t, 0, t.length())) {
                starting.add(s);
                // Enough that start with it: the rest can't come before them.
                if (exact.size() + starting.size() >= max)
                    break;
            } else if (within.size() < max && containsIgnoringCase(s, t))
                within.add(s);
        }
        final List<String> out = new ArrayList<>(exact);
        out.addAll(starting);
        out.addAll(within);
        return out.size() > max ? out.subList(0, max) : out;
    }

    private static boolean containsIgnoringCase(String s, String t) {
        for (int i = 0; i + t.length() <= s.length(); i++)
            if (s.regionMatches(true, i, t, 0, t.length()))
                return true;
        return false;
    }
}
