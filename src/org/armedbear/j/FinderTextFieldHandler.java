/*
 * FinderTextFieldHandler.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.armedbear.j.util.FuzzyMatcher;
import org.armedbear.j.util.FuzzyMatcher.Query;
import org.armedbear.j.util.FuzzyMatcher.Ranked;
import org.armedbear.j.util.Keys;

/**
 * A location bar prompt that lists, as the user types, the candidates that
 * fuzzily match. Up and Down, or Ctrl-P and Ctrl-N, move the selection;
 * Enter accepts it, and Ctrl-Enter or Alt-Enter accepts it in the other window.
 */
public abstract class FinderTextFieldHandler extends DefaultTextFieldHandler {
    private static final int MAX_ROWS = 15;
    private static final int MAX_RESULTS = 200;
    private static final int DEBOUNCE_MILLIS = 25;

    // Ranking runs here, off the event dispatch thread.
    private static final ExecutorService ranker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Finder");
        t.setDaemon(true);
        return t;
    });

    private final CompletionPopup<FinderItem.Row> popup;
    private final AtomicInteger generation = new AtomicInteger();
    private final Timer debounce;
    private final DocumentListener documentListener = new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent e) {
            debounce.restart();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            debounce.restart();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {}
    };
    // The text the popup's rows were ranked for.
    private String shownText;

    protected FinderTextFieldHandler(Editor editor, HistoryTextField textField) {
        super(editor, textField);
        popup = new CompletionPopup<>(textField, MAX_ROWS);
        popup.setCellRenderer(new FinderCellRenderer());
        popup.setOnClick(row -> accept(row.item(), false));
        // Focus went elsewhere: give the location bar back.
        popup.setOnDismiss(() -> {
            if (isActive())
                editor.updateLocation();
        });
        debounce = new Timer(DEBOUNCE_MILLIS, e -> refilter());
        debounce.setRepeats(false);
        textField.getDocument().addDocumentListener(documentListener);
    }

    /** Every candidate, in the order an empty query lists them. Called on the event dispatch thread. */
    protected abstract List<FinderItem> candidates();

    /** What an empty query lists; by default, the candidates. */
    protected List<FinderItem> emptyQueryItems() {
        return candidates();
    }

    /** Ranked when candidates() has no match for a query; null for none. */
    protected List<FinderItem> fallbackCandidates() {
        return null;
    }

    /** Called with whether the list now showing came from fallbackCandidates(). */
    protected void listed(boolean fromFallback) {}

    /** The part of the field's text to match; a subclass may strip a suffix it handles itself. */
    protected String queryText(String text) {
        return text;
    }

    /** Whether this handler is still the location bar's. */
    protected final boolean isActive() {
        return textField.getHandler() == this;
    }

    @Override
    public void detached() {
        debounce.stop();
        generation.incrementAndGet();
        textField.getDocument().removeDocumentListener(documentListener);
        popup.hide();
    }

    /**
     * Lists the candidates again, for new text or changed candidates. The
     * selection stays on the same item if the text hasn't changed.
     */
    public final void refilter() {
        debounce.stop();
        // Nothing to do until the user is in the field.
        if (!isActive() || !hasFocus()) {
            popup.hide();
            return;
        }
        final String text = textField.getText();
        final Query query = Query.parse(queryText(text));
        final List<FinderItem> items = query.isEmpty() ? emptyQueryItems() : candidates();
        final List<FinderItem> fallback = query.isEmpty() ? null : fallbackCandidates();
        final int gen = generation.incrementAndGet();
        ranker.execute(() -> {
            if (gen != generation.get())
                return;
            List<FinderItem.Row> rows = rank(items, query);
            boolean fellBack = false;
            if (rows.isEmpty() && fallback != null) {
                rows = rank(fallback, query);
                fellBack = !rows.isEmpty();
            }
            final boolean fromFallback = fellBack;
            final List<FinderItem.Row> shown = rows;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation.get() || !isActive())
                    return;
                if (hasFocus()) {
                    show(text, shown);
                    listed(fromFallback);
                } else {
                    popup.hide();
                }
            });
        });
    }

    private static List<FinderItem.Row> rank(List<FinderItem> items, Query query) {
        List<Ranked<FinderItem>> ranked =
            FuzzyMatcher.rank(items, FinderItem::matchText, FinderItem::boost, query, MAX_RESULTS);
        List<FinderItem.Row> rows = new ArrayList<>(ranked.size());
        for (Ranked<FinderItem> r : ranked) {
            FuzzyMatcher.Match m = query.isEmpty() ? null : FuzzyMatcher.match(r.text(), query);
            rows.add(new FinderItem.Row(r.item(), m == null ? null : m.positions()));
        }
        return rows;
    }

    private void show(String text, List<FinderItem.Row> rows) {
        int index = 0;
        FinderItem.Row selected = popup.getSelected();
        if (selected != null && text.equals(shownText)) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).item().matchText().equals(selected.item().matchText())) {
                    index = i;
                    break;
                }
            }
        }
        shownText = text;
        popup.show(rows, index);
    }

    // The focus may still be on its way when the first list is ready.
    private boolean hasFocus() {
        return textField.isFocusOwner() || editor.getFrame().getFocusedComponent() == textField;
    }

    /** Whether the user has moved the selection off the first row. */
    protected final boolean selectionMoved() {
        return popup.isShowing() && popup.getSelectedIndex() > 0;
    }

    /** The selected row, ranked for the text as it is now; null if nothing matches. */
    protected final FinderItem.Row selection() {
        final String text = textField.getText();
        if (!text.equals(shownText) || !popup.isShowing()) {
            debounce.stop();
            generation.incrementAndGet();
            Query query = Query.parse(queryText(text));
            List<FinderItem.Row> rows = rank(query.isEmpty() ? emptyQueryItems() : candidates(), query);
            List<FinderItem> fallback = query.isEmpty() ? null : fallbackCandidates();
            boolean fromFallback = false;
            if (rows.isEmpty() && fallback != null) {
                rows = rank(fallback, query);
                fromFallback = !rows.isEmpty();
            }
            show(text, rows);
            listed(fromFallback);
        }
        return popup.getSelected();
    }

    /** Closes the prompt, then has item do its thing. */
    protected void accept(FinderItem item, boolean otherWindow) {
        if (item == null)
            return;
        closePrompt();
        item.accept(editor, otherWindow);
    }

    /** Saves the query, hides the list and gives the location bar back to the current location. */
    protected final void closePrompt() {
        saveHistory();
        detached();
        editor.setFocusToDisplay();
        editor.updateLocation();
    }

    private void saveHistory() {
        History history = textField.getHistory();
        String s = textField.getText().trim();
        if (history != null && !s.isEmpty()) {
            history.append(s);
            history.save();
        }
    }

    @Override
    public void enter() {
        FinderItem.Row row = selection();
        if (row != null)
            accept(row.item(), false);
        else
            editor.status("No match");
    }

    @Override
    public void escape() {
        detached();
        super.escape();
    }

    @Override
    public boolean wantTab() {
        return true;
    }

    @Override
    public void tab() {
        popup.move(+1, true);
    }

    @Override
    public void shiftTab() {
        popup.move(-1, true);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        final int modifiers = Keys.keyModifiers(e);
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ENTER:
                if (modifiers == CTRL_MASK || modifiers == ALT_MASK) {
                    e.consume();
                    FinderItem.Row row = selection();
                    if (row != null)
                        accept(row.item(), true);
                    return;
                }
                break;
            case KeyEvent.VK_UP:
            case KeyEvent.VK_KP_UP:
                if (modifiers == 0) {
                    e.consume();
                    popup.move(-1, false);
                    return;
                }
                break;
            case KeyEvent.VK_DOWN:
            case KeyEvent.VK_KP_DOWN:
                if (modifiers == 0) {
                    e.consume();
                    popup.move(+1, false);
                    return;
                }
                break;
            case KeyEvent.VK_P:
                if (modifiers == CTRL_MASK) {
                    e.consume();
                    popup.move(-1, false);
                    return;
                }
                break;
            case KeyEvent.VK_N:
                if (modifiers == CTRL_MASK) {
                    e.consume();
                    popup.move(+1, false);
                    return;
                }
                break;
            case KeyEvent.VK_PAGE_UP:
                e.consume();
                popup.page(-1);
                return;
            case KeyEvent.VK_PAGE_DOWN:
                e.consume();
                popup.page(+1);
                return;
            default:
                break;
        }
        // findAction's key toggles between the finders, and findFileInProject's
        // goes to it; the query comes along.
        KeyMapping mapping = editor.getKeyMapping(e.getKeyChar(), e.getKeyCode(), modifiers);
        if (mapping != null) {
            Object command = mapping.getCommand();
            final String query = textField.getText();
            if ("findAction".equals(command)) {
                e.consume();
                if (this instanceof ActionTextFieldHandler)
                    ProjectCommands.findFileInProject(editor, query);
                else
                    ProjectCommands.findAction(editor, query);
                return;
            }
            if ("findFileInProject".equals(command) && !(this instanceof FindFileTextFieldHandler)) {
                e.consume();
                ProjectCommands.findFileInProject(editor, query);
                return;
            }
        }
        super.keyPressed(e);
    }
}
