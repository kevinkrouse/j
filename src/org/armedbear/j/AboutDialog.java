/*
 * AboutDialog.java
 *
 * Copyright (C) 1998-2012 Peter Graves
 * Copyright (C) 2013 Kevin Krouse
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

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Date;

public class AboutDialog extends AbstractDialog
{
    private long totalMemory;
    private long freeMemory;

    public AboutDialog()
    {
        super(Editor.getCurrentFrame(), null, true);

        Editor.getCurrentFrame().setWaitCursor();

        memory();
        Log.debug("total memory " + totalMemory);
        Log.debug("used " + (totalMemory - freeMemory));
        Log.debug("free " + freeMemory);

        setUndecorated(true);
        setResizable(false);

        Container contents = getContentPane();

        if (contents instanceof JComponent)
        {
            ((JComponent) contents).setBorder(
                    BorderFactory.createEmptyBorder(11, 21, 12, 13));
            ((JComponent) contents).setOpaque(true);
        }

        contents.setLayout(new GridBagLayout());

        GridBagConstraints c =
                new GridBagConstraints(
                        1, 0,
                        1,
                        1, 1, 1,
                        GridBagConstraints.NORTHEAST,
                        GridBagConstraints.NONE,
                        new Insets(0, 0, 0, 0), 0, 0);

        ImageIcon image = Utilities.getIconFromFile("icons/j-64.png");
        JLabel icon = new JLabel(image);

        icon.setOpaque(false);
        icon.setBorder(BorderFactory.createEmptyBorder(0, 18, 16, 0));

        contents.add(icon, c);

        Font productFont = mainPanel.getFont().deriveFont(Font.BOLD, 11);
        Font buildFont = mainPanel.getFont().deriveFont(Font.PLAIN, 11);

        Font plainFont = mainPanel.getFont().deriveFont(Font.PLAIN, 10);
        Font boldFont = mainPanel.getFont().deriveFont(Font.BOLD, 10);

        c.gridy = 0;
        c.insets = new Insets(10, 0, 0, 0);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        JLabel productLabel = new JLabel(getProductName());
        productLabel.setFont(productFont);
        mainPanel.add(productLabel, c);

        c.gridy = 1;
        c.insets = new Insets(0, 0, 0, 0);
        JLabel buildLabel = new JLabel(getBuildVersion());
        buildLabel.setFont(buildFont);
        mainPanel.add(buildLabel, c);

        // spacer
        c.gridy = 2;
        mainPanel.add(new JLabel(" "), c);

        c.gridy = 3;
        c.insets = new Insets(0, 0, 0, 0);
        JLabel jvmVersionLabel = new JLabel(getJVMVersion());
        jvmVersionLabel.setFont(boldFont);
        mainPanel.add(jvmVersionLabel, c);

        c.gridy = 4;
        c.insets = new Insets(0, 0, 0, 0);
        JLabel jvmInfoLabel = new JLabel(getJVMInfo());
        jvmInfoLabel.setFont(plainFont);
        mainPanel.add(jvmInfoLabel, c);

        c.gridx = 0;
        c.gridy = 0;
        contents.add(mainPanel, c);

        c.gridy = 1;
        c.gridwidth = GridBagConstraints.REMAINDER;
        c.insets = new Insets(0, 0, 0, 0);
        JLabel copyrightLabel = new JLabel(getCopyright());
        copyrightLabel.setFont(plainFont);
        copyrightLabel.setForeground(Color.GRAY);
        contents.add(copyrightLabel, c);

        // dismiss on click
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                super.mouseClicked(e);
                dispose();
            }
        });

        pack();
        Editor.getCurrentFrame().setDefaultCursor();
    }

    String getProductName()
    {
        return Version.getShortVersionString();
    }

    String getBuildVersion()
    {
        return Version.getLongBuildString();
    }

    String getJVMVersion()
    {
        return "Java " + System.getProperty("java.version");
    }

    String getJVMInfo()
    {
        StringBuilder sb = new StringBuilder();
        String vm = System.getProperty("java.vm.name");
        if (vm != null)
            sb.append(vm);
        String vendor = System.getProperty("java.vendor");
        if (vendor != null)
            sb.append(" by ").append(vendor);
        return sb.toString();
    }

    String getCopyright()
    {
        return "Copyright (C) 1998-2010 Peter Graves (peter@armedbear.org)";
    }

    private static String getUptimeString()
    {
        final int millisecondsPerMinute = 60 * 1000;
        final int millisecondsPerHour = 60 * millisecondsPerMinute;
        final int millisecondsPerDay = 24 * millisecondsPerHour;

        long now = System.currentTimeMillis();
        SimpleDateFormat dateFormatter = new SimpleDateFormat("EEEE MMM d yyyy h:mm a");
        String dateString = dateFormatter.format(new Date(now));
        long uptime = now - Editor.getStartTimeMillis();

        // Don't show uptime if less than 1 minute.
        if (uptime < millisecondsPerMinute)
            return dateString;

        int days = (int) (uptime / millisecondsPerDay);
        int remainder = (int) (uptime % millisecondsPerDay);
        int hours = remainder / millisecondsPerHour;
        remainder = remainder % millisecondsPerHour;
        int minutes = remainder / millisecondsPerMinute;

        StringBuilder sb = new StringBuilder(dateString);
        sb.append("   up ");
        if (uptime < millisecondsPerHour)
        {
            sb.append(minutes);
            sb.append(" minute");
            if (minutes > 1)
                sb.append('s');
        }
        else
        {
            if (days > 0)
            {
                sb.append(days);
                sb.append(" day");
                if (days > 1)
                    sb.append('s');
                sb.append(", ");
            }
            sb.append(hours);
            sb.append(':');
            if (minutes < 10)
                sb.append('0');
            sb.append(minutes);
        }
        return sb.toString();
    }

    private void memory()
    {
        Runtime runtime = Runtime.getRuntime();
        try
        {
            runtime.gc();
            Thread.currentThread().sleep(100);
            runtime.runFinalization();
            Thread.currentThread().sleep(100);
            runtime.gc();
            Thread.currentThread().sleep(100);
        }
        catch (InterruptedException e)
        {
            Log.error(e);
        }
        totalMemory = runtime.totalMemory();
        freeMemory = runtime.freeMemory();
    }

    private String formatMemory(long value)
    {
        if (value < 1000)
            return String.valueOf(value) + " bytes";
        if (value < 1000 * 1024)
        {
            double k = Math.round(value * 10 / (float) 1024) / 10.0;
            return String.valueOf(k) + "K";
        }
        if (value < 1000 * 1024 * 1024)
        {
            double m = Math.round(value * 10 / (float) (1024 * 1024)) / 10.0;
            return String.valueOf(m) + "M";
        }
        double g = Math.round(value * 10 / (float) (1024 * 1024 * 1024)) / 10.0;
        return String.valueOf(g) + "G";
    }

    public static void about()
    {
        AboutDialog d = new AboutDialog();
        Editor.currentEditor().centerDialog(d);
        d.show();
    }
}
