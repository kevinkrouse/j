/*
 * BuiltinModes.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.armedbear.j.Constants.*;

import java.util.List;
import org.armedbear.j.extension.ModeDescriptor;
import org.armedbear.j.extension.ModeProvider;
import org.armedbear.j.jdb.JdbMode;
import org.armedbear.j.mode.archive.ArchiveMode;
import org.armedbear.j.mode.binary.BinaryMode;
import org.armedbear.j.mode.c.CMode;
import org.armedbear.j.mode.checkin.CheckinMode;
import org.armedbear.j.mode.compilation.CompilationMode;
import org.armedbear.j.mode.cpp.CppMode;
import org.armedbear.j.mode.css.CSSMode;
import org.armedbear.j.mode.diff.DiffMode;
import org.armedbear.j.mode.dir.DirectoryMode;
import org.armedbear.j.mode.html.HtmlMode;
import org.armedbear.j.mode.image.ImageMode;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.js.JavaScriptMode;
import org.armedbear.j.mode.lisp.LispMode;
import org.armedbear.j.mode.lisp.LispShellMode;
import org.armedbear.j.mode.list.ListOccurrencesMode;
import org.armedbear.j.mode.list.ListRegistersMode;
import org.armedbear.j.mode.list.ListTagsMode;
import org.armedbear.j.mode.make.MakefileMode;
import org.armedbear.j.mode.man.ManMode;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.armedbear.j.mode.perl.PerlMode;
import org.armedbear.j.mode.php.PHPMode;
import org.armedbear.j.mode.properties.PropertiesMode;
import org.armedbear.j.mode.python.PythonMode;
import org.armedbear.j.mode.ruby.RubyMode;
import org.armedbear.j.mode.sh.ShellScriptMode;
import org.armedbear.j.mode.shell.ShellMode;
import org.armedbear.j.mode.text.PlainTextMode;
import org.armedbear.j.mode.web.WebMode;
import org.armedbear.j.mode.xml.XmlMode;
import org.armedbear.j.vcs.StatusMode;

/** The modes core provides. */
final class BuiltinModes implements ModeProvider {
    @Override
    public List<ModeDescriptor> modes() {
        return List.of(
            new ModeDescriptor(
                ARCHIVE_MODE,
                ARCHIVE_MODE_NAME,
                ArchiveMode.class,
                id -> ArchiveMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                BINARY_MODE,
                BINARY_MODE_NAME,
                BinaryMode.class,
                id -> BinaryMode.getMode(),
                true,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                CHECKIN_MODE,
                CHECKIN_MODE_NAME,
                CheckinMode.class,
                id -> CheckinMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                COMPILATION_MODE,
                COMPILATION_MODE_NAME,
                CompilationMode.class,
                id -> CompilationMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                CPP_MODE,
                CPP_MODE_NAME,
                CppMode.class,
                id -> CppMode.getMode(),
                true,
                "(.+\\.cpp)|(.+\\.cxx)|(.+\\.cc)|(.+\\.hpp)|(.+\\.hxx)|(.+\\.h)",
                List.of(),
                List.of("cpp", "c++", "cc", "cxx", "hpp", "hxx")
            ),
            new ModeDescriptor(
                CSS_MODE,
                CSS_MODE_NAME,
                CSSMode.class,
                id -> CSSMode.getMode(),
                true,
                ".+\\.css",
                List.of(),
                List.of("css")
            ),
            new ModeDescriptor(
                C_MODE,
                C_MODE_NAME,
                CMode.class,
                id -> CMode.getMode(),
                true,
                ".+\\.c",
                List.of(),
                List.of("c", "h")
            ),
            new ModeDescriptor(
                DIFF_MODE,
                DIFF_MODE_NAME,
                DiffMode.class,
                id -> DiffMode.getMode(),
                true,
                ".+\\.diff|.+\\.patch",
                List.of(),
                List.of("diff", "patch")
            ),
            new ModeDescriptor(
                DIRECTORY_MODE,
                DIRECTORY_MODE_NAME,
                DirectoryMode.class,
                id -> DirectoryMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                HTML_MODE,
                HTML_MODE_NAME,
                HtmlMode.class,
                id -> HtmlMode.getMode(),
                true,
                ".+\\.html?",
                List.of(),
                List.of("html", "htm", "xhtml")
            ),
            new ModeDescriptor(
                IMAGE_MODE,
                IMAGE_MODE_NAME,
                ImageMode.class,
                id -> ImageMode.getMode(),
                false,
                ".+\\.gif|.+\\.jpe?g|.+\\.png|.+\\.bmp|.+\\.tiff?",
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                JAVASCRIPT_MODE,
                JAVASCRIPT_MODE_NAME,
                JavaScriptMode.class,
                id -> JavaScriptMode.getMode(),
                true,
                ".+\\.js",
                List.of(),
                List.of("javascript", "js", "jsx", "mjs", "cjs", "json", "typescript", "ts")
            ),
            new ModeDescriptor(
                JAVA_MODE,
                JAVA_MODE_NAME,
                JavaMode.class,
                id -> JavaMode.getMode(),
                true,
                ".+\\.java|.+\\.jad",
                List.of(),
                List.of("java", "jad")
            ),
            new ModeDescriptor(
                JDB_MODE,
                JDB_MODE_NAME,
                JdbMode.class,
                id -> JdbMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                LISP_MODE,
                LISP_MODE_NAME,
                LispMode.class,
                id -> LispMode.getMode(),
                true,
                ".+\\.[ej]l|.*\\.li?sp|.*\\.cl|.*\\.emacs|.*\\.asd",
                List.of(),
                List.of("lisp", "elisp", "emacs-lisp", "el", "cl", "common-lisp", "clojure", "clj")
            ),
            new ModeDescriptor(
                LISP_SHELL_MODE,
                LISP_SHELL_MODE_NAME,
                LispShellMode.class,
                id -> LispShellMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                LIST_OCCURRENCES_MODE,
                LIST_OCCURRENCES_MODE_NAME,
                ListOccurrencesMode.class,
                id -> ListOccurrencesMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                LIST_REGISTERS_MODE,
                LIST_REGISTERS_MODE_NAME,
                ListRegistersMode.class,
                id -> ListRegistersMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                LIST_TAGS_MODE,
                LIST_TAGS_MODE_NAME,
                ListTagsMode.class,
                id -> ListTagsMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                MAKEFILE_MODE,
                MAKEFILE_MODE_NAME,
                MakefileMode.class,
                id -> MakefileMode.getMode(),
                true,
                "makefile(\\.in)?",
                List.of(),
                List.of("make", "makefile", "mk")
            ),
            new ModeDescriptor(
                MAN_MODE,
                MAN_MODE_NAME,
                ManMode.class,
                id -> ManMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                MARKDOWN_MODE,
                MARKDOWN_MODE_NAME,
                MarkdownMode.class,
                id -> MarkdownMode.getMode(),
                true,
                ".+\\.md|.+\\.markdown|.+\\.mkd",
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                PERL_MODE,
                PERL_MODE_NAME,
                PerlMode.class,
                id -> PerlMode.getMode(),
                true,
                ".+\\.p[lm]",
                List.of(),
                List.of("perl", "pl", "pm")
            ),
            new ModeDescriptor(
                PHP_MODE,
                PHP_MODE_NAME,
                PHPMode.class,
                id -> PHPMode.getMode(),
                true,
                ".+\\.php[34]?",
                List.of(),
                List.of("php")
            ),
            new ModeDescriptor(
                PLAIN_TEXT_MODE,
                PLAIN_TEXT_MODE_NAME,
                PlainTextMode.class,
                id -> PlainTextMode.getMode(),
                true,
                null,
                List.of("text"),
                List.of()
            ),
            new ModeDescriptor(
                PROPERTIES_MODE,
                PROPERTIES_MODE_NAME,
                PropertiesMode.class,
                id -> PropertiesMode.getMode(),
                true,
                "(.+\\.config)|(.+\\.co?nf)|(.+\\.cfg)|(.+\\.ini)|(.+\\.properties)|prefs",
                List.of(),
                List.of("properties", "ini", "conf", "cfg", "toml")
            ),
            new ModeDescriptor(
                PYTHON_MODE,
                PYTHON_MODE_NAME,
                PythonMode.class,
                id -> PythonMode.getMode(),
                true,
                ".+\\.py",
                List.of(),
                List.of("python", "py", "python3")
            ),
            new ModeDescriptor(
                RUBY_MODE,
                RUBY_MODE_NAME,
                RubyMode.class,
                id -> RubyMode.getMode(),
                true,
                ".+\\.rb",
                List.of(),
                List.of("ruby", "rb")
            ),
            new ModeDescriptor(
                SHELL_MODE,
                SHELL_MODE_NAME,
                ShellMode.class,
                id -> ShellMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                SHELL_SCRIPT_MODE,
                SHELL_SCRIPT_MODE_NAME,
                ShellScriptMode.class,
                id -> ShellScriptMode.getMode(),
                true,
                ".+\\.[ck]?sh|\\.bashrc|\\.bash_profile",
                List.of(),
                List.of("sh", "bash", "zsh", "ksh", "csh", "shell", "console", "shell-script")
            ),
            new ModeDescriptor(
                WEB_MODE,
                WEB_MODE_NAME,
                WebMode.class,
                id -> WebMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                XML_MODE,
                XML_MODE_NAME,
                XmlMode.class,
                id -> XmlMode.getMode(),
                true,
                ".+\\.x[msu]l|.+\\.dtd",
                List.of(),
                List.of("xml", "svg", "xsd", "xsl", "plist")
            ),
            new ModeDescriptor(
                VCS_STATUS_MODE,
                VCS_STATUS_MODE_NAME,
                StatusMode.class,
                id -> StatusMode.getMode(),
                false,
                null,
                List.of(),
                List.of()
            )
        );
    }
}
