/*
 * CompletionPopup.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import org.armedbear.j.util.Keys;

/**
 * A list under a text field, in its window's popup layer. The field keeps
 * the focus: its handler moves the selection and accepts it.
 */
public final class CompletionPopup<T> {
    private final JTextField field;
    private final Model<T> model = new Model<>();
    private final JList<T> list = new JList<>(model);
    private final JScrollPane scroller;
    private final int maxRows;
    private Consumer<T> onClick;
    private Runnable onDismiss;
    private boolean showing;

    // On the field only while showing, so popups that come and go don't pile up listeners on it.
    private final FocusAdapter focusListener = new FocusAdapter() {
        @Override
        public void focusLost(FocusEvent e) {
            if (!e.isTemporary())
                dismiss();
        }
    };
    private final ComponentAdapter componentListener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            place();
        }
    };
    private final AncestorListener ancestorListener = new AncestorListener() {
        @Override
        public void ancestorAdded(AncestorEvent e) {}

        @Override
        public void ancestorRemoved(AncestorEvent e) {
            dismiss();
        }

        @Override
        public void ancestorMoved(AncestorEvent e) {
            place();
        }
    };

    public CompletionPopup(JTextField field, int maxRows) {
        this.field = field;
        this.maxRows = maxRows;
        list.setFont(field.getFont());
        list.setFocusable(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int index = rowAt(e);
                if (
                    index >= 0
                        && Keys.isUnmodified(e)
                        &&
                        (e.getButton() == MouseEvent.BUTTON1 || e.getButton() == MouseEvent.BUTTON2)
                )
                    list.setSelectedIndex(index);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int index = rowAt(e);
                if (index >= 0 && onClick != null && Keys.isUnmodified(e) && e.getButton() == MouseEvent.BUTTON1)
                    onClick.accept(model.items.get(index));
            }
        });
        scroller = new JScrollPane(
            list,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        );
        Color border = UIManager.getColor("Component.borderColor");
        scroller.setBorder(BorderFactory.createLineBorder(border != null ? border : Color.GRAY));
    }

    private int rowAt(MouseEvent e) {
        int index = list.locationToIndex(e.getPoint());
        return index >= 0 && list.getCellBounds(index, index).contains(e.getPoint()) ? index : -1;
    }

    public void setCellRenderer(ListCellRenderer<? super T> renderer) {
        list.setCellRenderer(renderer);
    }

    /** Called with an item the user clicks. */
    public void setOnClick(Consumer<T> onClick) {
        this.onClick = onClick;
    }

    /** Called when the popup hides itself, as when the field loses the focus. */
    public void setOnDismiss(Runnable onDismiss) {
        this.onDismiss = onDismiss;
    }

    /**
     * Shows items, selecting the one at selected. Hides if there are none;
     * false if nothing is showing.
     */
    public boolean show(List<T> items, int selected) {
        if (items.isEmpty()) {
            hide();
            return false;
        }
        if (!showing) {
            JRootPane rootPane = SwingUtilities.getRootPane(field);
            if (rootPane == null)
                return false;
            rootPane.getLayeredPane().add(scroller, JLayeredPane.POPUP_LAYER);
            field.addFocusListener(focusListener);
            field.addComponentListener(componentListener);
            field.addAncestorListener(ancestorListener);
            showing = true;
        }
        model.set(items);
        int index = Math.max(0, Math.min(selected, items.size() - 1));
        list.setSelectedIndex(index);
        list.setVisibleRowCount(Math.min(items.size(), maxRows));
        place();
        list.ensureIndexIsVisible(index);
        return true;
    }

    public void hide() {
        if (!showing)
            return;
        showing = false;
        field.removeFocusListener(focusListener);
        field.removeComponentListener(componentListener);
        field.removeAncestorListener(ancestorListener);
        Container parent = scroller.getParent();
        if (parent != null) {
            Rectangle bounds = scroller.getBounds();
            parent.remove(scroller);
            parent.repaint(bounds.x, bounds.y, bounds.width, bounds.height);
        }
        model.set(List.of());
    }

    private void dismiss() {
        if (!showing)
            return;
        hide();
        if (onDismiss != null)
            onDismiss.run();
    }

    public boolean isShowing() {
        return showing;
    }

    public int size() {
        return model.items.size();
    }

    public int getSelectedIndex() {
        return list.getSelectedIndex();
    }

    public T getSelected() {
        int index = list.getSelectedIndex();
        return index >= 0 && index < model.items.size() ? model.items.get(index) : null;
    }

    /** Moves the selection by delta, wrapping or stopping at the ends. False if it didn't move. */
    public boolean move(int delta, boolean wrap) {
        final int count = model.items.size();
        if (count == 0)
            return false;
        int index = list.getSelectedIndex();
        int i = index + delta;
        if (wrap)
            i = Math.floorMod(i, count);
        else
            i = Math.max(0, Math.min(count - 1, i));
        if (i == index)
            return false;
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
        return true;
    }

    /** Moves the selection by a page, up for a negative direction. */
    public boolean page(int direction) {
        int rows = Math.max(1, list.getVisibleRowCount() - 1);
        return move(direction < 0 ? -rows : rows, false);
    }

    // At least as wide as the field, wider for long items, within the window.
    private void place() {
        Container parent = scroller.getParent();
        if (!showing || parent == null)
            return;
        Point p = SwingUtilities.convertPoint(field, 0, field.getHeight(), parent);
        Dimension preferred = scroller.getPreferredSize();
        int width = Math.min(Math.max(field.getWidth(), preferred.width), parent.getWidth() - p.x);
        int height = Math.min(preferred.height, parent.getHeight() - p.y);
        scroller.setBounds(p.x, p.y, Math.max(width, 0), Math.max(height, 0));
        scroller.revalidate();
        scroller.repaint();
    }

    private static final class Model<T> extends AbstractListModel<T> {
        private List<T> items = List.of();

        void set(List<T> newItems) {
            int old = items.size();
            if (old > 0) {
                items = List.of();
                fireIntervalRemoved(this, 0, old - 1);
            }
            items = Collections.unmodifiableList(newItems);
            if (!items.isEmpty())
                fireIntervalAdded(this, 0, items.size() - 1);
        }

        @Override
        public int getSize() {
            return items.size();
        }

        @Override
        public T getElementAt(int index) {
            return items.get(index);
        }
    }
}
