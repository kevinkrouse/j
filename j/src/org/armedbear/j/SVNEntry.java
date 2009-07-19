/*
 * SVNEntry.java
 *
 * Copyright (C) 2009 Kevin Krouse
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

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.StringReader;
import java.util.LinkedList;

public final class SVNEntry extends VersionControlEntry
{
    private String status;
    private String author;
    private String changelist;

    protected SVNEntry(Buffer buffer, String revision, String status, String author, String changelist) {
        super(buffer, revision);
        this.status = status;
        this.author = author;
        this.changelist = changelist;
    }

    public int getVersionControl() {
        return Constants.VC_SVN;
    }

    public String getStatusText() {
        return statusText(true);
    }

    public String getLongStatusText() {
        return statusText(false);
    }

    private String statusText(boolean brief) {
        FastStringBuffer sb = new FastStringBuffer("svn");
        if (status != null) {
            if (brief) {
                if (status.equals("added"))
                    sb.append(" A");
                else if (status.equals("modified"))
                    sb.append(" M");
                else if (status.equals("deleted"))
                    sb.append(" D");
                else if (status.equals("conflicted"))
                    sb.append(" C");
                else if (status.equals("ignored"))
                    sb.append(" I");
                else if (status.equals("replaced"))
                    sb.append(" R");
                else if (status.equals("unversioned"))
                    sb.append(" ?");
            }
            else
                sb.append(" ").append(status);
        }
        if (revision != null)
            sb.append(" r").append(revision);
        if (changelist != null)
            sb.append(" (").append(changelist).append(")");
        if (author != null)
            sb.append(" ").append(author);
        return sb.toString();
    }

    public static SVNEntry getEntry(Buffer buffer) {
        if (!SVN.checkSVNInstalled())
            return null;

        final File file = buffer.getFile();
        ShellCommand cmd = new ShellCommand(
                "svn -v --xml st " + VersionControl.maybeQuote(file.getName()),
                file.getParentFile());
        cmd.run();
        String output = cmd.getOutput();
        if (output == null || output.length() == 0)
            return null;

        XMLReader xmlReader = Utilities.getDefaultXMLReader();
        if (xmlReader == null)
            return null;
        Handler handler = new Handler();
        xmlReader.setContentHandler(handler);
        try {
            InputSource source = new InputSource(new StringReader(output));
            xmlReader.parse(source);
        }
        catch (Exception e) {
            Log.error(e);
            return null;
        }
        return new SVNEntry(buffer, handler.revision, handler.status, handler.author, handler.changelist);
    }

    private static class Handler extends DefaultHandler
    {
        private LinkedList<String> stack = new LinkedList<String>();

        public String changelist = null;
        public String revision = null;
        public String date = null;
        public String author = null;
        public String status = null;

        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            stack.push(localName);
            if (localName.equals("changelist")) {
                changelist = attributes.getValue("", "name");
            }
            else if (localName.equals("wc-status")) {
                status = attributes.getValue("", "item");
            }
            else if (localName.equals("commit")) {
                revision = attributes.getValue("", "revision");
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            String curr = stack.peek();
            if (curr.equals("author"))
                author = new String(ch, start, length);
            else if (curr.equals("date"))
                date = new String(ch, start, length);
        }

        public void endElement(String uri, String localName, String qName) throws SAXException {
            stack.pop();
            // since we only expect a single <entry>, we'll just ignore end elements
        }
    }
}
