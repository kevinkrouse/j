/*
 * BufferListStatus.java
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

package org.armedbear.j.vcs;

import static org.armedbear.j.Constants.*;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.armedbear.j.Buffer;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.File;
import org.armedbear.j.ProjectRoot;
import org.armedbear.j.Property;

/**
 * What the buffer lists show after a file's name: its status letter (M
 * changed, M in another color staged, A added, ? untracked, U conflicted),
 * or a dot, as gitBufferListStatus says; and its repository's color when
 * the project has several.
 */
public final class BufferListStatus {
    private BufferListStatus() {}

    public record Badge(String text, Color color) {}

    private static final Color CHANGED = new Color(0x2b6cb0);
    private static final Color STAGED = new Color(0x2a9d4b);
    private static final Color ADDED = new Color(0x3f9142);
    private static final Color DELETED = new Color(0x8b1a1a);
    private static final Color CONFLICT = new Color(0xe03131);
    private static final Color UNTRACKED = new Color(0x888888);

    /** The badge for buffer, or null for none. */
    public static Badge badge(Buffer buffer) {
        final String style = buffer.getStringProperty(Property.GIT_BUFFER_LIST_STATUS);
        if ("none".equals(style))
            return null;
        final VersionControlEntry entry = buffer.getVCSEntry();
        if (entry == null)
            return null;
        final Badge badge = entry.getCode() != null ? fromCode(entry.getCode()) : fromKind(entry.getStatusKind());
        if (badge == null || !"dot".equals(style))
            return badge;
        return new Badge("•", badge.color());
    }

    /** The badge for a porcelain code: the working tree's change first, then the index's. */
    static Badge fromCode(String xy) {
        if (xy.length() < 2)
            return null;
        final char x = xy.charAt(0);
        final char y = xy.charAt(1);
        if (x == 'U' || y == 'U' || (x == 'D' && y == 'D') || (x == 'A' && y == 'A'))
            return new Badge("U", CONFLICT);
        if (x == '?')
            return new Badge("?", UNTRACKED);
        if (x == '!')
            return null;
        if (y != ' ')
            return new Badge(String.valueOf(y == 'T' ? 'M' : y), y == 'D' ? DELETED : CHANGED);
        return switch (x) {
            case 'A' -> new Badge("A", ADDED);
            case 'D' -> new Badge("D", DELETED);
            case ' ' -> null;
            default -> new Badge(x == 'T' ? "M" : String.valueOf(x), STAGED);
        };
    }

    private static Badge fromKind(int kind) {
        return switch (kind) {
            case VCS_NEW -> new Badge("A", ADDED);
            case VCS_MODIFIED -> new Badge("M", CHANGED);
            case VCS_DELETED -> new Badge("D", DELETED);
            case VCS_CONFLICT -> new Badge("U", CONFLICT);
            default -> null;
        };
    }

    // Projects' repositories, by root, for a few seconds: a buffer list asks
    // on every repaint, and finding them lists a directory.
    private static final Map<File, List<VcsRepository>> projects = new HashMap<>();
    private static long projectsRead;
    private static final long PROJECTS_MILLIS = 5000;

    /**
     * The color of buffer's repository, when its project has more than one,
     * for a list on background; null otherwise. On the event thread.
     */
    public static Color repositoryColor(Buffer buffer, Color background) {
        if (System.currentTimeMillis() - projectsRead > PROJECTS_MILLIS) {
            projects.clear();
            colorIndexes.clear();
            projectsRead = System.currentTimeMillis();
        }
        final int i = colorIndexes.computeIfAbsent(buffer, BufferListStatus::colorIndex);
        return i < 0 ? null : RepositoryColors.color(i, background != null && DefaultTheme.isDark(background));
    }

    // Each buffer's, for a few seconds as projects are: rows are painted often.
    private static final Map<Buffer, Integer> colorIndexes = new java.util.WeakHashMap<>();

    private static int colorIndex(Buffer buffer) {
        final File file = buffer.getFile();
        if (file == null || !file.isLocal())
            return -1;
        final File root = ProjectRoot.find(buffer);
        if (root == null)
            return -1;
        final List<VcsRepository> project = projects.computeIfAbsent(root, Repositories::forProject);
        return project.size() < 2 ? -1 : Repositories.colorIndex(Repositories.forFile(file), project);
    }
}
