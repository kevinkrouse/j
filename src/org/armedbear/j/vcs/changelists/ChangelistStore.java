/*
 * XmlChangelistStore.java
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

package org.armedbear.j.vcs.changelists;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.armedbear.j.Log;
import org.armedbear.j.util.AtomicFiles;
import org.armedbear.j.vcs.changelists.Changelists.Assignment;
import org.armedbear.j.vcs.changelists.Changelists.Change;
import org.armedbear.j.vcs.changelists.Changelists.Changelist;
import org.armedbear.j.vcs.changelists.Changelists.Part;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * A repository's changelists kept in a file of j's own, as XML the JDK
 * reads and writes: git's in the working tree's git directory,
 * j/changelists.xml, one for each worktree. Written whole, and only over
 * what it last read or wrote: another writer's since is not lost. A file
 * that isn't XML of changelists is moved aside, to changelists.xml.bad (or
 * .bad.1, and on), and the lists start again from the default; one that
 * can't be read for now, or a later j's, is kept, and not written over.
 *
 * <pre>
 * &lt;changelists version="1" active="Default"&gt;
 *   &lt;list name="Default"&gt;&lt;comment&gt;draft&lt;/comment&gt;&lt;/list&gt;
 *   &lt;file path="a.txt" list="Default"/&gt;
 *   &lt;file path="b.txt"&gt;
 *     &lt;change hash="..." row="3" rows="2" old-row="3" old-rows="1" list="Default"&gt;
 *       &lt;part from="0" to="1" list="Other"/&gt;
 *     &lt;/change&gt;
 *   &lt;/file&gt;
 * &lt;/changelists&gt;
 * </pre>
 */
public final class ChangelistStore {
    private static final int VERSION = 1;

    private final Path file;
    // The hash of the file's bytes as last read or written, null for no
    // file; and whether it may be written over.
    private byte[] seen;
    private boolean writable = true;

    public ChangelistStore(Path file) {
        this.file = file;
    }

    /** The file it keeps them in. */
    public Path file() {
        return file;
    }

