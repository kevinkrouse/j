/*
 * WebImages.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.web;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.armedbear.j.Display;
import org.armedbear.j.File;
import org.armedbear.j.ImageLink;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.LineSequence;
import org.armedbear.j.Log;
import org.armedbear.j.UIScale;
import org.armedbear.j.mode.html.HtmlLineSegment;
import org.armedbear.j.mode.image.ImageLine;

/**
 * Draws a page's images in place of their [IMAGE] links, for
 * {@code webShowImages}. The images on one line of text are drawn side by side
 * on the lines below it, each where its link starts; a line with nothing but
 * images on it is replaced by them.
 *
 * <p>Only local images are drawn. A remote one keeps its link.
 */
final class WebImages {
    private WebImages() {}

    /**
     * Returns lines with the images under dir drawn in, and moves the refs
     * past each line added or removed.
     */
    static LineSequence expand(LineSequence lines, Map<String, Integer> refs, File dir) {
        final LineSequence result = new LineSequence();
        int offset = 0; // Of the next line in result.
        Line line = lines.getFirstLine();
        while (line != null) {
            final Line next = line.next();
            final List<ImageLine.Placement> placements =
                    line instanceof WebLine webLine ? placements(webLine, dir) : List.of();
            if (placements.isEmpty()) {
                result.appendLine(line);
                offset += line.length() + 1;
            } else {
                final int length = line.length() + 1;
                final List<ImageLine> strips = strips(placements);
                if (isOnlyImages((WebLine) line, placements.size())) {
                    shiftRefs(refs, offset, length, strips.size() - length);
                } else {
                    result.appendLine(line);
                    offset += length;
                    shiftRefs(refs, offset, 0, strips.size());
                }
                for (ImageLine imageLine : strips)
                    result.appendLine(imageLine);
                offset += strips.size();
            }
            line = next;
        }
        if (result.getLastLine() != null)
            result.getLastLine().setNext(null);
        return result;
    }

    private static List<ImageLine.Placement> placements(WebLine line, File dir) {
        final LineSegmentList segments = line.getSegmentList();
        if (segments == null)
            return List.of();
        final List<ImageLine.Placement> placements = new ArrayList<>();
        final double scale = UIScale.getScale();
        int col = 0;
        for (int i = 0; i < segments.size(); i++) {
            final HtmlLineSegment segment = (HtmlLineSegment) segments.getSegment(i);
            if (segment.getLink() instanceof ImageLink link) {
                final BufferedImage image = load(dir, link.getTarget());
                if (image != null) {
                    final int width = link.getWidth() > 0 ? link.getWidth() : image.getWidth();
                    final int height = link.getHeight() > 0 ? link.getHeight() : image.getHeight();
                    placements.add(
                        new ImageLine.Placement(image,
                                col * Display.getCharWidth(),
                                (int) Math.round(width * scale),
                                (int) Math.round(height * scale),
                                link.getAnchor()));
                }
            }
            col += segment.getText().length();
        }
        return placements;
    }

    private static BufferedImage load(File dir, String src) {
        if (dir == null || src.contains(":"))
            return null;
        final File file = File.getInstance(dir, src);
        if (file == null || !file.isFile())
            return null;
        try {
            return ImageIO.read(new java.io.File(file.canonicalPath()));
        }
        catch (IOException e) {
            Log.error(e);
            return null;
        }
    }

    // Whether line is blank but for images, all drawn. An image left out
    // keeps its [IMAGE] link.
    private static boolean isOnlyImages(WebLine line, int drawn) {
        final LineSegmentList segments = line.getSegmentList();
        int images = 0;
        for (int i = 0; i < segments.size(); i++) {
            final HtmlLineSegment segment = (HtmlLineSegment) segments.getSegment(i);
            if (segment.getLink() instanceof ImageLink)
                ++images;
            else if (!segment.getText().isBlank())
                return false;
        }
        return images == drawn;
    }

    // Line-high strips of the placements, top to bottom.
    private static List<ImageLine> strips(List<ImageLine.Placement> placements) {
        int height = 0;
        for (ImageLine.Placement p : placements)
            height = Math.max(height, p.height());
        final int lineHeight = Display.getCharHeight();
        final List<ImageLine> strips = new ArrayList<>();
        for (int y = 0; y < height; y += lineHeight)
            strips.add(new ImageLine(placements, y, Math.min(lineHeight, height - y)));
        return strips;
    }

    // Moves the refs at or past from + span by delta; those within the span
    // go to from.
    private static void shiftRefs(Map<String, Integer> refs, int from, int span, int delta) {
        refs.replaceAll((ref, at) -> at < from ? at : at < from + span ? from : at + delta);
    }
}
