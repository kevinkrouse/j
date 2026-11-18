/*  
 * VerticalScrollBar.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JScrollBar;
import javax.swing.JToolTip;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;

public final class VerticalScrollBar extends JScrollBar {
    private Editor editor;
    private Display display;
    private ScrollMarks marks;
    private ScrollMarks colorsFor;
    private Color colorsTrack;
    private Color[] colors;
    // A press on a mark went there: its release and drags are not the track's.
    private boolean swallowing;
    private boolean hand;

    public VerticalScrollBar(Editor editor) {
        super();
        this.editor = editor;
        display = editor.getDisplay();
        setToolTipText("");
    }

    @Override
    public final int getUnitIncrement(int direction) {
        return Display.getCharHeight() * editor.getBuffer().getIntegerProperty(Property.VERTICAL_SCROLL_INCREMENT);
    }

    @Override
    public final int getBlockIncrement(int direction) {
        return display.getHeight();
    }

    // Over the thumb too; the arrow buttons, children, paint after.
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (refresh()) {
            // Swing repaints only the thumb when the model changes.
            final Rectangle clip = g.getClipBounds();
            if (clip != null && !clip.contains(0, 0, getWidth(), getHeight()))
                repaint();
        }
        if (marks == null)
            return;
        final Rectangle track = trackBounds();
        if (track.isEmpty())
            return;
        marks.paint(g, track, display.getHeight(), !editor.isScrollBarLeft(), colors());
    }

    // Whether the marks were made again.
    private boolean refresh() {
        final ScrollMarks last = marks;
        marks = ScrollMarks.of(editor, last);
        return marks != last;
    }

    /** Repaints the whole bar if its marks would be made again. */
    void repaintIfMarksStale() {
        if (ScrollMarks.stale(editor, marks))
            repaint();
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        final List<ScrollMarks.Mark> at = marksAt(e);
        return at.isEmpty() ? null : ScrollMarks.toolTip(at, 6);
    }

    // Beside the bar, over the text: at the pointer it would be off the
    // frame's edge, and over the marks near the one it names.
    @Override
    public Point getToolTipLocation(MouseEvent e) {
        final String text = getToolTipText(e);
        if (text == null)
            return null;
        final JToolTip tip = createToolTip();
        tip.setTipText(text);
        final Dimension size = tip.getPreferredSize();
        final int gap = UIScale.scale(4);
        return new Point(editor.isScrollBarLeft() ? getWidth() + gap : -size.width - gap, e.getY() - size.height / 2);
    }

    // A press on a mark goes there instead of paging, except on the thumb,
    // which drags: its marks' lines are already in the window.
    @Override
    protected void processMouseEvent(MouseEvent e) {
        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED -> {
                swallowing = false;
                if (SwingUtilities.isLeftMouseButton(e) && !inThumb(e)) {
                    final List<ScrollMarks.Mark> at = marksAt(e);
                    if (!at.isEmpty()) {
                        swallowing = true;
                        ToolTipManager.sharedInstance().mousePressed(e);
                        jump(at.get(0));
                        return;
                    }
                }
            }
            case MouseEvent.MOUSE_RELEASED -> {
                if (swallowing) {
                    swallowing = false;
                    return;
                }
            }
            default -> {}
        }
        super.processMouseEvent(e);
    }

    @Override
    protected void processMouseMotionEvent(MouseEvent e) {
        if (e.getID() == MouseEvent.MOUSE_DRAGGED && swallowing)
            return;
        if (e.getID() == MouseEvent.MOUSE_MOVED)
            setHand(!inThumb(e) && !marksAt(e).isEmpty());
        super.processMouseMotionEvent(e);
    }

    // Component.setCursor asks the window system each time, even unchanged.
    private void setHand(boolean on) {
        if (on != hand) {
            hand = on;
            setCursor(on ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
        }
    }

    private List<ScrollMarks.Mark> marksAt(MouseEvent e) {
        if (refresh())
            repaint();
        if (marks == null)
            return List.of();
        final Rectangle track = trackBounds();
        if (!track.contains(e.getX(), e.getY()))
            return List.of();
        return marks.near(e.getY(), UIScale.scale(2), track, display.getHeight(), !editor.isScrollBarLeft());
    }

    // Other looks and feels' thumbs as BasicScrollBarUI lays them out.
    private boolean inThumb(MouseEvent e) {
        if (getUI() instanceof ScrollBarUI ui)
            return ui.thumbBounds().contains(e.getX(), e.getY());
        final Rectangle track = trackBounds();
        final long range = (long) getMaximum() - getMinimum();
        if (range <= 0)
            return true;
        final Dimension min = UIManager.getDimension("ScrollBar.minimumThumbSize");
        final int height = Math.min(
            track.height,
            Math.max((int) ((long) getVisibleAmount() * track.height / range), min != null ? min.height : 8));
        final long movable = range - getVisibleAmount();
        final int top = track.y
                + (movable <= 0 ? 0 : (int) ((long) (track.height - height) * (getValue() - getMinimum()) / movable));
        return e.getY() >= top && e.getY() < top + height;
    }

    private void jump(ScrollMarks.Mark m) {
        setHand(false);
        editor.getDispatcher().clickedIn();
        // A line replaced since the marks were made has nowhere to go.
        if (editor.getBuffer().contains(m.line())) {
            editor.jumpToLine(m.line(), m.offset());
            editor.updateDisplay();
        }
        editor.setFocusToDisplay();
    }

    private Color[] colors() {
        final Color fromUI = UIManager.getColor("ScrollBar.track");
        final Color track = fromUI != null ? fromUI : getBackground();
        if (colors == null || colorsFor != marks || !track.equals(colorsTrack)) {
            colors = ScrollMarks.colors(editor, track);
            colorsFor = marks;
            colorsTrack = track;
        }
        return colors;
    }

    // Other looks and feels' tracks lie between their arrow buttons.
    Rectangle trackBounds() {
        if (getUI() instanceof ScrollBarUI ui)
            return ui.trackBounds();
        final Insets insets = getInsets();
        int top = insets.top;
        int bottom = getHeight() - insets.bottom;
        for (Component c : getComponents()) {
            if (!(c instanceof JButton) || !c.isVisible())
                continue;
            if (c.getY() + c.getHeight() / 2 < getHeight() / 2)
                top = Math.max(top, c.getY() + c.getHeight());
            else
                bottom = Math.min(bottom, c.getY());
        }
        return new Rectangle(insets.left, top, getWidth() - insets.left - insets.right, Math.max(0, bottom - top));
    }
}
