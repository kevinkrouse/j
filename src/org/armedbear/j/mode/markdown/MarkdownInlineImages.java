/*
 * MarkdownInlineImages.java
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

package org.armedbear.j.mode.markdown;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Property;
import org.armedbear.j.VirtualText;
import org.armedbear.j.util.Background;

/**
 * Draws the images of a Markdown buffer in place of the lines that hold
 * them: a line whose only content is {@code ![alt](src)} becomes a
 * {@link VirtualText.Placement#REPLACE} whose painter draws the picture,
 * and the caret entering the line reveals the source to edit. The buffer's
 * text is never touched.
 *
 * <p>This is the first client of {@code REPLACE} and needs no external
 * renderer: {@link ImageIO} reads the picture directly. It is the proof
 * that the placement works end to end before the Mermaid extension builds
 * on it.
 *
 * <p>Only local pictures are drawn; a remote {@code http(s)} source, or a
 * picture in a buffer opened over ssh or ftp, keeps its link. A picture is
 * read off the event thread once and cached by file and modification time,
 * so a rescan neither re-reads an unchanged file nor starts a second load
 * for one already in flight. The scan is debounced and skipped when nothing
 * has changed, so typing and caret moves do not churn the display.
 *
 * <p>The state is held against a {@link WeakReference} of its buffer, and
 * the debounce timer's listener does not capture the buffer, so a closed
 * buffer and its decoded pictures can be collected.
 */
public final class MarkdownInlineImages {

    // A line that is nothing but one image: optional indent, the image,
    // optional trailing space. The alt is group 1, the src group 2.
    private static final Pattern STANDALONE =
            Pattern.compile("^ {0,3}!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\) *$");

    // The extensions ImageIO reads without a plugin, as image mode's.
    private static final List<String> IMAGE_EXTENSIONS =
            List.of(".gif", ".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff");

    // The layer owner for these decorations.
    private static final String OWNER = "inlineImages";

    // The debounce before a rescan after the last edit.
    private static final int DEBOUNCE_MILLIS = 300;

    // Per-buffer state, keyed weakly so a closed buffer is forgotten.
    private static final Map<Buffer, MarkdownInlineImages> STATES = new WeakHashMap<>();

    private final WeakReference<Buffer> bufferRef;
    private final Timer debounce;
    // A picture and the file's modification time it was read at, so a
    // changed file is noticed.
    private record Cached(BufferedImage image, long stamp) {}
    // Read pictures, by canonical path.
    private final Map<String, Cached> cache = new HashMap<>();
    // Paths whose load is in flight, so a rescan does not start another.
    private final Set<String> loading = new HashSet<>();
    // Paths that failed to read, by path and stamp, so a bad file is not
    // re-read and re-logged on every rescan.
    private final Set<String> failed = new HashSet<>();
    // The buffer's modCount at the last scan, so a caret move that edited
    // nothing does not schedule a rescan.
    private int lastScanModCount = -1;
    // A signature of the last registered decorations, so a rescan that
    // finds the same set does not repaint.
    private String lastSignature = "";

    private MarkdownInlineImages(Buffer buffer) {
        this.bufferRef = new WeakReference<>(buffer);
        this.debounce = new Timer(DEBOUNCE_MILLIS, e -> rescan());
        this.debounce.setRepeats(false);
    }

    /** The state for buffer, made on first use. */
    private static synchronized MarkdownInlineImages forBuffer(Buffer buffer) {
        return STATES.computeIfAbsent(buffer, MarkdownInlineImages::new);
    }

    /** Whether buffer is a Markdown buffer with inline images turned on. */
    private static boolean enabled(Buffer buffer) {
        return buffer != null
                && buffer.getMode() instanceof MarkdownMode
                && buffer.getBooleanProperty(Property.RENDER_INLINE_IMAGES);
    }

    /**
     * A Markdown buffer was activated: draw its inline images if the
     * preference is on. Called from the editor's activation path.
     */
    public static void onActivate(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        if (!enabled(buffer)) {
            clear(buffer);
            return;
        }
        forBuffer(buffer).rescan();
    }

    /**
     * A command ran in a Markdown buffer: reschedule a debounced rescan,
     * but only if the buffer actually changed, so a plain caret move does
     * not churn the display.
     */
    public static void onEdit(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        if (!enabled(buffer))
            return;
        final MarkdownInlineImages state = forBuffer(buffer);
        if (buffer.getModCount() == state.lastScanModCount)
            return;
        state.debounce.restart();
    }

    /**
     * A window showing a Markdown buffer was resized: the pictures are
     * scaled to the text width, so their heights change and the
     * decorations must be re-registered. Debounced so a resize drag does
     * not scan on every pixel.
     */
    public static void onResize(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        if (!enabled(buffer))
            return;
        forBuffer(buffer).debounce.restart();
    }

    /**
     * The toggle: flip the preference for the buffer and either draw the
     * pictures or clear them.
     */
    public static void toggle(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        if (!(buffer.getMode() instanceof MarkdownMode))
            return;
        final boolean now = !buffer.getBooleanProperty(Property.RENDER_INLINE_IMAGES);
        buffer.setProperty(Property.RENDER_INLINE_IMAGES, now);
        if (now)
            forBuffer(buffer).rescan();
        else
            clear(buffer);
    }

    /** Removes the inline-image decorations from buffer, if any. */
    public static void clear(Buffer buffer) {
        final MarkdownInlineImages state;
        synchronized (MarkdownInlineImages.class) {
            state = STATES.get(buffer);
        }
        if (state != null)
            state.clearOwn();
    }

    private synchronized void clearOwn() {
        debounce.stop();
        lastSignature = "";
        final Buffer buffer = bufferRef.get();
        if (buffer != null)
            buffer.getVirtualText().clear(OWNER);
    }

