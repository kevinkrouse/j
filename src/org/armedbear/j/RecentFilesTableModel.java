/*
 * RecentFilesTableModel.java
 *
 * Copyright (C) 1998-2002 Peter Graves
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.event.TableModelEvent;
import javax.swing.table.AbstractTableModel;

public final class RecentFilesTableModel extends AbstractTableModel {
    private static final int NAME = 0;
    private static final int LOCATION = 1;
    private static final int LAST_VISIT = 2;
    private static final int FIRST_VISIT = 3;

    private static final int ASCENDING = 0;
    private static final int DESCENDING = 1;

    private final String[] columnNames =
        { "Name", "Location", "Last Visit", "First Visit" };

    private static final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter shortDateFormat = DateTimeFormatter.ofPattern("EEE h:mm a");
    private static final DateTimeFormatter fullDateFormat = DateTimeFormatter.ofPattern("MMM d yyyy h:mm a");

    private final List<RecentFilesEntry> data = RecentFiles.getInstance().getEntries();

    private int[] indexes;

    private final ZonedDateTime startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault());
    private final ZonedDateTime startOfWeek = startOfDay.minusDays(6);

    private int sortColumn = LAST_VISIT;
    private int sortOrder = DESCENDING;

    public RecentFilesTableModel() {
        indexes = new int[data.size()];

        for (int i = 0; i < indexes.length; i++)
            indexes[i] = i;
    }

    @Override
    public int getColumnCount() {
        return columnNames.length;
    }

    @Override
    public int getRowCount() {
        return data.size();
    }

    @Override
    public String getColumnName(int col) {
        return columnNames[col];
    }

    private String format(long date) {
        ZonedDateTime t = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault());
        if (t.isBefore(startOfWeek))
            return fullDateFormat.format(t);
        if (t.isBefore(startOfDay))
            return shortDateFormat.format(t);
        return timeFormat.format(t);
    }

    public RecentFilesEntry getEntryAtRow(int row) {
        int i = indexes[row];
        return data.get(i);
    }

    public int getRowForEntry(RecentFilesEntry entry) {
        for (int row = 0; row < indexes.length; row++) {
            int i = indexes[row];
            if (entry == data.get(i))
                return row;
        }
        return -1;
    }

    @Override
    public Object getValueAt(int row, int col) {
        int i = indexes[row];
        RecentFilesEntry entry = data.get(i);
        if (entry != null) {
            switch (col) {
                case NAME:
                    return entry.name;
                case LOCATION:
                    return entry.location;
                case FIRST_VISIT:
                    return format(entry.firstVisit);
                case LAST_VISIT:
                    return format(entry.lastVisit);
            }
        }
        return null;
    }

    public void sortByColumn(int column) {
        if (column == sortColumn) {
            // Sorting on same column.  Reverse sort order.
            sortByColumn(column, sortOrder == DESCENDING ? ASCENDING : DESCENDING);
        } else {
            // Sorting on a different column.  Use default sort order for that column.
            switch (column) {
                case NAME:
                    sortByColumn(NAME, ASCENDING);
                    break;
                case LOCATION:
                    sortByColumn(LOCATION, ASCENDING);
                    break;
                case FIRST_VISIT:
                    sortByColumn(FIRST_VISIT, DESCENDING);
                    break;
                case LAST_VISIT:
                    sortByColumn(LAST_VISIT, DESCENDING);
                    break;
            }
        }
        fireTableChanged(new TableModelEvent(this));
    }

    private void sortByColumn(int column, int order) {
        if (order == DESCENDING) {
            for (int i = 0; i < getRowCount(); i++) {
                for (int j = i + 1; j < getRowCount(); j++) {
                    if (compareByColumn(indexes[i], indexes[j], column) < 0)
                        swap(i, j);
                }
            }
        } else {
            for (int i = 0; i < getRowCount(); i++) {
                for (int j = i + 1; j < getRowCount(); j++) {
                    if (compareByColumn(indexes[i], indexes[j], column) > 0)
                        swap(i, j);
                }
            }
        }
        sortColumn = column;
        sortOrder = order;
    }

    private void sortByName() {
        for (int i = 0; i < getRowCount(); i++) {
            for (int j = i + 1; j < getRowCount(); j++) {
                if (compareByColumn(indexes[i], indexes[j], 0) > 0)
                    swap(i, j);
            }
        }
    }

    private void sortByLocation() {
        for (int i = 0; i < getRowCount(); i++) {
            for (int j = i + 1; j < getRowCount(); j++) {
                if (compareByColumn(indexes[i], indexes[j], 1) > 0)
                    swap(i, j);
            }
        }
    }

    private void sortByFirstVisit() {
        for (int i = 0; i < getRowCount(); i++) {
            for (int j = i + 1; j < getRowCount(); j++) {
                if (compareByColumn(indexes[i], indexes[j], 2) < 0)
                    swap(i, j);
            }
        }
    }

    private void sortByLastVisit() {
        for (int i = 0; i < getRowCount(); i++) {
            for (int j = i + 1; j < getRowCount(); j++) {
                if (compareByColumn(indexes[i], indexes[j], 3) < 0)
                    swap(i, j);
            }
        }
    }

    private int compareByColumn(int i, int j, int column) {
        RecentFilesEntry entry1 = data.get(i);
        RecentFilesEntry entry2 = data.get(j);
        switch (column) {
            case NAME:
                return entry1.name.compareTo(entry2.name);
            case LOCATION:
                return entry1.location.compareTo(entry2.location);
            case FIRST_VISIT:
                if (entry1.firstVisit < entry2.firstVisit)
                    return -1;
                if (entry1.firstVisit == entry2.firstVisit)
                    return 0;
                return 1;
            case LAST_VISIT:
                if (entry1.lastVisit < entry2.lastVisit)
                    return -1;
                if (entry1.lastVisit == entry2.lastVisit)
                    return 0;
                return 1;
            default:
                Debug.assertTrue(false);
        }
        return 0;
    }

    private void swap(int i, int j) {
        int tmp = indexes[i];
        indexes[i] = indexes[j];
        indexes[j] = tmp;
    }

    @Override
    public Class<String> getColumnClass(int col) {
        return String.class;
    }
}
