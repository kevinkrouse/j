/*
 * ModeList.java
 *
 * Copyright (C) 1998-2005 Peter Graves
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

import java.util.ArrayList;
import java.util.Iterator;

public final class ModeList implements Constants, Iterable<ModeListEntry>
{
    private static ModeList modeList;

    public static synchronized ModeList getInstance()
    {
        if (modeList == null)
            modeList = new ModeList();
        return modeList;
    }

    private ArrayList<ModeListEntry> list;

    private ModeList()
    {
        list = new ArrayList<ModeListEntry>();
        addEntry(ARCHIVE_MODE, ARCHIVE_MODE_NAME, "mode.archive.ArchiveMode", false, null);
        addEntry(ASM_MODE, ASM_MODE_NAME, "mode.asm.AsmMode", true, ".+\\.asm|.+\\.inc");
        addEntry(AUTOCONF_MODE, AUTOCONF_MODE_NAME, "mode.autoconf.AutoconfMode", true, "configure.ac|configure.in|aclocal.m4");
        addEntry(BINARY_MODE, BINARY_MODE_NAME, "mode.binary.BinaryMode", true, null);
        addEntry(CHECKIN_MODE, CHECKIN_MODE_NAME, "mode.checkin.CheckinMode", false, null);
        addEntry(COMPILATION_MODE, COMPILATION_MODE_NAME, "mode.compilation.CompilationMode", false, null);
        addEntry(CPP_MODE, CPP_MODE_NAME, "mode.cpp.CppMode", true, "(.+\\.cpp)|(.+\\.cxx)|(.+\\.cc)|(.+\\.hpp)|(.+\\.hxx)|(.+\\.h)");
        addEntry(CSS_MODE, CSS_MODE_NAME, "mode.css.CSSMode", true, ".+\\.css");
        addEntry(C_MODE, C_MODE_NAME, "mode.c.CMode", true, ".+\\.c");
        addEntry(DIFF_MODE, DIFF_MODE_NAME, "mode.diff.DiffMode", true, ".+\\.diff|.+\\.patch");
        addEntry(DIRECTORY_MODE, DIRECTORY_MODE_NAME, "mode.dir.DirectoryMode", false, null);
        addEntry(HTML_MODE, HTML_MODE_NAME, "mode.html.HtmlMode", true, ".+\\.html?");
        addEntry(IMAGE_MODE, IMAGE_MODE_NAME, "mode.image.ImageMode", false, ".+\\.gif|.+\\.jpe?g|.+\\.png");
        addEntry(JAVASCRIPT_MODE, JAVASCRIPT_MODE_NAME, "mode.js.JavaScriptMode", true, ".+\\.js");
        addEntry(JAVA_MODE, JAVA_MODE_NAME, "mode.java.JavaMode", true, ".+\\.java|.+\\.jad");
        addEntry(JDB_MODE, JDB_MODE_NAME, "mode.jdb.JdbMode", false, null);
        addEntry(LISP_MODE, LISP_MODE_NAME, "mode.lisp.LispMode", true, ".+\\.[ej]l|.*\\.li?sp|.*\\.cl|.*\\.emacs|.*\\.asd");
        addEntry(LISP_SHELL_MODE, LISP_SHELL_MODE_NAME, "mode.lisp.LispShellMode", false, null);
        addEntry(LIST_OCCURRENCES_MODE, LIST_OCCURRENCES_MODE_NAME, "mode.list.ListOccurrencesMode", false, null);
        addEntry(LIST_REGISTERS_MODE, LIST_REGISTERS_MODE_NAME, "mode.list.ListRegistersMode", false, null);
        addEntry(LIST_TAGS_MODE, LIST_TAGS_MODE_NAME, "mode.list.ListTagsMode", false, null);
        addEntry(MAILBOX_MODE, MAILBOX_MODE_NAME, "mail.MailboxMode", false, null);
        addEntry(MAKEFILE_MODE, MAKEFILE_MODE_NAME, "mode.make.MakefileMode", true, "makefile(\\.in)?");
        addEntry(MAN_MODE, MAN_MODE_NAME, "mode.man.ManMode", false, null);
        addEntry(MESSAGE_MODE, MESSAGE_MODE_NAME, "mail.MessageMode", false, null);
        addEntry(NEWS_GROUPS_MODE, NEWS_GROUPS_MODE_NAME, "mail.NewsGroupsMode", false, null);
        addEntry(NEWS_GROUP_SUMMARY_MODE, NEWS_GROUP_SUMMARY_MODE_NAME, "mail.NewsGroupSummaryMode", false, null);
        addEntry(OBJC_MODE, OBJC_MODE_NAME, "mode.objc.ObjCMode", true, ".+\\.m");
        addEntry(PERL_MODE, PERL_MODE_NAME, "mode.perl.PerlMode", true, ".+\\.p[lm]");
        addEntry(PHP_MODE, PHP_MODE_NAME, "mode.php.PHPMode", true, ".+\\.php[34]?");
        addEntry(PLAIN_TEXT_MODE, PLAIN_TEXT_MODE_NAME, "mode.text.PlainTextMode", true, null);
        addEntry(PROPERTIES_MODE, PROPERTIES_MODE_NAME, "mode.properties.PropertiesMode", true, "(.+\\.config)|(.+\\.co?nf)|(.+\\.cfg)|(.+\\.ini)|(.+\\.properties)|prefs");
        addEntry(PYTHON_MODE, PYTHON_MODE_NAME, "mode.python.PythonMode", true, ".+\\.py");
        addEntry(RUBY_MODE, RUBY_MODE_NAME, "mode.ruby.RubyMode", true, ".+\\.rb");
        addEntry(SCHEME_MODE, SCHEME_MODE_NAME, "mode.scheme.SchemeMode", true, ".+\\.sc[ehm]?|.+\\.ss");
        addEntry(SEND_MAIL_MODE, SEND_MAIL_MODE_NAME, "mail.SendMailMode", false, null);
        addEntry(SHELL_MODE, SHELL_MODE_NAME, "mode.shell.ShellMode", false, null);
        addEntry(SHELL_SCRIPT_MODE, SHELL_SCRIPT_MODE_NAME, "mode.sh.ShellScriptMode", true, ".+\\.[ck]?sh|\\.bashrc|\\.bash_profile");
        addEntry(TCL_MODE, TCL_MODE_NAME, "mode.tcl.TclMode", true, ".+\\.tcl");
        addEntry(VERILOG_MODE, VERILOG_MODE_NAME, "mode.verilog.VerilogMode", true, ".+\\.v");
        addEntry(VHDL_MODE, VHDL_MODE_NAME, "mode.vhdl.VHDLMode", true, ".+\\.vhdl?");
        addEntry(WEB_MODE, WEB_MODE_NAME, "mode.web.WebMode", false, null);
        addEntry(WORD_MODE, WORD_MODE_NAME, "mode.word.WordMode", false, null);
        addEntry(XML_MODE, XML_MODE_NAME, "mode.xml.XmlMode", true, ".+\\.x[msu]l|.+\\.dtd");
        addEntry(VCS_STATUS_MODE, VCS_STATUS_MODE_NAME, "vcs.StatusMode", false, null);
    }

    public synchronized Mode getMode(int id)
    {
        final ModeListEntry entry = getEntry(id);
        return entry == null ? null : entry.getMode(true);
    }

    public synchronized boolean modeAccepts(int id, String filename)
    {
        final ModeListEntry entry = getEntry(id);
        if (entry == null) {
            Debug.bug("ModeList.modeAccepts() invalid mode id " + id);
            return false;
        }
        return entry.accepts(filename);
    }

    public synchronized Mode getModeFromModeName(String modeName)
    {
        if (modeName != null) {
            for (int i = list.size(); i-- > 0;) {
                ModeListEntry entry = list.get(i);
                if (modeName.equalsIgnoreCase(entry.getDisplayName()))
                    return entry.getMode(true);
            }
            if (modeName.equalsIgnoreCase("asm"))
                return getMode(ASM_MODE);
            if (modeName.equalsIgnoreCase("objc"))
                return getMode(OBJC_MODE);
            if (modeName.equalsIgnoreCase("text"))
                return getMode(PLAIN_TEXT_MODE);
        }
        return null;
    }

    public synchronized int getModeIdFromModeName(String modeName)
    {
        if (modeName != null) {
            for (int i = list.size(); i-- > 0;) {
                ModeListEntry entry = list.get(i);
                if (modeName.equalsIgnoreCase(entry.getDisplayName()))
                    return entry.getId();
            }
            if (modeName.equalsIgnoreCase("asm"))
                return ASM_MODE;
            if (modeName.equalsIgnoreCase("objc"))
                return OBJC_MODE;
            if (modeName.equalsIgnoreCase("text"))
                return PLAIN_TEXT_MODE;
        }
        return -1;
    }

    public synchronized Mode getModeForFileName(String fileName)
    {
        int id = getModeIdForFileName(fileName);
        return id > 0 ? getMode(id) : null;
    }

    public synchronized int getModeIdForFileName(String fileName)
    {
        if (fileName != null) {
            for (int i = list.size(); i-- > 0;) {
                ModeListEntry entry = list.get(i);
                if (entry.accepts(fileName))
                    return entry.getId();
            }
        }
        return -1;
    }

    // Hard-coded for now.
    public synchronized Mode getModeForContentType(String contentType)
    {
        if (contentType != null) {
            if (contentType.toLowerCase().startsWith("text/css"))
                return getMode(CSS_MODE);
        }
        return null;
    }

    public synchronized final Iterator<ModeListEntry> iterator()
    {
        return list.iterator();
    }

    // Does not check for duplicate entries.
    private final void addEntry(int id, String displayName, String className,
        boolean selectable, String defaultFiles)
    {
        list.add(new ModeListEntry(id, displayName, className, selectable, defaultFiles));
    }

    private final ModeListEntry getEntry(int id)
    {
        for (int i = list.size(); i-- > 0;) {
            ModeListEntry entry = list.get(i);
            if (entry.getId() == id) {
                // Found entry.
                return entry;
            }
        }
        return null;
    }
}
