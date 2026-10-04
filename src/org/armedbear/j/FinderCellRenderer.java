/*
 * FinderCellRenderer.java
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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Arrays;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListCellRenderer;

/** One line per row: icon, label, then dimmed detail and note, and a key binding at the right. */
public final class FinderCellRenderer extends JComponent implements ListCellRenderer<FinderItem.Row> {
    private FinderItem.Row row;
    private Color background;
    private Color foreground;
    private Color dim;
    private Color match;
    private Font bold;

    @Override
    public Component getListCellRendererComponent(
        JList<? extends FinderItem.Row> list,
        FinderItem.Row value,
        int index,
        boolean isSelected,
        boolean cellHasFocus
    ) {
        return render(list, value, isSelected);
    }

    /** The component that draws row, in any list. */
    public Component render(JList<?> list, FinderItem.Row value, boolean isSelected) {
        row = value;
        background = isSelected ? list.getSelectionBackground() : list.getBackground();
        foreground = isSelected ? list.getSelectionForeground() : list.getForeground();
        dim = blend(foreground, background, 0.55f);
        match = isSelected ? foreground : DefaultTheme.isDark(background) ? new Color(0x6CB4FF) : new Color(0x0050C8);
        Font font = list.getFont();
        if (font != getFont()) {
            setFont(font);
            bold = font.deriveFont(Font.BOLD);
        }
        return this;
    }

    // Wide enough for all but the note, which is cut short to fit.
    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(bold != null ? bold : getFont());
        int iconHeight = row != null && row.item().icon() != null ? row.item().icon().getIconHeight() : 0;
        int width = UIScale.scale(100);
        if (row != null) {
            FinderItem item = row.item();
            int pad = UIScale.scale(4);
            width = 3 * pad + UIScale.scale(16) + fm.stringWidth(item.label());
            if (!item.detail().isEmpty())
                width += 2 * pad + fm.stringWidth(item.detail());
            if (!item.keyText().isEmpty())
                width += 2 * pad + fm.stringWidth(item.keyText());
        }
        return new Dimension(width, Math.max(fm.getHeight(), iconHeight) + UIScale.scale(4));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        Display.setRenderingHints(g2);
        g2.setColor(background);
        g2.fillRect(0, 0, getWidth(), getHeight());
        if (row == null)
            return;
        final FinderItem item = row.item();
        final int pad = UIScale.scale(4);
        final FontMetrics fm = g2.getFontMetrics(getFont());
        final int baseline = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
        int x = pad;
        int right = getWidth() - pad;

        Icon icon = item.icon();
        int iconWidth = UIScale.scale(16);
        if (icon != null)
            icon.paintIcon(this, g2, x, (getHeight() - icon.getIconHeight()) / 2);
        x += iconWidth + pad;

        String key = item.keyText();
        if (!key.isEmpty()) {
            int w = fm.stringWidth(key);
            g2.setFont(getFont());
            g2.setColor(dim);
            g2.drawString(key, right - w, baseline);
            right -= w + 2 * pad;
        }

        x = drawMarked(g2, item.label(), item.labelOffset(), x, right, baseline, foreground);
        String detail = item.detail();
        if (!detail.isEmpty() && x < right)
            x = drawMarked(g2, detail, item.detailOffset(), x + 2 * pad, right, baseline, dim);
        String note = item.note();
        if (!note.isEmpty() && x < right) {
            g2.setFont(getFont());
            g2.setColor(dim);
            drawClipped(g2, note, x + 2 * pad, right, baseline);
        }
    }

    // Draws s, with the characters at the row's positions (offset by offset) in
    // bold and the match color. Returns the x after it.
    private int drawMarked(Graphics2D g2, String s, int offset, int x, int right, int baseline, Color color) {
        int[] positions = row.positions();
        for (int i = 0; i < s.length() && x < right; i++) {
            boolean marked = offset >= 0 && positions != null && Arrays.binarySearch(positions, offset + i) >= 0;
            Font f = marked ? bold : getFont();
            g2.setFont(f);
            g2.setColor(marked ? match : color);
            String ch = String.valueOf(s.charAt(i));
            int w = g2.getFontMetrics(f).stringWidth(ch);
            if (x + w > right) {
                drawEllipsis(g2, x, right, baseline, color);
                return right;
            }
            g2.drawString(ch, x, baseline);
            x += w;
        }
        return x;
    }

    private void drawClipped(Graphics2D g2, String s, int x, int right, int baseline) {
        FontMetrics fm = g2.getFontMetrics();
        if (x + fm.stringWidth(s) <= right) {
            g2.drawString(s, x, baseline);
            return;
        }
        int ellipsis = fm.stringWidth("…");
        int end = s.length();
        while (end > 0 && x + fm.stringWidth(s.substring(0, end)) + ellipsis > right)
            end--;
        if (end > 0)
            g2.drawString(s.substring(0, end) + "…", x, baseline);
    }

    private void drawEllipsis(Graphics2D g2, int x, int right, int baseline, Color color) {
        g2.setFont(getFont());
        g2.setColor(color);
        if (x + g2.getFontMetrics().stringWidth("…") <= right)
            g2.drawString("…", x, baseline);
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(
            Math.round(a.getRed() * t + b.getRed() * (1 - t)),
            Math.round(a.getGreen() * t + b.getGreen() * (1 - t)),
            Math.round(a.getBlue() * t + b.getBlue() * (1 - t))
        );
    }
}
