/*
 * Display.java
 *
 * Copyright (C) 1998-2005 Peter Graves
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

import org.armedbear.j.util.Utilities;
import org.armedbear.j.mode.image.ImageBuffer;
import org.armedbear.j.mode.image.ImageLine;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseEvent;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public final class Display extends JComponent implements Constants,
    ActionListener, FocusListener
{
    private static final int MAX_LINE_NUMBER_CHARS = 6;

    private static final Preferences preferences = Editor.preferences();

    private static Font plainFont;
    private static Font boldFont;
    private static Font italicFont;

    private static int charHeight;
    private static int charDescent;
    private static int charAscent;
    private static int charLeading;
    private static int charWidth;
    private static int spaceWidth; // Width of a space character.
    private static int minCharWidth;

    private static boolean antialias;
    private static Object desktopAntialiasingHint;
    private static boolean underlineBold;
    private static boolean emulateBold;
    private static boolean ligatures;

    private static int changeMarkWidth;

    private static Font gutterFont;
    private static int gutterCharWidth;

    private final HashMap<Line, Line> changedLines = new HashMap<Line, Line>();

    private final Editor editor;

    private Line topLine;
    private int pixelsAboveTopLine;

    // The offset of the first visible column of the display.
    int shift = 0;

    // The column containing the caret, relative to the first visible column
    // of the display. The absolute column number will be different if we're
    // horizontally scrolled (absolute column number = caretCol + shift).
    int caretCol;

    private char[] textArray;
    private int[] formatArray;

    private int updateFlag;

    // These are set in initializePaint().
    private boolean showChangeMarks;
    private boolean enableChangeMarks;
    private boolean showLineNumbers;
    private int gutterWidth;
    private Color changeColor;
    private Color savedChangeColor;
    private Color lineNumberColor;
    private int verticalRuleX;
    private Color verticalRuleColor;
    private Color gutterBorderColor;
    private boolean highlightBrackets;
    private boolean highlightMatchingBracket;
    private Position posBracket;
    private Position posMatch;

    private Timer timer;
    private boolean caretVisible = true;

    public Display(Editor editor)
    {
        this.editor = editor;
        if (plainFont == null)
            initializeStaticValues();
        initialize();
        setFocusTraversalKeysEnabled(false);
        setToolTipText("");
    }

    public static void initializeStaticValues()
    {
        // Preferences may have been reloaded, and uiScale is one of them.
        UIScale.reset();

        final String fontName = preferences.getStringProperty(Property.FONT_NAME);
        final int fontSize = UIScale.scaledProperty(preferences, Property.FONT_SIZE);

        plainFont = new Font(fontName, Font.PLAIN, fontSize);
        boldFont = new Font(fontName, Font.BOLD, fontSize);
        if (preferences.getBooleanProperty(Property.ENABLE_ITALICS))
            italicFont = new Font(fontName, Font.ITALIC, fontSize);
        else
            italicFont = plainFont;

        FontMetrics fm = Utilities.getFontMetrics(plainFont);

        final int plainAscent = fm.getAscent();
        final int plainDescent = fm.getDescent();
        final int plainLeading = fm.getLeading();

        charWidth = fm.charWidth('a');
        spaceWidth = fm.charWidth(' ');
        minCharWidth = getMinCharWidth(fm);

        fm = Utilities.getFontMetrics(boldFont);

        final int boldAscent = fm.getAscent();
        final int boldDescent = fm.getDescent();
        final int boldLeading = fm.getLeading();

        charAscent = plainAscent;
        if (boldAscent > charAscent)
            charAscent = boldAscent;

        charDescent = plainDescent;
        if (boldDescent > charDescent)
            charDescent = boldDescent;

        // Use no more than 1 pixel of leading.
        charLeading = (plainLeading > 0 || boldLeading > 0) ? 1 : 0;

        // Apply user-specified adjustments.
        final int adjustAscent = preferences.getIntegerProperty(Property.ADJUST_ASCENT);
        final int adjustDescent = preferences.getIntegerProperty(Property.ADJUST_DESCENT);
        final int adjustLeading = preferences.getIntegerProperty(Property.ADJUST_LEADING);

        if (charAscent + adjustAscent >= 0)
            charAscent += adjustAscent;
        if (charDescent + adjustDescent >= 0)
            charDescent += adjustDescent;
        if (charLeading + adjustLeading >= 0)
            charLeading += adjustLeading;

        charHeight = charAscent + charDescent + charLeading;

        antialias = preferences.getBooleanProperty(Property.ANTIALIAS);
        desktopAntialiasingHint = getDesktopAntialiasingHint();
        underlineBold = preferences.getBooleanProperty(Property.UNDERLINE_BOLD);
        emulateBold = preferences.getBooleanProperty(Property.EMULATE_BOLD);
        ligatures = resolveLigatures(
            preferences.getStringProperty(Property.LIGATURES), plainFont);

        String gutterFontName = preferences.getStringProperty(Property.GUTTER_FONT_NAME);
        if (gutterFontName == null)
            gutterFontName = fontName;
        int gutterFontSize = UIScale.scaledProperty(preferences, Property.GUTTER_FONT_SIZE);
        if (gutterFontSize == 0)
            gutterFontSize = fontSize;
        gutterFont = new Font(gutterFontName, Font.PLAIN, gutterFontSize);

        fm = Utilities.getFontMetrics(gutterFont);
        gutterCharWidth = fm.charWidth('0');

        changeMarkWidth =
            UIScale.scaledProperty(preferences, Property.CHANGE_MARK_WIDTH);
    }

    public synchronized void initialize()
    {
        // Explicitly set this to null here. We might be resetting the display.
        paintLineImage = null;

        // Allocate text and format arrays big enough to handle full screen
        // width for narrowest character in font, plus some slack (runs of
        // italics tend to get compressed). An extra 25% should be plenty.
        // Toolkit.getScreenSize() throws HeadlessException when there is no
        // display. Any plausible width will do here; these arrays are only a
        // scratch buffer for formatting one line.
        final int screenWidth =
            GraphicsEnvironment.isHeadless() ? 1920
            : Toolkit.getDefaultToolkit().getScreenSize().width;
        int size = screenWidth * 5 / (minCharWidth * 4);
        textArray = new char[size];
        formatArray = new int[size];

        if (preferences.getBooleanProperty(Property.BLINK_CARET)) {
            if (timer == null) {
                timer = new Timer(500, this);
                addFocusListener(this);
            }
        } else {
            if (timer != null) {
                timer.stop();
                timer = null;
                removeFocusListener(this);
                setCaretVisible(true);
            }
        }
    }

    private static final int getMinCharWidth(FontMetrics fm)
    {
        int minWidth = Integer.MAX_VALUE;
        int[] widths = fm.getWidths();
        int limit = widths.length > 0x7e ? 0x7e : widths.length;
        for (int i = limit; i >= 32; i--)
            if (widths[i] != 0 && widths[i] < minWidth)
                minWidth = widths[i];
        return minWidth;
    }

    /**
     * Digraphs that programming fonts ligate. Any one of them is enough to
     * conclude the font has contextual substitutions worth shaping for.
     */
    private static final String[] LIGATURE_PROBES =
        { "->", "=>", "==", "!=", "<=", ">=", "..." };

    /**
     * Whether to shape text with {@link Font#layoutGlyphVector}.
     *
     * <p>"true" and "false" say so outright. "auto", the default, probes the
     * font, so that the cost of shaping is paid only by a font that has
     * something to show for it.
     */
    private static boolean resolveLigatures(String pref, Font font)
    {
        boolean enabled;
        String reason;
        if (pref == null || pref.equalsIgnoreCase("auto")) {
            enabled = fontHasLigatures(font);
            reason = "auto";
        } else if (pref.equalsIgnoreCase("true") || pref.equalsIgnoreCase("yes")
                   || pref.equalsIgnoreCase("on")) {
            enabled = true;
            reason = "ligatures = " + pref;
        } else if (pref.equalsIgnoreCase("false") || pref.equalsIgnoreCase("no")
                   || pref.equalsIgnoreCase("off")) {
            enabled = false;
            reason = "ligatures = " + pref;
        } else {
            Log.warn("unrecognized value for ligatures: \"" + pref +
                     "\" (expected auto, true or false)");
            enabled = fontHasLigatures(font);
            reason = "auto";
        }
        Log.debug("ligatures " + (enabled ? "enabled" : "disabled") + " for " +
                  font.getFamily() + " (" + reason + ")");
        return enabled;
    }

    /**
     * True if the font applies contextual substitutions to any of
     * {@link #LIGATURE_PROBES} -- that is, if shaping a digraph yields
     * something other than its characters shaped one at a time.
     *
     * <p>Programming fonts (CaskaydiaCove, Fira Code, JetBrains Mono, Cascadia
     * Code) do; Monospaced, Courier and the plain monospaced faces do not.
     */
    static boolean fontHasLigatures(Font font)
    {
        // The same FontRenderContext the painting will use, so that detection
        // can never disagree with what gets drawn.
        FontRenderContext frc =
            Utilities.getFontMetrics(font).getFontRenderContext();
        for (String probe : LIGATURE_PROBES) {
            char[] chars = probe.toCharArray();
            GlyphVector shaped =
                font.layoutGlyphVector(frc, chars, 0, chars.length,
                                       Font.LAYOUT_LEFT_TO_RIGHT);
            if (shaped.getNumGlyphs() != chars.length)
                return true;
            for (int i = 0; i < chars.length; i++) {
                GlyphVector alone =
                    font.layoutGlyphVector(frc, chars, i, i + 1,
                                           Font.LAYOUT_LEFT_TO_RIGHT);
                if (alone.getNumGlyphs() != 1 ||
                    alone.getGlyphCode(0) != shaped.getGlyphCode(i))
                    return true;
            }
        }
        return false;
    }

    public static final int getCharHeight()
    {
        return charHeight;
    }

    public static final int getCharWidth()
    {
        return charWidth;
    }

    public static final int getImageBorderHeight()
    {
        return 5;
    }

    public static final int getImageBorderWidth()
    {
        return 5;
    }

    public final Line getTopLine()
    {
        return topLine;
    }

    public final int getTopLineNumber()
    {
        return topLine.lineNumber();
    }

    public final void setTopLine(Line line)
    {
        topLine = line;
        pixelsAboveTopLine = 0;
    }

    public final int getPixelsAboveTopLine()
    {
        return pixelsAboveTopLine;
    }

    public final void setPixelsAboveTopLine(int pixels)
    {
        pixelsAboveTopLine = pixels;
    }

    public final int getShift()
    {
        return shift;
    }

    public final void setShift(int n)
    {
        this.shift = n;
    }

    public final int getCaretCol()
    {
        return caretCol;
    }

    public final void setCaretCol(int n)
    {
        caretCol = n;
    }

    public int getAbsoluteCaretCol()
    {
        return caretCol + shift;
    }

    public void setAbsoluteCaretCol(int col)
    {
        caretCol = col - shift;
    }

    public final void repaintNow()
    {
        paintImmediately(0, 0, getWidth(), getHeight());
    }

    public synchronized void repaintChangedLines()
    {
        if (!Editor.displayReady())
            return;
        if ((updateFlag & REPAINT) == REPAINT) {
            repaint();
            return;
        }
        if (changedLines.isEmpty())
            return;
        final Buffer buffer = editor.getBuffer();
        initializePaint();
        try {
            buffer.lockRead();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            Graphics2D g2d = (Graphics2D) getGraphics();
            if (g2d != null) {
                Line line = topLine;
                int y = - pixelsAboveTopLine;
                final int limit = getHeight();
                while (line != null && y < limit) {
                    if (changedLines.containsKey(line))
                        paintLine(line, g2d, y);
                    y += line.getHeight();
                    line = line.nextVisible();
                }
                if (y < limit) {
                    g2d.setColor(editor.getFormatter().getBackgroundColor());
                    final int height = limit - y;
                    g2d.fillRect(0, y, getWidth(), height);
                    if (showLineNumbers)
                        drawGutterBorder(g2d, y, height);
                    drawVerticalRule(g2d, y, height);
                }
                drawCaret(g2d);
            }
        }
        finally {
            buffer.unlockRead();
        }
        changedLines.clear();
    }

    // Set caret column to be where dot is.
    public void moveCaretToDotCol()
    {
        if (editor.getDot() == null)
            return;
        int absCol = editor.getDotCol();
        if (caretCol != absCol - shift) {
            caretCol = absCol - shift;
            lineChanged(editor.getDotLine());
        }
        setUpdateFlag(REFRAME);
    }

    // Assumes line is after topLine.
    private int getY(Line line)
    {
        int y = - pixelsAboveTopLine;
        int limit = getHeight();
        for (Line l = topLine; y < limit && l != null && l != line; l = l.nextVisible())
            y += l.getHeight();
        return y;
    }

    // Does NOT assume line is after topLine.
    private int getAbsoluteY(Line line)
    {
        int y = 0;
        for (Line l = editor.getBuffer().getFirstLine(); l != null && l != line; l = l.nextVisible())
            y += l.getHeight();
        return y;
    }

    private void initializePaint()
    {
        final Buffer buffer = editor.getBuffer();
        final Mode mode = editor.getMode();
        showChangeMarks = buffer.getBooleanProperty(Property.SHOW_CHANGE_MARKS);
        enableChangeMarks = showChangeMarks && !buffer.isNewFile();
        if (showChangeMarks) {
            changeColor = mode.getColorProperty(Property.COLOR_CHANGE);
            if (changeColor == null)
                changeColor = DefaultTheme.getColor("change");
            savedChangeColor = mode.getColorProperty(Property.COLOR_SAVED_CHANGE);
            if (savedChangeColor == null)
                savedChangeColor = DefaultTheme.getColor("savedChange");
        }
        showLineNumbers = buffer.getBooleanProperty(Property.SHOW_LINE_NUMBERS);
        gutterWidth = getGutterWidth(showChangeMarks, showLineNumbers);
        if (showLineNumbers) {
            lineNumberColor = mode.getColorProperty(Property.COLOR_LINE_NUMBER);
            if (lineNumberColor == null)
                lineNumberColor = DefaultTheme.getColor("lineNumber");
            gutterBorderColor = mode.getColorProperty(Property.COLOR_GUTTER_BORDER);
            if (gutterBorderColor == null)
                gutterBorderColor = DefaultTheme.getColor("gutterBorder");
        }
        int col = buffer.getIntegerProperty(Property.VERTICAL_RULE);
        if (col != 0) {
            verticalRuleX = gutterWidth + (col - shift) * charWidth - 1;
            verticalRuleColor = mode.getColorProperty(Property.COLOR_VERTICAL_RULE);
            if (verticalRuleColor == null)
                verticalRuleColor = DefaultTheme.getColor("verticalRule");
        } else
            verticalRuleX = 0;
        highlightBrackets =
            buffer.getBooleanProperty(Property.HIGHLIGHT_BRACKETS);
        highlightMatchingBracket = highlightBrackets ||
            buffer.getBooleanProperty(Property.HIGHLIGHT_MATCHING_BRACKET);

        if (highlightMatchingBracket) {
            Position oldPosMatch = posMatch;
            posBracket = null;
            posMatch = null;
            if (editor.getDot() != null) {
                Position dot = editor.getDotCopy();
                char c = dot.getChar();
                if (c == '{' || c == '[' || c == '(') {
                    posBracket = dot;
                    posMatch = editor.findMatchInternal(dot, 200);
                } else if (dot.getOffset() > 0) {
                    int end = editor.getBuffer().getCol(dot.getLine(),
                        dot.getLine().length());
                    if (shift + caretCol <= end) {
                        dot.skip(-1);
                        c = dot.getChar();
                        if (c == '}' || c == ']' || c == ')') {
                            posBracket = dot;
                            posMatch = editor.findMatchInternal(dot, 200);
                        }
                    }
                }
            }
            if (oldPosMatch != null && oldPosMatch != posMatch)
                lineChanged(oldPosMatch.getLine());
            if (posMatch != null && posMatch != oldPosMatch)
                lineChanged(posMatch.getLine());
            if (!highlightBrackets)
                posBracket = null;
        }
    }

    private void drawVerticalRule(Graphics g, int y, int height)
    {
        if (verticalRuleX > gutterWidth) {
            g.setColor(verticalRuleColor);
            g.drawLine(verticalRuleX, y, verticalRuleX, y + height);
        }
    }

    private Position dragCaretPos;

    public void setDragCaretPos(Position pos)
    {
        if (dragCaretPos != null)
            lineChanged(dragCaretPos.getLine());
        dragCaretPos = pos;
        if (pos != null)
            lineChanged(pos.getLine());
    }

    private int dragCaretCol;

    public void setDragCaretCol(int col)
    {
        dragCaretCol = col;
    }

    // Called only from synchronized methods.
    private void drawDragCaret()
    {
        if (dragCaretPos == null)
            return;
        if (topLine == null)
            return;
        final Line line = dragCaretPos.getLine();
        if (line.lineNumber() < topLine.lineNumber())
            return;
        final int absCol;
        if (editor.getBuffer().getBooleanProperty(Property.RESTRICT_CARET))
            absCol = editor.getBuffer().getCol(dragCaretPos);
        else
            absCol = dragCaretCol; // We can go beyond the end of the line.
        if (absCol < shift)
            return;
        final int col = absCol - shift;
        formatLine(line, shift, col);
        Graphics2D g2d = (Graphics2D) getGraphics();
        final int x =
            gutterWidth + measureLine(g2d, textArray, col, formatArray);
        final int y = getY(line);
        g2d.setColor(editor.getFormatter().getCaretColor());
        g2d.fillRect(x, y, 1, charAscent + charDescent);
    }

    /**
     * The line the caret was last drawn on.
     *
     * A block caret fills a character cell, so leaving it behind is plainly
     * visible in a way a two pixel bar was not. j repaints by line and expects
     * whoever moved the caret to mark the line it left, which every caller
     * would now have to remember to do. Tracking it here instead makes the
     * caret clean up after itself wherever it is moved from.
     */
    private Line caretLine;

    // Called only from synchronized methods.
    private void drawCaret(Graphics2D g2d)
    {
        if (dragCaretPos != null) {
            drawDragCaret();
            return;
        }

        if (!caretVisible)
            return;
        if (topLine == null)
            return;
        if (editor != Editor.currentEditor())
            return;
        if (editor.getDot() == null)
            return;
        final InputHandler.CaretShape caretShape = caretShape();
        // A thin caret between two characters disappears into a selection, so
        // it is not drawn over one. A block caret is the cursor itself and has
        // to stay visible: visual mode is the selection plus where you are in
        // it.
        if (caretShape == InputHandler.CaretShape.BAR
            && editor.getMark() != null
            && !editor.getMark().equals(editor.getDot()))
            return;
        if (caretCol < 0)
            return;
        // An editor with no frame is always focused: there is no window to
        // lose focus to. This is also what lets a test paint one and look at
        // the pixels.
        final Frame frame = editor.getFrame();
        if (frame != null
            && (!frame.isActive() || frame.getFocusedComponent() != this))
            return;
        final Line dotLine = editor.getDotLine();
        if (dotLine instanceof ImageLine)
            return;
        if (editor.getBuffer().needsRenumbering())
            editor.getBuffer().renumber();
        if (dotLine.lineNumber() < topLine.lineNumber())
            return;

        int x;
        if (caretCol == 0)
            x = gutterWidth;
        else if (dotLine.length() == 0)
            x = gutterWidth + caretCol * spaceWidth;
        else {
            formatLine(dotLine, shift, caretCol);
            x = gutterWidth + measureLine(g2d, textArray, caretCol, formatArray);
        }

        if (x > getWidth())
            return;

        int y = getY(dotLine);
        if (y >= getHeight())
            return;

        eraseCaretFromOldLine(g2d, dotLine);

        g2d.setColor(editor.getFormatter().getCaretColor());

        final int height = charAscent + charDescent;
        switch (caretShape) {
            case BLOCK:
                g2d.fillRect(x, y, characterWidth(g2d, dotLine), height);
                drawCharacterInCaret(g2d, dotLine, x, y);
                break;
            case UNDERLINE:
                g2d.fillRect(x, y + height - 2, characterWidth(g2d, dotLine), 2);
                break;
            default:
                // Caret width is 2 pixel.
                g2d.fillRect(x-1, y, 2, height);
                break;
        }
    }

    /**
     * Repaints the line the caret has just left, if it is still on screen.
     *
     * Safe to call while painting: paintLine draws no caret, so this cannot
     * recurse.
     */
    private void eraseCaretFromOldLine(Graphics2D g2d, Line dotLine)
    {
        final Line previous = caretLine;
        caretLine = dotLine;
        if (previous == null || previous == dotLine)
            return;
        final int y = visibleY(previous);
        if (y >= 0)
            paintLine(previous, g2d, y);
    }

    /**
     * Where a line is on screen, or -1 if it is not on screen at all.
     *
     * Unlike getY this reports failure. The line the caret came from may have
     * been removed from the buffer since -- undoing a delete replaces line
     * objects -- and an orphan would otherwise be given the y of wherever the
     * walk happened to stop, and painted there.
     */
    private int visibleY(Line target)
    {
        int y = - pixelsAboveTopLine;
        final int limit = getHeight();
        for (Line line = topLine; line != null && y < limit;
             line = line.nextVisible()) {
            if (line == target)
                return y;
            y += line.getHeight();
        }
        return -1;
    }

    /**
     * Redraws the character a block caret covers, in the background colour.
     *
     * A filled block hides the character underneath it, which is not what a
     * block cursor looks like anywhere else: a terminal draws the cell
     * inverted, so the character is still there to read.
     */
    private void drawCharacterInCaret(Graphics2D g2d, Line dotLine, int x, int y)
    {
        final int offset = editor.getDotOffset();
        if (dotLine.length() == 0 || offset >= dotLine.length())
            return;
        formatLine(dotLine, shift, caretCol + 1);
        if (caretCol < 0 || caretCol >= textArray.length)
            return;
        final char c = textArray[caretCol];
        if (c == ' ' || c == '\t')
            return;
        g2d.setColor(editor.getFormatter().getBackgroundColor());
        g2d.setFont(fontForFormat(formatArray[caretCol]));
        g2d.drawChars(textArray, caretCol, 1, x, y + charAscent);
    }

    private Font fontForFormat(int format)
    {
        switch (editor.getFormatter().getStyle(format)) {
            case Font.BOLD:
                return boldFont;
            case Font.ITALIC:
                return italicFont;
            case Font.PLAIN:
            default:
                return plainFont;
        }
    }

    private InputHandler.CaretShape caretShape()
    {
        final InputHandler handler = editor.getInputHandler();
        return handler == null ? InputHandler.CaretShape.BAR
                               : handler.getCaretShape();
    }

    /**
     * How wide the character under the caret is, for a caret that covers it.
     *
     * Measured rather than assumed: the font may be proportional, and a tab is
     * as wide as it needs to be. Past the end of the line there is no
     * character, so a space's worth is used.
     */
    private int characterWidth(Graphics2D g2d, Line dotLine)
    {
        final int offset = editor.getDotOffset();
        if (dotLine.length() == 0 || offset >= dotLine.length())
            return spaceWidth;
        formatLine(dotLine, shift, caretCol + 1);
        final int end = measureLine(g2d, textArray, caretCol + 1, formatArray);
        formatLine(dotLine, shift, caretCol);
        final int start = measureLine(g2d, textArray, caretCol, formatArray);
        final int width = end - start;
        return width > 0 ? width : spaceWidth;
    }

    public synchronized void setCaretVisible(boolean b)
    {
        caretVisible = b;
        if (b && timer != null && timer.isRunning())
            timer.restart();
    }

    private synchronized void blinkCaret()
    {
        Position dot = editor.getDot();
        if (dot != null) {
            caretVisible = !caretVisible;
            final Line line = dot.getLine();
            Runnable r = () -> {
                repaintLine(line);
            };
            SwingUtilities.invokeLater(r);
        }
    }

    private synchronized void repaintLine(Line l)
    {
        if (!Editor.displayReady())
            return;
        if ((updateFlag & REPAINT) == REPAINT) {
            repaint();
            return;
        }
        final Buffer buffer = editor.getBuffer();
        initializePaint();
        try {
            buffer.lockRead();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            Graphics2D g2d = (Graphics2D) getGraphics();
            if (g2d != null) {
                Line line = topLine;
                int y = - pixelsAboveTopLine;
                final int limit = getHeight();
                while (line != null && y < limit) {
                    if (line == l) {
                        paintLine(line, g2d, y);
                        break;
                    }
                    y += line.getHeight();
                    line = line.nextVisible();
                }
                drawCaret(g2d);
            }
        }
        finally {
            buffer.unlockRead();
        }
    }

    // Timer event handler.
    public void actionPerformed(ActionEvent e)
    {
        blinkCaret();
    }

    public synchronized void focusGained(FocusEvent e)
    {
        if (timer != null)
            timer.start();
    }

    public synchronized void focusLost(FocusEvent e)
    {
        if (timer != null)
            timer.stop();
    }

    private int formatLine(final Line line, final int begin, final int maxCols)
    {
        // Avoid getfield overhead.
        final int[] fa = formatArray;
        final char[] ta = textArray;
        final int taLength = ta.length;
        Debug.assertTrue(taLength == fa.length);
        for (int i = taLength; i-- > 0;) {
            ta[i] = ' ';
            fa[i] = 0;
        }
        final int limit = Math.min(maxCols, taLength);
        final LineSegmentList segmentList = editor.getFormatter().formatLine(line);
        int segmentStart = 0;
        int totalChars = 0;
        final int size = segmentList.size();
        for (int i = 0; i < size; i++) {
            final LineSegment segment = segmentList.getSegment(i);
            final int segmentLength = segment.length();
            if (segmentStart + segmentLength < begin) {
                segmentStart += segmentLength;
                continue;
            }
            final int maxAppend = limit - totalChars;
            if (segmentStart >= begin) {
                if (segmentLength <= maxAppend) {
                    segment.getChars(0, segmentLength, ta, totalChars);
                    totalChars += segmentLength;
                } else if (maxAppend > 0) {
                    segment.getChars(0, maxAppend, ta, totalChars);
                    totalChars += maxAppend;
                }
            } else {
                // segmentStart < begin && segmentStart + segmentLength >= begin
                String text = segment.substring(begin - segmentStart);
                if (text.length() <= maxAppend) {
                    text.getChars(0, text.length(), ta, totalChars);
                    totalChars += text.length();
                } else if (maxAppend > 0) {
                    text.getChars(0, maxAppend, ta, totalChars);
                    totalChars += maxAppend;
                }
            }
            final int format = segment.getFormat();
            int k = segmentStart - begin;
            if (k > limit)
                break;
            for (int j = 0; j < segmentLength; j++, k++) {
                if (k >= 0 && k < limit)
                    fa[k] = format;
            }
            segmentStart += segmentLength;
        }
        return totalChars;
    }

    private Image paintLineImage;
    private int paintLineImageWidth;
    private int paintLineImageHeight;
    private double paintLineImageScale;
    private Graphics2D paintLineGraphics;

    // How many device pixels the display gets per logical pixel. 1.0 unless
    // the JDK is scaling the UI for a high resolution display.
    private double getDeviceScale()
    {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc == null)
            return 1.0;
        AffineTransform t = gc.getDefaultTransform();
        if (t == null)
            return 1.0;
        return Math.max(t.getScaleX(), t.getScaleY());
    }

    private final void providePaintLineImage(int width, int height)
    {
        final double deviceScale = getDeviceScale();

        if (paintLineImage != null &&
            paintLineImageWidth == width &&
            paintLineImageHeight == height &&
            paintLineImageScale == deviceScale)
            return;

        // Otherwise...
        // Allocate the buffer in device pixels rather than logical ones. A
        // logical sized buffer is magnified on the way to a high resolution
        // screen, which would blur every line drawn through here -- and since
        // only the incremental repaints use this buffer, the text under the
        // caret would look softer than the rest of the screen. Scaling the
        // buffer's Graphics by the same factor lets all the drawing code below
        // go on working in logical coordinates.
        final int deviceWidth = (int) Math.ceil(width * deviceScale);
        final int deviceHeight = (int) Math.ceil(height * deviceScale);

        paintLineImage =
            new BufferedImage(Math.max(deviceWidth, 1), Math.max(deviceHeight, 1),
                              BufferedImage.TYPE_INT_RGB);
        paintLineImageWidth = width;
        paintLineImageHeight = height;
        paintLineImageScale = deviceScale;
        paintLineGraphics = (Graphics2D) paintLineImage.getGraphics();
        if (deviceScale != 1.0)
            paintLineGraphics.scale(deviceScale, deviceScale);

        setRenderingHints(paintLineGraphics);
    }

    private final void paintLine(Line line, Graphics2D g2d, int y)
    {
        if (line instanceof ImageLine)
            paintImageLine((ImageLine)line, g2d, y);
        else
            paintTextLine(line, g2d, y);
    }

    private synchronized void paintTextLine(Line line, Graphics g, int y)
    {
        int displayWidth = getWidth();
        int maxCols = getMaxCols();

        providePaintLineImage(displayWidth, charHeight);

        Color backgroundColor;
        if (line == getCurrentLine())
            backgroundColor = editor.getFormatter().getCurrentLineBackgroundColor();
        else
            backgroundColor = editor.getFormatter().getBackgroundColor();
        drawBackgroundForLine(paintLineGraphics, backgroundColor, line, 0);

        int totalChars = formatLine(line, shift, maxCols);

        final Region selection = selectionRegion();
        if (selection != null) {
            handleSelection(selection, line, formatArray, paintLineGraphics, 0);
        } else if (posMatch != null) {
            if (posMatch.getLine() == line)
                highlightBracket(posMatch, line, formatArray,
                    paintLineGraphics, 0);
            if (posBracket != null && posBracket.getLine() == line)
                highlightBracket(posBracket, line, formatArray,
                    paintLineGraphics, 0);
        }

        drawGutterText(paintLineGraphics, line, 0);
        if (showLineNumbers && editor.getDot() != null)
            drawGutterBorder(paintLineGraphics, 0, line.getHeight());
        drawVerticalRule(paintLineGraphics, 0, line.getHeight());
        drawText(paintLineGraphics, textArray, totalChars, formatArray, 0,
                 caretBreakCol(line, totalChars));
        changedLines.remove(line);

        g.drawImage(paintLineImage, 0, y,
                    paintLineImageWidth, paintLineImageHeight, null);
    }

    private void paintImageLine(ImageLine imageLine, Graphics g, int y)
    {
        final int displayWidth = getWidth();
        final int lineHeight = imageLine.getHeight();
        final int imageWidth = imageLine.getImageWidth();
        final int imageHeight = imageLine.getImageHeight();
        final int x = gutterWidth - shift * charWidth;

        Color backgroundColor;
        if (imageLine == getCurrentLine())
            backgroundColor = editor.getFormatter().getCurrentLineBackgroundColor();
        else
            backgroundColor = editor.getFormatter().getBackgroundColor();

        g.setColor(backgroundColor);
        // Left.
        g.fillRect(0, y, x, lineHeight);
        // Right.
        g.fillRect(x + imageWidth, y, displayWidth - (x + imageWidth), lineHeight);
        // Bottom.
        if (imageHeight < lineHeight)
            g.fillRect(0, y + imageHeight, displayWidth,
                lineHeight - imageHeight);

        Rectangle rect = imageLine.getRect();
        g.drawImage(imageLine.getImage(),
            x, y, x + rect.width, y + rect.height,
            rect.x, rect.y, rect.x + rect.width, rect.y + rect.height,
            null);
    }

    private void drawBackgroundForLine(Graphics2D g2d, Color backgroundColor,
        Line line, int y)
    {
        if (enableChangeMarks && line.isModified()) {
            g2d.setColor(line.isSaved() ? savedChangeColor : changeColor);
            g2d.fillRect(0, y, changeMarkWidth, line.getHeight());
            g2d.setColor(backgroundColor);
            g2d.fillRect(changeMarkWidth, y, getWidth() - changeMarkWidth, line.getHeight());
        } else {
            g2d.setColor(backgroundColor);
            g2d.fillRect(0, y, getWidth(), line.getHeight());
        }
    }

    private void drawGutterText(Graphics g, Line line, int y)
    {
        int x = showChangeMarks ? changeMarkWidth : 0;
        char c = 0;
        Annotation annotation = line.getAnnotation();
        if (annotation != null)
            c = annotation.getGutterChar();
        else if (line.next() != null && line.next().isHidden())
            c = '+';
        if (c != 0) {
            char[] chars = new char[1];
            chars[0] = c;
            g.setColor(editor.getFormatter().getColor(0)); // Default text color.
            g.setFont(plainFont);
            g.drawChars(chars, 0, 1, x, y + charAscent);
        }
        x += charWidth;
        if (showLineNumbers) {
            final String s = String.valueOf(line.lineNumber() + 1);
            final int pad = MAX_LINE_NUMBER_CHARS - s.length();
            if (pad >= 0) {
                x += pad * gutterCharWidth;
                g.setColor(lineNumberColor);
                g.setFont(gutterFont);
                g.drawString(s, x, y + charAscent);
            }
        }
    }

    private void drawGutterBorder(Graphics g)
    {
        int x = getGutterWidth(editor.getBuffer()) - 4;
        g.setColor(gutterBorderColor);
        g.drawLine(x, 0, x, getHeight());
    }

    private void drawGutterBorder(Graphics g, int y, int height)
    {
        int x = getGutterWidth(editor.getBuffer()) - 4;
        g.setColor(gutterBorderColor);
        g.drawLine(x, y, x, y + height);
    }

    // Returns width in pixels.
    public static final int getGutterWidth(Buffer buffer)
    {
        return getGutterWidth(buffer.getBooleanProperty(Property.SHOW_CHANGE_MARKS),
            buffer.getBooleanProperty(Property.SHOW_LINE_NUMBERS));
    }

    // Returns width in pixels.
    private static final int getGutterWidth(boolean showChangeMarks,
        boolean showLineNumbers)
    {
        int width = charWidth;
        if (showChangeMarks)
            width += changeMarkWidth;
        if (showLineNumbers)
            width += MAX_LINE_NUMBER_CHARS * gutterCharWidth + 5;
        return width;
    }

    /**
     * The column of the caret on this line, if a ligature there should be
     * broken apart so the character under the caret is legible, otherwise -1.
     *
     * <p>Deliberately independent of the blink state: a ligature that came and
     * went with the caret would flicker.
     */
    private int caretBreakCol(Line line, int totalChars)
    {
        if (!ligatures)
            return -1;
        if (line != editor.getDotLine())
            return -1;
        if (editor.getMark() != null)
            return -1;
        return (caretCol > 0 && caretCol < totalChars) ? caretCol : -1;
    }

    /**
     * Glyphs for textArray[start, limit).
     *
     * <p>createGlyphVector is a bare cmap lookup: no ligatures, no kerning.
     * layoutGlyphVector runs the font's OpenType tables through HarfBuzz,
     * which is what turns "->" into an arrow. Substitution is confined to
     * [start, limit), so splitting a run breaks the ligature at that point
     * without moving anything -- drawText relies on that to let the caret sit
     * inside a ligature.
     */
    private static GlyphVector glyphs(Font font, FontRenderContext frc,
        char[] textArray, int start, int limit)
    {
        if (ligatures)
            return font.layoutGlyphVector(frc, textArray, start, limit,
                                          Font.LAYOUT_LEFT_TO_RIGHT);
        char[] chars = new char[limit - start];
        System.arraycopy(textArray, start, chars, 0, limit - start);
        return font.createGlyphVector(frc, chars);
    }

    /**
     * @param breakCol a column at which to end a run even though the format
     *     has not changed, so that a ligature straddling it comes apart, or -1
     *     for none. Used to show the caret's own character inside a ligature.
     */
    private void drawText(Graphics2D g2d, char[] textArray, int length,
        int[] formatArray, int y, int breakCol)
    {
        int i = 0;
        double x = gutterWidth;
        final Formatter formatter = editor.getFormatter();
        while (i < length) {
            int format = formatArray[i];
            int start = i;
            ++i;
            while (i < length && formatArray[i] == format && i != breakCol)
                ++i;
            g2d.setColor(formatter.getColor(format));
            int style = formatter.getStyle(format);
            Font font;
            switch (style) {
                case Font.BOLD:
                    font = boldFont;
                    break;
                case Font.ITALIC:
                    font = italicFont;
                    break;
                case Font.PLAIN:
                default:
                    font = plainFont;
                    break;
            }
            GlyphVector gv = glyphs(font, g2d.getFontRenderContext(),
                                    textArray, start, i);
            final double width = gv.getLogicalBounds().getWidth();
            if (style == Font.BOLD) {
                if (boldFont == plainFont) {
                    if (underlineBold)
                        g2d.drawLine((int)x, y + charAscent + 1, (int)(x + width), y + charAscent + 1);
                    else
                        g2d.drawGlyphVector(gv, (int)x + 1, y + charAscent);
                } else if (emulateBold)
                    g2d.drawGlyphVector(gv, (float)x + 1, y + charAscent);
            }
            if (formatter.getUnderline(format))
                g2d.drawLine((int)x, y + charAscent + 1, (int)(x + width), y + charAscent + 1);
            g2d.drawGlyphVector(gv, (float)x, y + charAscent);
            x += width;
        }
    }

    private int measureLine(Graphics2D g2d, char[] textArray, int length, int[] formatArray)
    {
        if (length == 0)
            return 0;
        final int limit = Math.min(length, textArray.length);
        double totalWidth = 0;
        int i = 0;
        Formatter formatter = editor.getFormatter();
        while (i < limit) {
            int format = formatArray[i];
            int startCol = i;
            while (i < limit && formatArray[i] == format)
                ++i;
            Font font;
            switch (formatter.getStyle(format)) {
                case Font.BOLD:
                    font = boldFont;
                    break;
                case Font.ITALIC:
                    font = italicFont;
                    break;
                case Font.PLAIN:
                default:
                    font = plainFont;
                    break;
            }
            GlyphVector gv = glyphs(font, g2d.getFontRenderContext(),
                                    textArray, startCol, i);
            totalWidth += gv.getLogicalBounds().getWidth();
        }
        return (int) totalWidth;
    }

    public void paintComponent(Graphics g)
    {
        final Buffer buffer = editor.getBuffer();
        if (!Editor.displayReady()) {
            if (buffer != null && buffer.getModeId() == IMAGE_MODE)
                g.setColor(ImageBuffer.getDefaultBackgroundColor());
            else
                g.setColor(editor.getFormatter().getBackgroundColor());
            g.fillRect(0, 0, getWidth(), getHeight());
            return;
        }
        try {
            buffer.lockRead();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            if (buffer.getModeId() == IMAGE_MODE)
                paintImage(g);
            else
                paintComponentInternal(g);
        }
        finally {
            buffer.unlockRead();
        }
    }

    private void paintImage(Graphics g)
    {
        ImageBuffer ib = (ImageBuffer) editor.getBuffer();
        g.setColor(ib.getBackgroundColor());
        g.fillRect(0, 0, getWidth(), getHeight());
        Image image = ib.getImage();
        if (image != null) {
            int imageWidth = image.getWidth(null);
            int imageHeight = image.getHeight(null);
            int x = 0;
            int y = 0;
            if (imageWidth > 0 && imageHeight > 0) {
                if (imageWidth < getWidth())
                    x = (getWidth() - imageWidth) / 2;
                if (imageHeight < getHeight())
                    y = (getHeight() - imageHeight) / 2;
            }
            if (x < getImageBorderWidth())
                x = getImageBorderWidth();
            if (y < getImageBorderHeight())
                y = getImageBorderHeight();
            g.drawImage(image, x - shift * charWidth, y - pixelsAboveTopLine, this);
        }
    }

    private synchronized void paintComponentInternal(Graphics g)
    {
        initializePaint();
        Graphics2D g2d = (Graphics2D) g;
        setRenderingHints(g2d);
        final Rectangle clipBounds = g2d.getClipBounds();
        final int displayWidth = getWidth();
        final int maxCols = getMaxCols();

        // Selection.
        final Region r = selectionRegion();

        // Current line.
        final Line currentLine = getCurrentLine();

        final Color colorBackground = editor.getFormatter().getBackgroundColor();
        int y = - pixelsAboveTopLine;
        Line line = topLine;
        while (line != null && y + line.getHeight() < clipBounds.y) {
            y += line.getHeight();
            line = line.nextVisible();
        }
        final int limit = clipBounds.y + clipBounds.height;
        while (line != null && y < limit) {
            if (line instanceof ImageLine) {
                paintImageLine((ImageLine)line, g2d, y);
            } else {
                Color backgroundColor;
                if (line == currentLine) {
                    backgroundColor =
                        editor.getFormatter().getCurrentLineBackgroundColor();
                } else
                    backgroundColor = colorBackground;
                drawBackgroundForLine(g2d, backgroundColor, line, y);
                int totalChars = formatLine(line, shift, maxCols);
                if (r != null)
                    handleSelection(r, line, formatArray, g2d, y);
                else if (posMatch != null) {
                    if (posMatch.getLine() == line)
                        highlightBracket(posMatch, line, formatArray, g2d, y);
                    if (posBracket != null && posBracket.getLine() == line)
                        highlightBracket(posBracket, line, formatArray, g2d, y);
                }
                drawGutterText(g2d, line, y);
                if (totalChars > 0) {
                    // Draw vertical rule first so it will be behind the text.
                    drawVerticalRule(g2d, y, line.getHeight());
                    drawText(g2d, textArray, totalChars, formatArray, y,
                             caretBreakCol(line, totalChars));
                } else
                    drawVerticalRule(g2d, y, line.getHeight());
                changedLines.remove(line);
            }
            y += line.getHeight();
            line = line.nextVisible();
        }
        if (y < limit) {
            g2d.setColor(colorBackground);
            g2d.fillRect(0, y, displayWidth, limit - y);
            drawVerticalRule(g2d, y, limit - y);
        }
        drawCaret(g2d);
        if (showLineNumbers && editor.getDot() != null)
            drawGutterBorder(g2d);
        updateFlag &= ~REPAINT;
    }

    private Line getCurrentLine()
    {
        if (editor.getDot() != null && editor.getMark() == null)
            return editor.getDotLine();
        return null;
    }

    /**
     * The selection to paint, or null when there is none.
     *
     * Ordinarily that is mark to dot. A linewise selection is painted from the
     * start of its first line to the start of the line after its last, so that
     * the whole of both end lines is covered however far along them the caret
     * is.
     */
    private Region selectionRegion()
    {
        final Position mark = editor.getMark();
        if (mark == null)
            return null;
        final InputHandler handler = editor.getInputHandler();
        final Position dot = editor.getDot();
        if (handler == null || !handler.isLinewiseSelection() || dot == null)
            return new Region(editor);
        final boolean markFirst = mark.isBefore(dot);
        final Line first = markFirst ? mark.getLine() : dot.getLine();
        final Line last = markFirst ? dot.getLine() : mark.getLine();
        final Line after = last.nextVisible();
        return new Region(editor.getBuffer(), new Position(first, 0),
                          after != null ? new Position(after, 0)
                                        : new Position(last, last.length()));
    }

    private void handleSelection(Region r, Line line, int[] formatArray,
                                 Graphics2D g2d, int y)
    {
        if (r == null)
            return;

        int maxCols = getMaxCols();
        int fillWidth = 0;
        int beginCol = 0;
        int endCol = 0;
        int x = 0;

        if (r.isColumnRegion()) {
            if (line.lineNumber() >= r.getBeginLineNumber() &&
                line.lineNumber() <= r.getEndLineNumber()) {
                beginCol = r.getBeginCol() - shift;
                endCol = r.getEndCol() - shift;

                if (beginCol < 0)
                    beginCol = 0;
                if (endCol < 0)
                    endCol = 0;
                if (endCol > maxCols)
                    endCol = maxCols;

                int x1 = measureLine(g2d, textArray, beginCol, formatArray);
                int x2 = measureLine(g2d, textArray, endCol, formatArray);
                fillWidth = x2 - x1;
                x = gutterWidth + x1;
            }
        } else if (line == r.getBeginLine()) {
            beginCol = r.getBeginCol() - shift;
            if (line == r.getEndLine())
                endCol = r.getEndCol() - shift;

            if (beginCol < 0)
                beginCol = 0;
            if (endCol < 0)
                endCol = 0;
            if (endCol > maxCols)
                endCol = maxCols;

            if (line == r.getEndLine()) {
                int x1 = measureLine(g2d, textArray, beginCol, formatArray);
                int x2 = measureLine(g2d, textArray, endCol, formatArray);
                fillWidth = x2 - x1;
                x = gutterWidth + x1;
            } else {
                fillWidth = getWidth();
                x = gutterWidth + measureLine(g2d, textArray, beginCol, formatArray);
            }
        } else if (line.lineNumber() > r.getBeginLineNumber() &&
            line.lineNumber() < r.getEndLineNumber()) {
            // Entire line is selected.
            fillWidth = getWidth();
            x = gutterWidth;
        } else if (line == r.getEndLine()) {
            // Last line of selection that spans more than one line.
            endCol = r.getEndCol() - shift;

            if (endCol < 0)
                endCol = 0;
            if (endCol > maxCols)
                endCol = maxCols;

            int x1 = measureLine(g2d, textArray, beginCol, formatArray);
            int x2 = measureLine(g2d, textArray, endCol, formatArray);
            fillWidth = x2 - x1;
            x = gutterWidth + x1;
        }

        if (fillWidth > 0) {
            g2d.setColor(editor.getFormatter().getSelectionBackgroundColor());
            g2d.fillRect(x, y, fillWidth, charHeight);
        }
    }

    private void highlightBracket(Position pos, Line line, int[] formatArray,
        Graphics2D g2d, int y)
    {
        if (pos == null) {
            Debug.bug();
            return;
        }
        if (pos.getLine() != line) {
            Debug.bug();
            return;
        }
        if (editor.getFrame().getFocusedComponent() != this)
            return;
        int beginCol = editor.getBuffer().getCol(pos) - shift;
        if (beginCol < 0)
            return;
        int endCol = beginCol + 1;
        int x1 = measureLine(g2d, textArray, beginCol, formatArray);
        int x2 = measureLine(g2d, textArray, endCol, formatArray);
        int fillWidth = x2 - x1;
        int x = gutterWidth + x1;
        if (fillWidth > 0) {
            g2d.setColor(editor.getFormatter().getMatchingBracketBackgroundColor());
            g2d.fillRect(x, y, fillWidth, charHeight);
        }
    }

    // Scroll up in the buffer, moving the content in the window down.
    private void scrollUp()
    {
        if (editor.getModeId() == IMAGE_MODE) {
            if (pixelsAboveTopLine >= charHeight) {
                pixelsAboveTopLine -= charHeight;
                repaint();
            }
            return;
        }

        if (topLine != null) {
            if (topLine instanceof ImageLine) {
                if (pixelsAboveTopLine - charHeight >= 0) {
                    pixelsAboveTopLine -= charHeight;
                    scrollPixelsUp(charHeight);
                    Rectangle r = new Rectangle(0, 0, getWidth(), charHeight);
                    paintImmediately(r);
                    return;
                }
            }
            Line line = topLine.previousVisible();
            if (line == null)
                return;
            if (line instanceof ImageLine) {
                scrollPixelsUp(charHeight);
                topLine = line;
                pixelsAboveTopLine = line.getHeight() - charHeight;
                editor.update(topLine);
                return;
            }
            scrollPixelsUp(charHeight);
            setTopLine(line);
            editor.update(topLine);
        }
    }

    // Scroll down in the buffer, moving the content in the window up.
    private void scrollDown()
    {
        if (editor.getModeId() == IMAGE_MODE) {
            pixelsAboveTopLine += charHeight;
            repaint();
            return;
        }
        if (topLine != null) {
            if (topLine instanceof ImageLine) {
                if (pixelsAboveTopLine + charHeight < topLine.getHeight()) {
                    pixelsAboveTopLine += charHeight;
                    scrollPixelsDown(charHeight);
                    Rectangle r = new Rectangle();
                    r.x = 0;
                    r.y = getHeight() - charHeight;
                    r.width = getWidth();
                    r.height = charHeight;
                    paintImmediately(r);
                    return;
                }
            }
            Line line = topLine.nextVisible();
            if (line != null) {
                scrollPixelsDown(charHeight);
                setTopLine(line);
                Line bottomLine = getBottomLine();
                editor.update(bottomLine);
                Line nextVisible = bottomLine.nextVisible();
                if (nextVisible != null)
                    editor.update(nextVisible);
            }
        }
    }

    // Move content down.
    private void scrollPixelsUp(int dy)
    {
        if (!isShowing() || !editor.isShowing())
            return;
        Point pt1 = editor.getLocationOnScreen();
        Point pt2 = getLocationOnScreen();
        int x = pt2.x - pt1.x;
        int y = pt2.y - pt1.y;
        editor.getGraphics().copyArea(x, y, getWidth(), getHeight() - dy, 0, dy);
    }

    // Move content up.
    private void scrollPixelsDown(int dy)
    {
        if (!isShowing() || !editor.isShowing())
            return;
        Point pt1 = editor.getLocationOnScreen();
        Point pt2 = getLocationOnScreen();
        int x = pt2.x - pt1.x;
        int y = pt2.y - pt1.y + dy;
        editor.getGraphics().copyArea(x, y, getWidth(), getHeight() - dy, 0, - dy);
    }

    public Line getBottomLine()
    {
        Line line = topLine;
        int y = line.getHeight() - pixelsAboveTopLine;
        final int limit = getHeight();
        while (true) {
            Line next = line.nextVisible();
            if (next == null)
                break;
            y += next.getHeight();
            if (y > limit)
                break;
            line = next;
        }
        return line;
    }

    public void up(boolean select)
    {
        if (editor.getDot() == null)
            return;

        if (select) {
            if (editor.getMark() == null)
                editor.addUndo(SimpleEdit.MOVE);
            else if (editor.getLastCommand() != COMMAND_UP)
                editor.addUndo(SimpleEdit.MOVE);
        } else {
            if (editor.getMark() != null) {
                boolean isLineBlock =
                    (editor.getDotOffset() == 0 && editor.getMarkOffset() == 0);
                editor.addUndo(SimpleEdit.MOVE);
                editor.beginningOfBlock();
                editor.setGoalColumn(editor.getDotCol());
                if (isLineBlock)
                    return;
            } else if (editor.getLastCommand() != COMMAND_UP)
                editor.addUndo(SimpleEdit.MOVE);
        }

        Line dotLine = editor.getDotLine();
        Line prevLine = dotLine.previousVisible();
        if (prevLine == null)
            return;
        if (dotLine == topLine) {
            // Need to scroll.
            windowUp();
        }
        editor.updateDotLine();
        final Buffer buffer = editor.getBuffer();
        boolean selectLine = false;
        if (select && editor.getMark() == null) {
            Position savedDot = null;
            if (buffer.getBooleanProperty(Property.AUTO_SELECT_LINE)) {
                Line nextLine = editor.getDotLine().next();
                if (nextLine != null) {
                    selectLine = true;
                    savedDot = new Position(editor.getDot());
                    editor.getDot().moveTo(nextLine, 0);
                    caretCol = 0;
                }
            }
            if (selectLine) {
                // Make sure absMarkCol will be correct.
                int savedShift = shift;
                shift = 0;
                editor.setMarkAtDot();
                shift = savedShift;
            } else
                editor.setMarkAtDot();
            if (savedDot != null)
                editor.getSelection().setSavedDot(savedDot);
        }
        editor.getDot().setLine(prevLine);
        editor.updateDotLine();
        if (selectLine)
            editor.setGoalColumn(0);
        editor.moveDotToGoalCol();
    }

    public void down(boolean select)
    {
        if (editor.getDot() == null)
            return;
        if (select) {
            if (editor.getMark() == null)
                editor.addUndo(SimpleEdit.MOVE);
            else if (editor.getLastCommand() != COMMAND_DOWN)
                editor.addUndo(SimpleEdit.MOVE);
        } else {
            if (editor.getMark() != null) {
                boolean isLineBlock =
                    (editor.getDotOffset() == 0 && editor.getMarkOffset() == 0);
                editor.addUndo(SimpleEdit.MOVE);
                editor.endOfBlock();
                editor.setGoalColumn(editor.getDotCol());
                if (isLineBlock)
                    return;
            }
            else if (editor.getLastCommand() != COMMAND_DOWN)
                editor.addUndo(SimpleEdit.MOVE);
        }
        final Line dotLine = editor.getDotLine();
        final Line nextLine = dotLine.nextVisible();
        if (nextLine == null)
            return;
        if (getY(nextLine) + nextLine.getHeight() > getHeight()) {
            // Need to scroll.
            windowDown();
        }
        editor.updateDotLine();
        final Buffer buffer = editor.getBuffer();
        boolean selectLine = false;
        if (select && editor.getMark() == null) {
            Position savedDot = null;
            if (buffer.getBooleanProperty(Property.AUTO_SELECT_LINE)) {
                selectLine = true;
                savedDot = new Position(editor.getDot());
                editor.getDot().setOffset(0);
                caretCol = 0;
            }
            if (selectLine) {
                // Make sure absMarkCol will be correct.
                int savedShift = shift;
                shift = 0;
                editor.setMarkAtDot();
                shift = savedShift;
            } else
                editor.setMarkAtDot();
            if (savedDot != null)
                editor.getSelection().setSavedDot(savedDot);
        }
        editor.getDot().setLine(nextLine);
        editor.updateDotLine();
        if (selectLine)
            editor.setGoalColumn(0);
        editor.moveDotToGoalCol();
    }

    public void windowUp()
    {
        if (getHeight() < editor.getBuffer().getDisplayHeight())
            scrollUp();
    }

    public void windowDown()
    {
        final int totalHeight = editor.getBuffer().getDisplayHeight();
        final int windowHeight = getHeight();
        if (windowHeight < totalHeight) {
            // Add up cumulative height to bottom of window.
            int y;
            if (topLine != null)
                y = editor.getBuffer().getY(topLine) + pixelsAboveTopLine + windowHeight;
            else
                y = pixelsAboveTopLine + windowHeight;
            if (y < totalHeight)
                scrollDown();
        }
    }

    public void windowUp(int lines)
    {
        Line line = topLine;
        if (line == null) {
            if (editor.getModeId() == IMAGE_MODE) {
                pixelsAboveTopLine -= lines * charHeight;
                if (pixelsAboveTopLine < 0)
                    pixelsAboveTopLine = 0;
                repaint();
            }
            return;
        }
        if (line instanceof ImageLine) {
            imageLineWindowUp(lines);
            return;
        }
        int actual = 0;
        for (int i = 0; i < lines; i++) {
            Line prev = line.previousVisible();
            if (prev == null)
                break;
            if (prev instanceof ImageLine) {
                imageLineWindowUp(lines);
                return;
            }
            line = prev;
            lineChanged(line);
            ++actual;
        }
        scrollPixelsUp(actual * charHeight);
        setTopLine(line);
        editor.maybeScrollCaret();
        editor.updateDisplay();
    }

    private void imageLineWindowUp(int lines)
    {
        int oldY = getAbsoluteY(topLine) + pixelsAboveTopLine;
        int newY = oldY - lines * charHeight;
        if (newY < 0)
            newY = 0;
        Line line = lineFromAbsoluteY(newY);
        if (line != null) {
            int y = getAbsoluteY(line);
            topLine = line;
            pixelsAboveTopLine = newY - y;
            setUpdateFlag(REPAINT);
            editor.updateDisplay();
        }
    }

    public void windowDown(final int lines)
    {
        Line top = topLine;
        if (top == null) {
            if (editor.getModeId() == IMAGE_MODE) {
                int windowHeight = getHeight();
                int bufferHeight = editor.getBuffer().getDisplayHeight();
                pixelsAboveTopLine += lines * charHeight;
                if (pixelsAboveTopLine + windowHeight > bufferHeight)
                    pixelsAboveTopLine = bufferHeight - windowHeight;
                repaint();
            }
            return;
        }
        if (top instanceof ImageLine) {
            imageLineWindowDown(lines);
            return;
        }
        int actual = 0;
        Line bottom = getBottomLine();
        for (int i = 0; i < lines; i++) {
            bottom = bottom.nextVisible();
            if (bottom == null)
                break;
            lineChanged(bottom);
            Line next = top.nextVisible();
            if (next instanceof ImageLine) {
                imageLineWindowDown(lines);
                return;
            }
            if (next == null)
                break;
            top = next;
            ++actual;
        }
        if (bottom != null) {
            bottom = bottom.nextVisible();
            if (bottom != null)
                lineChanged(bottom);
        }
        scrollPixelsDown(actual * charHeight);
        setTopLine(top);
        editor.maybeScrollCaret();
        editor.updateDisplay();
    }

    private void imageLineWindowDown(int lines)
    {
        Line top = topLine;
        int oldY = getAbsoluteY(top) + pixelsAboveTopLine;
        int newY = oldY + lines * charHeight;
        int windowHeight = getHeight();
        int bufferHeight = editor.getBuffer().getDisplayHeight();
        if (newY + windowHeight > bufferHeight)
            newY = bufferHeight - windowHeight;
        if (newY == oldY)
            return;
        Line line = lineFromAbsoluteY(newY);
        if (line != null) {
            int y = getAbsoluteY(line);
            topLine = line;
            pixelsAboveTopLine = newY - y;
            setUpdateFlag(REPAINT);
            editor.updateDisplay();
        }
    }

    public void windowLeft(int characters)
    {
        int absCaretCol = getShift() + getCaretCol();
        if (getShift() - characters < 0)
            characters = getShift();
        setShift(getShift() - characters);
        setCaretCol(absCaretCol - getShift());
        repaint();
        editor.updateScrollBars();
    }

    public void windowRight(final int characters)
    {
        int absCaretCol = getShift() + getCaretCol();
        setShift(getShift() + characters);
        setCaretCol(absCaretCol - getShift());
        repaint();
        editor.updateScrollBars();
    }

    public void setUpdateFlag(int mask)
    {
        updateFlag |= mask;
    }

    // Whether a full repaint is owed, and a way to settle it without
    // painting. For the headless tests, which never paint: a command that
    // changes what is on screen without changing any line -- clearing a
    // selection, say -- is otherwise indistinguishable from one that does
    // nothing at all.
    boolean isRepaintPending()
    {
        return (updateFlag & REPAINT) == REPAINT;
    }

    void clearRepaintPending()
    {
        updateFlag &= ~REPAINT;
    }

    private int reframeParam = 0;

    public void setReframe(int n)
    {
        reframeParam = n;
    }

    public void reframe()
    {
        if (!Editor.displayReady())
            return;
        if (editor.getDot() == null)
            return;
        if (getHeight() == 0)
            return; // Not visible yet.
        if (topLine == null || (updateFlag & REFRAME) != 0) {
            final Buffer buffer = editor.getBuffer();
            try {
                buffer.lockRead();
            }
            catch (InterruptedException e) {
                Log.error(e);
                return;
            }
            try {
                reframeHorizontally();
                reframeVertically();
            }
            finally {
                buffer.unlockRead();
            }
            updateFlag &= ~REFRAME;
        }
    }

    private void reframeVertically()
    {
        if (topLine != null && topLine.isHidden()) {
            Line prev = topLine.previousVisible();
            setTopLine(prev != null ? prev : topLine.nextVisible());
            setUpdateFlag(REPAINT);
        }

        if (topLine == null || mustReframe()) {
            Line top = findNewTopLine(editor.getDotLine());
            setTopLine(top);
            setUpdateFlag(REPAINT);
        }

        reframeParam = 0;

        // Now we need to check to make sure there's not unnecessary
        // whitespace at the bottom of the display.

        // If we can't go back any further, we have no choice.
        if (topLine.previousVisible() == null)
            return;

        int y = getAbsoluteY(topLine);
        int limit =
            editor.getBuffer().getDisplayHeight() - getHeight() + charHeight;
        if (y > limit) {
            // We need to scroll back in the buffer a bit.
            Line top = lineFromAbsoluteY(limit);
            setTopLine(top);
            setUpdateFlag(REPAINT);
            reframeParam = 0;
        }
    }

    // Returns true if necessary to reframe vertically.
    private boolean mustReframe()
    {
        final int height = getHeight();
        final Line dotLine = editor.getDotLine();
        Line line = topLine;
        int y = - pixelsAboveTopLine;
        while (y < height) {
            if (line == dotLine) {
                // Whole line must fit.
                return y + line.getHeight() > height;
            }
            Line next = line.nextVisible();
            if (next == null)
                return true;
            y += line.getHeight();
            line = next;
        }
        return true;
    }

    // Helper for reframeVertically().
    private Line findNewTopLine(final Line dotLine)
    {
        int y;
        if (reframeParam == 0)
            y = getHeight() / 2; // Default.
        else if (reframeParam > 0)
            y = (reframeParam - 1) * charHeight;
        else // reframeParam < 0
            y = getHeight() + reframeParam * charHeight;

        Line line = dotLine;
        while (true) {
            Line prev = line.previousVisible();
            if (prev == null)
                break;
            y -= prev.getHeight();
            if (y < 0)
                break;
            line = prev;
        }

        return line;
    }

    private void reframeHorizontally()
    {
        if (editor.getDot() == null)
            return;
        if (editor.getDotLine() instanceof ImageLine)
            return;
        final int absCaretCol = caretCol + shift;
        Debug.assertTrue(absCaretCol >= 0);
        ensureColumnVisible(editor.getDotLine(), absCaretCol);
        caretCol = absCaretCol - shift;
    }

    public synchronized void ensureColumnVisible(Line line, int absCol)
    {
        final int oldShift = shift;
        if (absCol < 50)
            shift = 0;
        int col = absCol - shift;
        if (col < 0) {
            do {
                shift -= 8;
                if (shift < 0)
                    shift = 0;
                col = absCol - shift;
            } while (col < 0);
        } else {
            if (col > getMaxCols()) {
                shift = absCol - getMaxCols();
                col = absCol - shift;
                Debug.assertTrue(col == getMaxCols());
            }
            Graphics2D g2d = (Graphics2D) getGraphics();
            if (g2d == null) {
                Log.error("ensureColumnVisible g2d is null");
                return;
            }
            formatLine(line, shift, col);
            int x = measureLine(g2d, textArray, col, formatArray);
            final int maxWidth = getWidth() - gutterWidth - charWidth;
            while (x > maxWidth) {
                shift += 8;
                col = absCol - shift;
                formatLine(line, shift, col);
                x = measureLine(g2d, textArray, col, formatArray);
            }
        }
        if (shift != oldShift)
            setUpdateFlag(REPAINT);
    }

    public void toCenter()
    {
        Line line = editor.getDotLine();
        int limit = getRows() / 2;
        for (int i = 0; i < limit; i++) {
            Line prev = line.previousVisible();
            if (prev == null)
                break;
            line = prev;
        }
        setTopLine(line);
        setUpdateFlag(REPAINT);
    }

    public void toTop()
    {
        Line goal = editor.getDotLine().previousVisible();
        if (goal == null)
            goal = editor.getDotLine();
        if (topLine != goal) {
            setTopLine(goal);
            setUpdateFlag(REPAINT);
        }
    }

    // Does nothing if entire region is already visible.
    public void centerRegion(Line begin, Line end)
    {
        if (begin == null)
            return;
        if (end != null) {
            if (isLineVisible(begin) && isLineVisible(end))
                return; // Entire region is already visible.
        }
        Line newTopLine = null;
        int linesInRegion = 0;
        if (end != null) {
            for (Line line = begin; line != null && line.isBefore(end); line = line.nextVisible())
                ++linesInRegion;
        } else {
            for (Line line = begin; line != null; line = line.nextVisible())
                ++linesInRegion;
        }
        int numRows = getRows();
        if (numRows > linesInRegion) {
            int linesAbove = (numRows - linesInRegion) / 2;
            newTopLine = begin;
            do {
                Line prev = newTopLine.previousVisible();
                if (prev != null)
                    newTopLine = prev;
                else
                    break;
                --linesAbove;
            }
            while (linesAbove > 0);
        }
        if (newTopLine == null) {
            Line prev = begin.previousVisible();
            newTopLine = prev != null ? prev : begin;
        }
        if (topLine != newTopLine) {
            setTopLine(newTopLine);
            setUpdateFlag(REPAINT);
        }
    }

    private boolean isLineVisible(Line line)
    {
        return (line.lineNumber() >= getTopLineNumber() &&
            line.lineNumber() < getTopLineNumber() + getRows());
    }

    private final int getMaxCols()
    {
        // We need some slack here (runs of italics tend to get compressed).
        // An extra 25% should be plenty.
        return (getWidth() / charWidth) * 5 / 4;
    }

    public final int getColumns()
    {
        return (getWidth()-getGutterWidth(editor.getBuffer()))/charWidth - 1;
    }

    public final int getRows()
    {
        return getHeight() / charHeight;
    }

    public void moveCaretToPoint(Point point)
    {
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final Line line = lineFromY(point.y);
        if (line == null)
            return;
        if (line != dot.getLine()) {
            editor.updateDotLine();
            dot.setLine(line);
        }
        caretCol = Math.max(getColumn(line, point.x), 0);
        editor.moveDotToCol(caretCol + shift);
    }

    public synchronized Position positionFromPoint(Point point, int shift)
    {
        int savedShift = this.shift;
        this.shift = shift;
        Position pos = positionFromPoint(point);
        this.shift = savedShift;
        return pos;
    }

    public Position positionFromPoint(Point point)
    {
        return positionFromPoint(point.x, point.y);
    }

    public Position positionFromPoint(int x, int y)
    {
        Line line = lineFromY(y);
        if (line == null)
            return null;
        Position pos = new Position(line, 0);
        int col = getColumn(line, x);
        pos.moveToCol(col + shift, editor.getBuffer().getTabWidth());
        return pos;
    }

    // y is offset from top of window.
    public Line lineFromY(int y)
    {
        if (topLine == null)
            return null;
        Line line = topLine;
        int total = - pixelsAboveTopLine;
        final int limit = getHeight();
        while (true) {
            total += line.getHeight();
            if (total > y)
                break;
            if (total > limit)
                break;
            Line next = line.nextVisible();
            if (next == null)
                break;
            line = next;
        }
        return line;
    }

    // y is absolute offset from start of buffer.
    private Line lineFromAbsoluteY(int y)
    {
        Line line = editor.getBuffer().getFirstLine();
        if (line != null) {
            int total = 0;
            while (true) {
                total += line.getHeight();
                if (total > y)
                    break;
                Line next = line.nextVisible();
                if (next != null)
                    line = line.nextVisible();
                else
                    break;
            }
        }
        return line;
    }

    public synchronized int getColumn(Line line, int x)
    {
        if (line instanceof ImageLine)
            return 0;
        int maxCols = getMaxCols();
        formatLine(line, shift, maxCols);
        Graphics2D g2d = (Graphics2D) getGraphics();
        int begin = 0;
        int end = maxCols;
        while (end - begin > 4) {
            int pivot = (begin + end) / 2;
            int width = measureLine(g2d, textArray, pivot, formatArray);
            if (width + gutterWidth > x)
                end = pivot + 1;
            else
                begin = pivot - 1;
        }
        for (int i = begin; i < end; i++) {
            int width = measureLine(g2d, textArray, i, formatArray);
            if (width + gutterWidth > x)
                return i - 1;
        }
        return 0; // Shouldn't happen.
    }

    public boolean isOpaque()
    {
        return true;
    }

    public synchronized final void lineChanged(Line line)
    {
        // A null line would go into the map as a null key and never be found.
        if (line == null) {
            Debug.bug("lineChanged line is null");
            return;
        }
        changedLines.put(line, line);
    }

    public static void resetDisplay()
    {
        if (plainFont == null)
            return; // Not initialized yet. Nothing to do.
        initializeStaticValues();
        for (EditorIterator it = new EditorIterator(); it.hasNext();) {
            Display display = it.next().getDisplay();
            display.initialize();
            display.repaint();
        }
    }

    private static Object getDesktopAntialiasingHint()
    {
        try {
            Object hints = Toolkit.getDefaultToolkit()
                .getDesktopProperty("awt.font.desktophints");
            if (hints instanceof Map) {
                Object value =
                    ((Map) hints).get(RenderingHints.KEY_TEXT_ANTIALIASING);
                // A desktop with antialiasing switched off still leaves j's
                // own antialias preference in charge, so ignore OFF here.
                if (value != null &&
                    value != RenderingHints.VALUE_TEXT_ANTIALIAS_OFF &&
                    value != RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT)
                    return value;
            }
        }
        catch (Throwable t) {
            Log.error(t);
        }
        return null;
    }

    /**
     * Applies j's text rendering settings to a Graphics.
     *
     * <p>Fractional metrics stay off everywhere. The editor needs that because
     * charWidth and all the column arithmetic built on it are whole pixels. Text
     * fields need it for a subtler reason: Swing paints their glyphs with the
     * hints set here, but works out where the caret and the selection go from a
     * Graphics it fetches separately, which carries the defaults. Turning
     * fractional metrics on for the painting alone leaves the two disagreeing,
     * and the error accumulates along the string until the caret sits visibly
     * inside the wrong character.
     */
    public static void setRenderingHints(Graphics g)
    {
        if (!antialias)
            return;
        Graphics2D g2d = (Graphics2D) g;
        Object hint = desktopAntialiasingHint;
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                             hint != null ? hint
                                          : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                             RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    }

    public String getToolTipText(MouseEvent e)
    {
        return editor.getMode().getToolTipText(editor, e);
    }
}
