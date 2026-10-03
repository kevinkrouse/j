# GENERAL

J is a multifile, multiwindow programmer's editor written entirely in Java. It
features syntax highlighting for Java, C, C++, XML, HTML, CSS, JavaScript,
Lisp, Perl, PHP, Python, Ruby, Scheme, Tcl/Tk, Verilog, and VHDL, automatic
indentation, directory buffers, regular expressions, multifile find and
replace, autosave and crash recovery, undo/redo, FTP/HTTP support, email,
and multiple horizontal/vertical splits. All keyboard mappings can be
customized. Themes may be used to customize the editor's appearance.

This is Kevin Krouse's fork of Peter Graves original J editor.

# LICENSE

J is distributed under the GNU General Public License
(with a special exception described below).

A copy of GNU General Public License (GPL) is included in this distribution, in
the file COPYING.

Linking this software statically or dynamically with other modules is making a
combined work based on this software. Thus, the terms and conditions of the GNU
General Public License cover the whole combination.

As a special exception, the copyright holders of this software give you
permission to link this software with independent modules to produce an
executable, regardless of the license terms of these independent modules, and
to copy and distribute the resulting executable under terms of your choice,
provided that you also meet, for each linked independent module, the terms and
conditions of the license of that module. An independent module is a module
which is not derived from or based on this software. If you modify this
software, you may extend this exception to your version of the software, but
you are not obligated to do so. If you do not wish to do so, delete this
exception statement from your version.


# INSTALLATION

To build J you need JDK 25 and [babashka](https://babashka.org/).
Armed Bear Common Lisp is fetched from Maven Central on the first build.
If you use Nix, "nix develop" puts a suitable JDK, babashka and gcc on your PATH.

Run `bb build` to compile the source.
Run `bb tasks` to see a list of available targets.

