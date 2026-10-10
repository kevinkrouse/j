/*
 * ThemeShot.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Starts j, opens each file with the caret on its middle line and writes a
 * picture of its text to {@code <out-dir>/<file name>.png}, plus the top left
 * of the first one to {@code card.png}. The theme is whatever the prefs in
 * {@code user.home} name.
 *
 * <p>Sizes are logical pixels. The pictures are in device pixels, so run it
 * with {@code -Dsun.java2d.uiScale=2} for pictures that stay sharp on a high
 * resolution screen.
 *
 * <p>Run by {@code bb theme-catalog}, which gives it a display (Xvfb) and a
 * home directory per theme.
 *
 * <p>Arguments: out-dir, width, height, card-width, card-height, file...
 */
public final class ThemeShot {
    public static void main(String[] args) throws Exception {
        final java.io.File out = new java.io.File(args[0]);
        final int width = Integer.parseInt(args[1]);
        final int height = Integer.parseInt(args[2]);
        final int cardWidth = Integer.parseInt(args[3]);
        final int cardHeight = Integer.parseInt(args[4]);
        new Thread(() -> {
            try {
                Class.forName("Main")
                        .getMethod("main", String[].class)
                        .invoke(
                            null,
                            (Object) new String[] {
                                "--force-new-instance",
                                "--no-server",
                                "--no-session",
                                "--no-restore" });
            }
            catch (ReflectiveOperationException e) {
                e.printStackTrace();
            }
        }).start();
        for (int i = 0; i < 60 && !started(); i++)
            Thread.sleep(500);
        if (!started()) {
            System.err.println("j did not start");
            System.exit(2);
        }
        out.mkdirs();
        // Size the frame so that the text, not the window, is width by height.
        for (int i = 0; i < 3; i++) {
            onEdt(() -> {
                final Frame frame = Editor.getCurrentFrame();
                final Display display = Editor.currentEditor().getDisplay();
                frame.setExtendedState(java.awt.Frame.NORMAL);
                frame.setSize(
                    frame.getWidth() + width - display.getWidth(),
                    frame.getHeight() + height - display.getHeight());
                frame.validate();
            });
            Thread.sleep(300);
        }
        for (int i = 5; i < args.length; i++) {
            final java.io.File source = new java.io.File(args[i]);
            onEdt(() -> {
                final Editor ed = Editor.currentEditor();
                final Buffer buffer = Editor.getBuffer(File.getInstance(source.getAbsolutePath()));
                ed.switchToBuffer(buffer);
                ed.moveDotTo(buffer.getLine(buffer.getLineCount() / 2), 0);
                ed.getDisplay().setTopLine(buffer.getFirstLine());
                ed.updateDisplay();
            });
            // Let the buffer load, format and repaint.
            Thread.sleep(1000);
            final BufferedImage[] image = new BufferedImage[1];
            final double[] scale = new double[1];
            onEdt(() -> {
                final Display display = Editor.currentEditor().getDisplay();
                scale[0] = display.getGraphicsConfiguration().getDefaultTransform().getScaleX();
                // The frame won't always shrink that far; crop what's over.
                final BufferedImage all = paint(display, scale[0]);
                image[0] = all.getSubimage(
                    0,
                    0,
                    (int) Math.min(all.getWidth(), width * scale[0]),
                    (int) Math.min(all.getHeight(), height * scale[0]));
            });
            ImageIO.write(image[0], "png", new java.io.File(out, source.getName() + ".png"));
            if (i == 5) {
                final BufferedImage card = image[0].getSubimage(
                    0,
                    0,
                    (int) Math.min(image[0].getWidth(), cardWidth * scale[0]),
                    (int) Math.min(image[0].getHeight(), cardHeight * scale[0]));
                ImageIO.write(card, "png", new java.io.File(out, "card.png"));
            }
        }
        System.exit(0);
    }

    private static boolean started() {
        return Editor.currentEditor() != null
                && Editor.getCurrentFrame() != null
                && Editor.getCurrentFrame().isShowing();
    }

    private static void onEdt(Runnable r) throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(r);
    }

    private static BufferedImage paint(Display display, double scale) {
        final BufferedImage image = new BufferedImage((int) Math.ceil(display.getWidth() * scale),
                (int) Math.ceil(display.getHeight() * scale),
                BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.scale(scale, scale);
            display.paint(g);
        }
        finally {
            g.dispose();
        }
        return image;
    }
}
