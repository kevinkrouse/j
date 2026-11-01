/*
 * Folds.java
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

/**
 * What a buffer's folds are: its mode's, which know its structure, unless
 * the buffer has folds of its own, as a comparison's unchanged runs.
 * See {@link Buffer#getFolds}.
 */
public interface Folds {
    /** "… " + what, as a closed fold is labeled, with the foldSymbol preference's symbol. */
    public static String label(String what) {
        return Editor.preferences().getStringProperty(Property.FOLD_SYMBOL) + " " + what;
    }

    /**
     * The lines that folding at <code>line</code> hides, for a mode that
     * knows its own structure.
     *
     * @return the first and last lines to hide; an empty array if there is
     *         nothing to fold there; or <code>null</code> to fold by
     *         indentation.
     */
    public Line[] getFoldRange(Editor editor, Line line);

    /**
     * Whether line heads lines that a fold can hide, for the gutter's
     * marker. Asked of visible lines as they are painted, so it should be
     * cheap; the display keeps the answer until the buffer changes, or
     * Buffer.foldsChanged says its folds did.
     */
    public default boolean isFoldStart(Buffer buffer, Line line) {
        return FoldCommands.isIndentedBelow(buffer, line);
    }

    /**
     * The first and last lines that folding what line heads hides, or null:
     * nothing to fold there, or folds that aren't a range of lines, as a
     * status's or log's, whose gutter then draws no extent.
     *
     * <p>Null serves both now. A method of its own, saying whether a mode's
     * folds are ranges of lines the gutter can draw, would keep this one
     * for what a fold hides, should a caller need a status's or a log's.
     */
    public default Line[] getFoldBelow(Buffer buffer, Line line) {
        return FoldCommands.indentedBelow(buffer, line);
    }

    /** The folds a file opens with closed, each as its preference says. */
    public enum Initial {
        /** The comment at the top, as a license. */
        HEADER(Property.FOLD_HEADER_COMMENT),
        /** The imports. */
        IMPORTS(Property.FOLD_IMPORTS),
        /** The documentation of the file's class, after the imports. */
        CLASS_DOC(Property.FOLD_CLASS_DOC);

        private final Property property;

        Initial(Property property) {
            this.property = property;
        }

        public Property property() {
            return property;
        }
    }

    /**
     * The line heading buffer's fold of kind, near its top, which a file
     * opens with closed; null if the mode knows none.
     */
    public default Line initialFold(Buffer buffer, Initial kind) {
        return null;
    }

    /** Whether what line heads is folded away. */
    public default boolean isFolded(Line line) {
        return line.next() != null && line.next().isHidden();
    }

    /** Folds or unfolds what line heads, as a click on its gutter marker does. */
    public default void toggleFoldAt(Editor editor, Line line) {
        FoldCommands.toggleFoldAt(editor, line);
    }

    /**
     * For a mode whose folds are its own, not hidden lines: opens, closes,
     * or with null toggles, the fold line heads or is in, as the fold
     * commands at the caret do. False if its folds are hidden lines.
     */
    public default boolean setFoldAt(Editor editor, Line line, Boolean open) {
        return false;
    }

    /** As setFoldAt, for every fold. */
    public default boolean setAllFolds(Editor editor, boolean open) {
        return false;
    }

    /**
     * Folds everything that can be, leaving an outline: the bodies of the
     * methods, or of the sections under the headings. A mode with nothing
     * to fold so says.
     */
    public void foldAll(Editor editor);
}