    /** The lists as kept; the default alone if none are, or what is kept can't be read. */
    public Changelists read() {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        }
        catch (NoSuchFileException e) {
            seen = null;
            writable = true;
            return Changelists.initial();
        }
        catch (IOException e) {
            Log.error("Changelists in " + file + " can't be read now, nor are they written over: " + e);
            writable = false;
            return Changelists.initial();
        }
        seen = hash(bytes);
        writable = true;
        final DocumentBuilder builder;
        try {
            builder = builder();
        }
        catch (ParserConfigurationException e) {
            Log.error(e);
            writable = false;
            return Changelists.initial();
        }
        try {
            final Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            final int version = root.hasAttribute("version") ? Integer.parseInt(root.getAttribute("version")) : 1;
            if (version > VERSION) {
                // What this j knows of them, kept as they are.
                Log.error("Changelists in " + file + " are a later j's, version " + version + ": not written over");
                writable = false;
                try {
                    return of(root);
                }
                catch (IllegalArgumentException e) {
                    return Changelists.initial();
                }
            }
            return of(root);
        }
        catch (SAXException | IOException | IllegalArgumentException e) {
            Log.error("Changelists in " + file + " can't be read, moved aside: " + e);
            moveAside();
            return Changelists.initial();
        }
    }

    /**
     * Keeps lists in place of what was kept; false, said in the log, if it
     * can't, or the file changed since it was last read or written here:
     * then read it again, and change that.
     */
    public boolean write(Changelists lists) {
        if (!writable) {
            Log.error("Changelists in " + file + " not written: they couldn't be read");
            return false;
        }
        try {
            byte[] now;
            try {
                now = hash(Files.readAllBytes(file));
            }
            catch (NoSuchFileException e) {
                now = null;
            }
            if (!Arrays.equals(now, seen)) {
                Log.error("Changelists in " + file + " not written: changed since they were read");
                return false;
            }
            final byte[] bytes = bytes(lists);
            AtomicFiles.write(file, bytes, false);
            seen = hash(bytes);
            return true;
        }
        catch (IOException | TransformerException | ParserConfigurationException e) {
            Log.error("Changelists in " + file + " can't be written: " + e);
            return false;
        }
    }

    // The file moved aside, under a name no other has; none read since.
    private void moveAside() {
        Path aside = file.resolveSibling(file.getFileName() + ".bad");
        for (int n = 1; Files.exists(aside); n++)
            aside = file.resolveSibling(file.getFileName() + ".bad." + n);
        try {
            Files.move(file, aside);
            seen = null;
        }
        catch (IOException e) {
            Log.error(e);
            writable = false;
        }
    }

    private static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // lists as the file has them.
    private static byte[] bytes(Changelists lists) throws TransformerException, ParserConfigurationException {
        final Document doc = builder().newDocument();
        doc.appendChild(element(doc, lists));
        final TransformerFactory tf = TransformerFactory.newInstance();
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        final Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    // A parser that reads no DTD nor anything outside the file.
    private static DocumentBuilder builder() throws ParserConfigurationException {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        f.setXIncludeAware(false);
        return f.newDocumentBuilder();
    }

    // The lists root has; a list unknown, the default, as is a missing active.
    private static Changelists of(Element root) {
        if (!root.getTagName().equals("changelists"))
            throw new IllegalArgumentException("not changelists: " + root.getTagName());
        final List<Changelist> lists = new ArrayList<>();
        lists.add(new Changelist(Changelists.DEFAULT, ""));
        for (Element l : children(root, "list")) {
            final String name = required(l, "name");
            final List<Element> comments = children(l, "comment");
            final String comment = comments.isEmpty() ? "" : comments.get(0).getTextContent();
            lists.removeIf(x -> x.name().equals(name));
            if (name.equals(Changelists.DEFAULT))
                lists.add(0, new Changelist(name, comment));
            else
                lists.add(new Changelist(name, comment));
        }
        final List<String> names = lists.stream().map(Changelist::name).toList();
        final Map<String, Assignment> files = new LinkedHashMap<>();
        for (Element f : children(root, "file")) {
            final String whole = f.hasAttribute("list") ? known(names, f.getAttribute("list")) : null;
            final List<Change> changes = new ArrayList<>();
            for (Element c : children(f, "change")) {
                final List<Part> parts = new ArrayList<>();
                for (Element p : children(c, "part"))
                    parts.add(new Part(number(p, "from"), number(p, "to"), known(names, required(p, "list"))));
                changes.add(
                    new Change(required(c, "hash"),
                            number(c, "row"),
                            number(c, "rows"),
                            number(c, "old-row"),
                            number(c, "old-rows"),
                            known(names, required(c, "list")),
                            parts));
            }
            files.put(required(f, "path"), new Assignment(whole, changes));
        }
        return new Changelists(lists, known(names, root.getAttribute("active")), files);
    }

    private static Element element(Document doc, Changelists lists) {
        final Element root = doc.createElement("changelists");
        root.setAttribute("version", Integer.toString(VERSION));
        root.setAttribute("active", lists.active());
        for (Changelist l : lists.lists()) {
            final Element e = doc.createElement("list");
            e.setAttribute("name", l.name());
            if (!l.comment().isEmpty()) {
                final Element comment = doc.createElement("comment");
                comment.setTextContent(l.comment());
                e.appendChild(comment);
            }
            root.appendChild(e);
        }
        for (Map.Entry<String, Assignment> f : lists.files().entrySet()) {
            final Element e = doc.createElement("file");
            e.setAttribute("path", f.getKey());
            if (f.getValue().whole() != null)
                e.setAttribute("list", f.getValue().whole());
            for (Change c : f.getValue().changes()) {
                final Element ce = doc.createElement("change");
                ce.setAttribute("hash", c.hash());
                ce.setAttribute("row", Integer.toString(c.row()));
                ce.setAttribute("rows", Integer.toString(c.rows()));
                ce.setAttribute("old-row", Integer.toString(c.oldRow()));
                ce.setAttribute("old-rows", Integer.toString(c.oldRows()));
                ce.setAttribute("list", c.list());
                for (Part p : c.parts()) {
                    final Element pe = doc.createElement("part");
                    pe.setAttribute("from", Integer.toString(p.from()));
                    pe.setAttribute("to", Integer.toString(p.to()));
                    pe.setAttribute("list", p.list());
                    ce.appendChild(pe);
                }
                e.appendChild(ce);
            }
            root.appendChild(e);
        }
        return root;
    }

    private static List<Element> children(Element parent, String tag) {
        final List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && e.getTagName().equals(tag))
                out.add(e);
        return out;
    }

    private static String required(Element e, String attribute) {
        if (!e.hasAttribute(attribute))
            throw new IllegalArgumentException(e.getTagName() + " without " + attribute);
        return e.getAttribute(attribute);
    }

    private static int number(Element e, String attribute) {
        return Integer.parseInt(required(e, attribute));
    }

    // name, if one of names, else the default.
    private static String known(List<String> names, String name) {
        return names.contains(name) ? name : Changelists.DEFAULT;
    }
}