    // Finds the standalone image lines and registers a REPLACE for each,
    // loading any picture not already cached off the event thread.
    private void rescan() {
        final Buffer buffer = bufferRef.get();
        if (buffer == null)
            return;
        if (!enabled(buffer)) {
            clearOwn();
            return;
        }
        final int width = textWidth(buffer);
        // The lines inside a fence, comment or front matter, computed fresh
        // so an image line in one is not drawn even before the formatter
        // has painted the flags.
        final Set<Line> nonProse = MarkdownFormatter.nonProseLines(buffer.getFirstLine());
        final List<Found> found = new ArrayList<>();
        for (Line line = buffer.getFirstLine(); line != null; line = line.next()) {
            final String src = standaloneImageSrc(line, nonProse);
            if (src == null)
                continue;
            final File file = resolve(buffer, src);
            if (file != null)
                found.add(new Found(line, file));
        }
        // Split into what is cached now, what is loading, and what to read.
        final List<Found> toLoad = new ArrayList<>();
        final List<VirtualText> ready = new ArrayList<>();
        for (Found f : found) {
            final String key = f.key();
            final long stamp = f.stamp();
            final Cached cached = cache.get(key);
            if (cached != null && cached.stamp() == stamp) {
                ready.add(VirtualText.replace(f.line, f.line, new ImagePainter(cached.image(), width)));
            } else if (!loading.contains(key) && !failed.contains(failKey(key, stamp))) {
                toLoad.add(f);
            }
        }
        // The signature is of what is registered, so a load that lands
        // changes it but a no-op rescan does not, skipping the repaint.
        final StringBuilder signature = new StringBuilder();
        for (VirtualText v : ready)
            signature.append(v.line().lineNumber()).append(':').append(width).append(';');
        final String sig = signature.toString();
        if (!sig.equals(lastSignature)) {
            lastSignature = sig;
            buffer.getVirtualText().set(OWNER, ready);
        }
        lastScanModCount = buffer.getModCount();
        for (Found f : toLoad)
            load(f);
    }

    // Reads one picture off the event thread, then re-scans so it lands
    // with any others that arrived.
    private void load(Found f) {
        final String key = f.key();
        final long stamp = f.stamp();
        loading.add(key);
        Background.then("inline image", () -> read(f.file), (image) -> {
            loading.remove(key);
            final Buffer buffer = bufferRef.get();
            // The buffer may have closed or the toggle turned off meanwhile.
            if (buffer == null || !enabled(buffer))
                return;
            if (image == null) {
                failed.add(failKey(key, stamp));
                return;
            }
            cache.put(key, new Cached(image, stamp));
            rescan();
        });
    }

    private static String failKey(String key, long stamp) {
        return key + '@' + stamp;
    }

    private static BufferedImage read(File file) {
        try {
            return ImageIO.read(new java.io.File(file.canonicalPath()));
        }
        catch (Exception e) {
            // A malformed or unreadable picture is left as its link.
            Log.error(e);
            return null;
        }
    }

    // The src of a line that is nothing but one image, or null. A line
    // inside a fence, comment or front matter is not prose, so its image
    // is left as text.
    private static String standaloneImageSrc(Line line, Set<Line> nonProse) {
        if (nonProse.contains(line))
            return null;
        final Matcher m = STANDALONE.matcher(line.getText());
        if (!m.matches())
            return null;
        final String src = m.group(2);
        // Remote sources keep their link for now.
        if (src.startsWith("http://") || src.startsWith("https://") || src.contains(":"))
            return null;
        return src;
    }

    // The file a src names, relative to the buffer's directory, if it is a
    // readable local picture. A buffer opened over ssh or ftp has a remote
    // directory whose files cannot be read as pictures, so it is skipped.
    private static File resolve(Buffer buffer, String src) {
        final File dir = buffer.getCurrentDirectory();
        if (dir == null || dir.isRemote())
            return null;
        final File file = File.getInstance(dir, src);
        if (file == null || !file.isLocal() || !file.isFile())
            return null;
        final String lower = file.getName().toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTENSIONS)
            if (lower.endsWith(ext))
                return file;
        return null;
    }

    // The width available to a picture: the text area, less the gutter.
    private static int textWidth(Buffer buffer) {
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == buffer)
                return ed.getDisplay().getWidth() - Display.getGutterWidth(buffer);
        return 0;
    }

    // A found image and its line.
    private record Found(Line line, File file) {
        String key() {
            try {
                return file.canonicalPath();
            }
            catch (Exception e) {
                return file.getAbsolutePath();
            }
        }

        long stamp() {
            return file.lastModified();
        }
    }

    /**
     * Draws a cached picture scaled to the text width, capped at its
     * natural size so a small image is not blown up. The scaling is done
     * once, in the constructor, so paint is a plain blit and never
     * resamples on the event thread.
     */
    private static final class ImagePainter implements VirtualText.Painter {
        private final BufferedImage scaled;
        private final int height;

        ImagePainter(BufferedImage image, int availWidth) {
            final int natural = image.getWidth();
            final double scale = availWidth > 0 && natural > availWidth
                    ? (double) availWidth / natural
                    : 1.0;
            final int w = Math.max(1, (int) Math.round(natural * scale));
            final int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
            this.height = h;
            if (scale == 1.0) {
                this.scaled = image;
            }
            else {
                final BufferedImage out =
                        new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                final Graphics2D g = out.createGraphics();
                g.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image, 0, 0, w, h, null);
                g.dispose();
                this.scaled = out;
            }
        }

        @Override
        public void paint(Graphics2D g, int x, int y, int w, int h) {
            g.drawImage(scaled, x, y, null);
        }

        @Override
        public int getHeight() {
            return height;
        }
    }
}
