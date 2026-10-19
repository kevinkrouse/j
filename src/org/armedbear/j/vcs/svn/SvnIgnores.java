/*
 * SvnIgnores.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.svn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilderFactory;
import org.armedbear.j.Log;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.ProcessRunner;
import org.armedbear.j.vcs.Ignores;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * What svn ignores in a working copy, by name: a directory's svn:ignore
 * patterns for its own entries, its svn:global-ignores for everything under
 * it, and the config's global-ignores everywhere, svn's own list when the
 * config doesn't set it; and what is in an ignored directory. The
 * properties are read once, with svn propget, in the background: until
 * they are, the event thread goes on without them, and whenRead tells
 * when to look again. Files svn tracks are not told apart: a pattern
 * matches them too.
 */
public final class SvnIgnores implements Ignores {
    // svn's global-ignores when ~/.subversion/config doesn't set it.
    static final String DEFAULT_GLOBAL_IGNORES =
            "*.o *.lo *.la *.al .libs *.so *.so.[0-9]* *.a *.pyc *.pyo __pycache__ *.rej *~ #*# .#* .*.swp .DS_Store [Tt]humbs.db";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Path root;
    private final List<Pattern> global;

    // svn:ignore and svn:global-ignores, by the directory that has them.
    private record Properties(Map<Path, List<Pattern>> ignore, Map<Path, List<Pattern>> globals) {}

    // Once read.
    private final CompletableFuture<Properties> properties;
    // When they were read, or -1 while they are being read.
    private volatile long readAt = -1;

    SvnIgnores(Path root) {
        this.root = root;
        global = patterns(configGlobalIgnores() != null ? configGlobalIgnores() : DEFAULT_GLOBAL_IGNORES);
        properties = CompletableFuture.supplyAsync(
            () -> new Properties(parse(propget(root, "svn:ignore")), parse(propget(root, "svn:global-ignores"))),
            command -> Background.start("svn propget", command));
        properties.thenRun(() -> readAt = System.currentTimeMillis());
    }

    /** With the config's global-ignores and the two properties' propget --xml output, for a test. */
    SvnIgnores(Path root, String configGlobal, String ignoreXml, String globalIgnoresXml) {
        this.root = root;
        global = patterns(configGlobal != null ? configGlobal : DEFAULT_GLOBAL_IGNORES);
        properties = CompletableFuture.completedFuture(new Properties(parse(ignoreXml), parse(globalIgnoresXml)));
        readAt = System.currentTimeMillis();
    }

    /** Whether these are still to be read again: being read, or read less than millis ago. */
    boolean fresh(long now, long millis) {
        final long at = readAt;
        return at < 0 || now - at < millis;
    }

    @Override
    public boolean ignored(Path path, boolean isDirectory) {
        if (!path.startsWith(root) || path.equals(root))
            return false;
        // The properties as read, or none yet on the event thread, which doesn't wait.
        final Properties props;
        if (properties.isDone() || !SwingUtilities.isEventDispatchThread())
            props = properties.join();
        else
            props = null;
        for (Path p = path; p != null && !p.equals(root); p = p.getParent())
            if (byPatterns(p, props))
                return true;
        return false;
    }

    // p's own name by the patterns, whatever the directories it is in.
    private boolean byPatterns(Path p, Properties props) {
        final String name = p.getFileName().toString();
        if (matches(global, name))
            return true;
        if (props == null)
            return false;
        final Path parent = p.getParent();
        if (matches(props.ignore().get(parent), name))
            return true;
        for (Path dir = parent; dir != null && dir.startsWith(root); dir = dir.getParent())
            if (matches(props.globals().get(dir), name))
                return true;
        return false;
    }

    @Override
    public void whenRead(Runnable r) {
        if (properties.isDone())
            return;
        properties.thenRun(() -> SwingUtilities.invokeLater(r));
    }

    private static boolean matches(List<Pattern> patterns, String name) {
        if (patterns != null)
            for (Pattern p : patterns)
                if (p.matcher(name).matches())
                    return true;
        return false;
    }

    // The property on every directory of the working copy that has it, as XML; "" if svn can't say.
    private static String propget(Path root, String property) {
        final ProcessRunner.Result r = ProcessRunner.of("svn", "propget", "-R", "--xml", property, root.toString())
                .discardErrors()
                .timeout(TIMEOUT)
                .run();
        return r.succeeded() ? r.output() : "";
    }

    /** Each target's patterns, by its path, from propget --xml output. */
    static Map<Path, List<Pattern>> parse(String xml) {
        final Map<Path, List<Pattern>> map = new HashMap<>();
        if (xml == null || xml.isBlank())
            return map;
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final NodeList targets = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getElementsByTagName("target");
            for (int i = 0; i < targets.getLength(); i++) {
                final Element target = (Element) targets.item(i);
                final NodeList properties = target.getElementsByTagName("property");
                final List<Pattern> patterns = new ArrayList<>();
                for (int j = 0; j < properties.getLength(); j++)
                    patterns.addAll(patterns(properties.item(j).getTextContent()));
                map.put(Path.of(target.getAttribute("path")), patterns);
            }
        }
        catch (Exception e) {
            Log.debug(e);
        }
        return map;
    }

    // Patterns separated by white space, as svn reads them; one that isn't a glob left out.
    static List<Pattern> patterns(String s) {
        final List<Pattern> patterns = new ArrayList<>();
        for (String glob : s.strip().split("\\s+")) {
            if (glob.isEmpty())
                continue;
            try {
                patterns.add(Pattern.compile(Ignores.globToRegex(glob)));
            }
            catch (PatternSyntaxException e) {
                Log.debug(e);
            }
        }
        return patterns;
    }

    // [miscellany] global-ignores in ~/.subversion/config, or null if it isn't set.
    private static String configGlobalIgnores() {
        final Path config = Path.of(System.getProperty("user.home"), ".subversion", "config");
        try {
            return Files.isRegularFile(config) ? configGlobalIgnores(Files.readAllLines(config)) : null;
        }
        catch (IOException e) {
            Log.debug(e);
            return null;
        }
    }

    static String configGlobalIgnores(List<String> lines) {
        boolean miscellany = false;
        StringBuilder value = null;
        for (String line : lines) {
            if (value != null) {
                // A value goes on over lines that start with white space.
                if (!line.isEmpty() && Character.isWhitespace(line.charAt(0)) && !line.isBlank()) {
                    value.append(' ').append(line.strip());
                    continue;
                }
                return value.toString();
            }
            final String s = line.strip();
            if (s.startsWith("["))
                miscellany = s.equals("[miscellany]");
            else if (miscellany && line.startsWith("global-ignores")) {
                final int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).strip().equals("global-ignores"))
                    value = new StringBuilder(line.substring(eq + 1).strip());
            }
        }
        return value != null ? value.toString() : null;
    }
}
