/*
 * SvnCli.java
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.armedbear.j.vcs.VcsCommand;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.VcsRunner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * svn command lines, as the tools run them: never a prompt, messages in
 * English, and paths read as UTF-8, which svn needs for a non-ASCII one.
 * Tell errors apart by their codes (E155011).
 */
public final class SvnCli {
    // LC_ALL empty is unset, so the others apply; C.UTF-8 is glibc's own,
    // where a locale such as en_US.UTF-8 may not be installed for svn.
    private static final Map<String, String> ENV = Map.of(
        "LC_ALL",
        "",
        "LC_CTYPE",
        System.getProperty("os.name").startsWith("Mac") ? "UTF-8" : "C.UTF-8",
        "LC_MESSAGES",
        "C");

    // Tests' own config directory, so the user's config and credentials are never read.
    static volatile Path testConfigDir;

    private SvnCli() {}

    /** What every svn command runs with. */
    static List<String> options() {
        final Path config = testConfigDir;
        return config == null
                ? List.of("--non-interactive")
                : List.of("--non-interactive", "--config-dir", config.toString());
    }

    /** A command that only reads, run in dir. */
    public static VcsCommand read(Path dir, String... args) {
        final List<String> options = options();
        return new VcsCommand(dir, argv(options, args), options, ENV, null, false, null);
    }

    /** A command that changes the working copy at root. */
    public static VcsCommand write(Path root, String... args) {
        final List<String> options = options();
        return new VcsCommand(root, argv(options, args), options, ENV, null, true, null);
    }

    public static VcsResult run(VcsCommand command) {
        return VcsRunner.run(command);
    }

    /** svn and its options, then args, for a command run outside VcsRunner. */
    static List<String> argv(String... args) {
        return argv(options(), args);
    }

    private static List<String> argv(List<String> options, String... args) {
        final List<String> argv = new ArrayList<>(List.of("svn"));
        argv.addAll(options);
        argv.addAll(List.of(args));
        return argv;
    }

    /** path as an svn target: with a trailing @, so an @ in it isn't read as a peg revision. */
    static String target(String path) {
        return path.indexOf('@') >= 0 ? path + "@" : path;
    }

    /** svn's --xml output, parsed with no DTD nor anything outside it. */
    static Document xml(byte[] bytes) throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        f.setXIncludeAware(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    /** e's child elements named name. */
    static List<Element> children(Element e, String name) {
        final List<Element> list = new ArrayList<>();
        final NodeList nodes = e.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++)
            if (nodes.item(i) instanceof Element c && c.getTagName().equals(name))
                list.add(c);
        return list;
    }

    /** The text of e's first child element named name, or null. */
    static String childText(Element e, String name) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element c && c.getTagName().equals(name))
                return c.getTextContent();
        return null;
    }
}
