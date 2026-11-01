/*
 * DirectoryEntry.java
 *
 * Copyright (C) 1998-2004 Peter Graves
 * Copyright (C) 2012-2026 Kevin Krouse
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

package org.armedbear.j.mode.dir;

import java.util.regex.Matcher;
import org.armedbear.j.DirectoryFilenameFilter;
import org.armedbear.j.Log;
import org.armedbear.j.util.TimeStyle;

public final class DirectoryEntry {
    private String name;
    private long date;
    private long size;
    private boolean isDirectory;
    private boolean isLink;
    private String linkedTo;
    private boolean isMarked;
    // HiddenNames' kind: shown, hidden or ignored.
    private int hidden;

    // For native format, this is all we store: as ls wrote it, or with its date as an age.
    private String string;
    // Where the size starts and ends and the name starts in the text past the
    // tag's two columns, so nothing reads a line's columns by its dates; -1 if not found.
    private int sizeStart = -1;
    private int sizeEnd = -1;
    private int nameStart = -1;

    // The internal format's: as ls -l's first column, and how sizes and dates are written.
    private String mode = "----------";
    private ListingStyle style = ListingStyle.DEFAULT;

    // Constructor for native "ls -l" format.
    private DirectoryEntry(String string, char firstChar) {
        this.string = string;
        if (firstChar == 'd')
            isDirectory = true;
        else if (firstChar == 'l')
            isLink = true;
        size = -1;
        findColumns();
    }

    // The columns of ls's line, by its date, read once.
    private void findColumns() {
        final int link = string.indexOf(" -> ");
        final String s = link >= 0 ? string.substring(0, link) : string;
        final Matcher matcher = DirectoryBuffer.getNativeMoveToFilenameRegExp().matcher(s);
        if (!matcher.find())
            return;
        sizeStart = matcher.start();
        sizeEnd = s.indexOf(' ', sizeStart);
        nameStart = matcher.end();
        name = s.substring(nameStart);
    }

    /** Where the name starts in the line, past the tag's columns; -1 if not known. */
    final int nameColumn() {
        return nameStart < 0 ? -1 : 2 + nameStart;
    }

    /** Where the size starts in the line; -1 if not known. */
    final int sizeColumn() {
        return sizeStart < 0 ? -1 : 2 + sizeStart;
    }

    /** Where the size ends in the line; -1 if not known. */
    final int sizeEndColumn() {
        return sizeEnd < 0 ? -1 : 2 + sizeEnd;
    }

    /**
     * ls's date written as how long ago, as the age time style has it, read
     * back from the date ls wrote; left as it is if that isn't a date.
     */
    final void writeAge(long now) {
        if (string == null || sizeEnd < 0 || nameStart <= sizeEnd)
            return;
        final long millis = ListingStyle.parseDate(string.substring(sizeEnd, nameStart).strip(), now);
        if (millis < 0)
            return;
        date = millis;
        final String age = ListingStyle.padAge(TimeStyle.age(millis / 1000, now / 1000));
        string = string.substring(0, sizeEnd) + " " + age + " " + string.substring(nameStart);
        nameStart = sizeEnd + 1 + age.length() + 1;
    }

    // Constructor for internal format.
    public DirectoryEntry(String name, long date, long size) {
        this.name = name;
        this.date = date;
        this.size = size;
        string = null;
    }

    // Constructor for internal format.
    public DirectoryEntry(String name, long date, long size, boolean isDirectory) {
        this.name = name;
        this.date = date;
        this.size = size;
        this.isDirectory = isDirectory;
        string = null;
    }

    // Wrapper for constructor for native "ls -l" format.
    // Ignore strings that aren't really directory entries.
    // Apply filter (if any).
    public static DirectoryEntry getDirectoryEntry(String s, DirectoryFilenameFilter filter) {
        if (s.length() == 0)
            return null;
        // Ignore "total" line, command line echo.
        // First char must be one of "-dlcbsp".
        final char c = s.charAt(0);
        if ("-dlcbsp".indexOf(c) < 0)
            return null;
        if (c == 'l' && s.startsWith("ls "))
            return null;
        if (filter != null && c != 'd' && !filter.accepts(getName(s)))
            return null;
        return new DirectoryEntry(s, c);
    }

    // Extracts filename from "ls -l" directory listing.
    public static String getName(String s) {
        // Strip symbolic link if any.
        int end = s.indexOf(" -> ");
        if (end >= 0)
            s = s.substring(0, end);
        Matcher matcher = DirectoryBuffer.getNativeMoveToFilenameRegExp().matcher(s);
        if (matcher.find())
            return s.substring(matcher.end());
        Log.error("DirectoryEntry.getName returning null s = |" + s + "|");
        return null;
    }

    public static int getNameColumn(String s) {
        // Strip symbolic link if any.
        int end = s.indexOf(" -> ");
        if (end >= 0)
            s = s.substring(0, end);
        Matcher matcher = DirectoryBuffer.getNativeMoveToFilenameRegExp().matcher(s);
        if (matcher.find())
            return matcher.end();
        return 0;
    }

    public final String extractName() {
        if (name == null && string != null)
            name = getName(string);
        return name;
    }

    /** The size ls wrote, in bytes as near as it says; -1 if not known. */
    final long nativeSize() {
        if (string == null || sizeStart < 0 || sizeEnd <= sizeStart)
            return -1;
        return ListingStyle.parseSize(string.substring(sizeStart, sizeEnd));
    }

    // nameColumn must be > 0. It's just a hint.
    public final String extractName(int nameColumn) {
        if (name != null)
            return name; // Cached.
        if (string == null)
            return null;
        if (nameColumn > 0 && nameColumn < string.length()) {
            String s = string;
            // Strip symbolic link if any.
            int end = s.indexOf(" -> ");
            if (end >= 0)
                s = s.substring(0, end);
            if (s.charAt(nameColumn - 1) == ' ')
                if (!Character.isWhitespace(s.charAt(nameColumn)))
                    return name = s.substring(nameColumn);
        }
        // Do it the hard way.
        return getName(string);
    }

    public final String getName() {
        return name;
    }

    public final long getDate() {
        return date;
    }

    public final long getSize() {
        return size;
    }

    public final void setSize(long size) {
        this.size = size;
    }

    public final int getHidden() {
        return hidden;
    }

    final void setHidden(int kind) {
        hidden = kind;
    }

    final void setMode(String mode) {
        this.mode = mode;
    }

    final void setStyle(ListingStyle style) {
        this.style = style;
    }

    public final boolean isDirectory() {
        return isDirectory;
    }

    public final boolean isLink() {
        return isLink;
    }

    public final void setLinkedTo(String linkedTo) {
        this.linkedTo = linkedTo;
    }

    public final boolean isMarked() {
        return isMarked;
    }

    public final void setMarked(boolean b) {
        isMarked = b;
    }

    public final String getString() {
        return string;
    }

    @Override
    public String toString() {
        String marked = isMarked ? "T " : "  ";
        // Use saved string for native format.
        if (string != null)
            return marked.concat(string);
        // Construct string for internal format: as ls -l writes it, but for
        // links, owner and group.
        StringBuilder sb = new StringBuilder(256);
        sb.append(marked);
        sb.append(mode).append(' ');
        final String sizeString = style.size(Math.max(size, 0));
        for (int j = sizeString.length(); j < (style.human() ? 5 : 10); j++)
            sb.append(' ');
        sizeStart = sb.length() - 2;
        sb.append(sizeString);
        sizeEnd = sb.length() - 2;
        sb.append(' ');
        sb.append(style.date(date, System.currentTimeMillis())).append(' ');
        nameStart = sb.length() - 2;
        sb.append(name);
        if (linkedTo != null) {
            sb.append(" -> ");
            sb.append(linkedTo);
        }
        return sb.toString();
    }
}
