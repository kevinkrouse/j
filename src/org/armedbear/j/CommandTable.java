/*
 * CommandTable.java
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.armedbear.j.jdb.Jdb;
import org.armedbear.j.jdb.JdbCommands;
import org.armedbear.j.mode.archive.ArchiveMode;
import org.armedbear.j.mode.binary.BinaryMode;
import org.armedbear.j.mode.checkin.CheckinBuffer;
import org.armedbear.j.mode.checkpath.CheckPath;
import org.armedbear.j.mode.compilation.CompilationBuffer;
import org.armedbear.j.mode.compilation.CompilationCommands;
import org.armedbear.j.mode.diff.DiffMode;
import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.armedbear.j.mode.dir.DirectoryMode;
import org.armedbear.j.mode.html.HtmlMode;
import org.armedbear.j.mode.image.ImageMode;
import org.armedbear.j.mode.lisp.LispMode;
import org.armedbear.j.mode.lisp.LispShellBuffer;
import org.armedbear.j.mode.lisp.LispShellMode;
import org.armedbear.j.mode.list.ListOccurrencesBuffer;
import org.armedbear.j.mode.list.ListTagsDialog;
import org.armedbear.j.mode.list.ListTagsMode;
import org.armedbear.j.mode.man.ManMode;
import org.armedbear.j.mode.markdown.MarkdownFolding;
import org.armedbear.j.mode.markdown.MarkdownTasks;
import org.armedbear.j.mode.php.PHPMode;
import org.armedbear.j.mode.web.WebBuffer;
import org.armedbear.j.mode.web.WebMode;
import org.armedbear.j.mode.xml.XmlMode;
import org.armedbear.j.vcs.StatusMode;
import org.armedbear.j.vcs.git.Git;
import org.armedbear.j.vcs.svn.SVN;

public class CommandTable {
    // The default load factor is 0.75, so an initial capacity of 600 will
    // accommodate 450 entries without rehashing.
    private static final int INITIAL_CAPACITY = 600;

    // Concurrent because extensions register into it at startup while a lazy
    // init() may be running on another thread.
    private static Map<String, Command> map;

    public static final Command getCommand(String name) {
        if (name == null)
            return null;
        init();
        return map.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Register a command supplied by an extension.
     *
     * <p>The owning class is passed as a Class, not a name: core's loader
     * cannot see an extension's classes. The method must be public static,
     * taking no arguments, a single String, or either.
     */
    public static void registerCommand(String name, Class<?> owner, String methodName) {
        if (name == null || owner == null || methodName == null)
            throw new IllegalArgumentException("name, owner and method are all required");
        init();
        map.put(name.toLowerCase(Locale.ROOT), new Command(name, owner, methodName));
    }

    private static synchronized void init() {
        if (map == null) {
            map = new ConcurrentHashMap<>(INITIAL_CAPACITY);

            add("adjacentWindow", null, (e, s) -> WindowCommands.adjacentWindow(e, s));
            add("backspace", EditCommands::backspace);
            add("backwardParagraph", e -> Paragraphs.backwardParagraph());
            add("backwardSection", e -> Paragraphs.backwardSection(), (e, s) -> Paragraphs.backwardSection(s));
            add("backwardSentence", e -> Sentences.backwardSentence());
            add("balanceWindows", WindowCommands::balanceWindows);
            add("bob", MotionCommands::bob);
            add("bol", MotionCommands::bol);
            add("bottom", MotionCommands::bottom);
            add("cancelBackgroundProcess", Editor::cancelBackgroundProcess);
            add("clearSearchHighlight", Editor::clearSearchHighlight);
            add("closeAll", FileCommands::closeAll);
            add("closeOthers", FileCommands::closeOthers);
            add("closeParen", ElectricCommands::closeParen);
            add("commentRegion", IndentCommands::commentRegion);
            add("copyAppend", ClipboardCommands::copyAppend);
            add("copyPath", ClipboardCommands::copyPath);
            add("copyRegion", ClipboardCommands::copyRegion);
            add("cppFindMatch", CaretCommands::cppFindMatch);
            add("cycleIndentSize", IndentCommands::cycleIndentSize);
            add("cyclePaste", ClipboardCommands::cyclePaste);
            add("cycleTabWidth", IndentCommands::cycleTabWidth);
            add("defaultMode", Editor::defaultMode);
            add("delete", EditCommands::delete);
            add("deleteWordLeft", ClipboardCommands::deleteWordLeft);
            add("deleteWordRight", ClipboardCommands::deleteWordRight);
            add("dirBrowseFile", BufferCommands::dirBrowseFile);
            add("dirCopyFile", BufferCommands::dirCopyFile);
            add("dirDeleteFiles", BufferCommands::dirDeleteFiles);
            add("dirGetFile", BufferCommands::dirGetFile);
            add("dirHome", BufferCommands::dirHome);
            add("dirHomeDir", BufferCommands::dirHomeDir);
            add("dirMoveFile", BufferCommands::dirMoveFile);
            add("dirProjectDir", ProjectCommands::dirProjectDir);
            add("dirRescan", BufferCommands::dirRescan);
            add("dirSortByDate", e -> DirectoryMode.dirSortByDate());
            add("dirSortByName", e -> DirectoryMode.dirSortByName());
            add("dirSortBySize", e -> DirectoryMode.dirSortBySize());
            add("dirTagFile", BufferCommands::dirTagFile);
            add("dirUpDir", BufferCommands::dirUpDir);
            add("down", MotionCommands::down);
            add("dropBookmark", JumpCommands::dropBookmark, (e, s) -> JumpCommands.dropBookmark(e, s));
            add("dropTemporaryMarker", JumpCommands::dropTemporaryMarker);
            add("electricCloseAngleBracket", ElectricCommands::electricCloseAngleBracket);
            add("electricCloseBrace", ElectricCommands::electricCloseBrace);
            add("electricColon", ElectricCommands::electricColon);
            add("electricOpenBrace", ElectricCommands::electricOpenBrace);
            add("electricPound", ElectricCommands::electricPound);
            add("electricQuote", ElectricCommands::electricQuote);
            add("electricSemi", ElectricCommands::electricSemi);
            add("electricStar", ElectricCommands::electricStar);
            add("end", MotionCommands::end);
            add("enlargeWindow", WindowCommands::enlargeWindow);
            add("eob", MotionCommands::eob);
            add("eol", MotionCommands::eol);
            add("escape", Editor::escape);
            add("executeCommand", Editor::executeCommand, (e, s) -> e.executeCommand(s));
            add("findCharInLine", null, (e, s) -> CaretCommands.findCharInLine(s));
            add("findCharInLineBackward", null, (e, s) -> CaretCommands.findCharInLineBackward(s));
            add("findFirstOccurrence", SearchCommands::findFirstOccurrence);
            add("findMatchingChar", CaretCommands::findMatchingChar);
            add("findNext", SearchCommands::findNext);
            add("findNextWord", SearchCommands::findNextWord);
            add("findPrev", SearchCommands::findPrev);
            add("findPrevWord", SearchCommands::findPrevWord);
            add("findUnmatchedBracket", null, (e, s) -> CaretCommands.findUnmatchedBracket(s));
            add("forwardParagraph", e -> Paragraphs.forwardParagraph());
            add("forwardSection", e -> Paragraphs.forwardSection(), (e, s) -> Paragraphs.forwardSection(s));
            add("forwardSentence", e -> Sentences.forwardSentence());
            add("fold", FoldCommands::fold);
            add("foldAll", FoldCommands::foldAll);
            add("foldMethods", FoldCommands::foldMethods);
            add("foldRegion", FoldCommands::foldRegion);
            add("gotoBookmark", JumpCommands::gotoBookmark, (e, s) -> JumpCommands.gotoBookmark(e, s));
            add("gotoTemporaryMarker", JumpCommands::gotoTemporaryMarker);
            add("gotoWindow", null, (e, s) -> WindowCommands.gotoWindow(e, s));
            add("home", MotionCommands::home);
            add("httpDeleteCookies", FileCommands::httpDeleteCookies);
            add("incrementalFind", SearchCommands::incrementalFind);
            add("duplicateLines", e -> Lines.duplicateLines());
            add("indentLine", IndentCommands::indentLine);
            add("indentLineOrRegion", IndentCommands::indentLineOrRegion);
            add("indentRegion", IndentCommands::indentRegion);
            add("insertBraces", ElectricCommands::insertBraces);
            add("insertByte", EditCommands::insertByte);
            add("insertChar", Editor::insertChar);
            add("insertKeyText", EditCommands::insertKeyText);
            add("insertParentheses", ElectricCommands::insertParentheses);
            add("insertString", null, (e, s) -> e.insertString(s));
            add("insertTab", IndentCommands::insertTab);
            add("justOneSpace", IndentCommands::justOneSpace);
            add("killAppend", ClipboardCommands::killAppend);
            add("killBuffer", BufferCommands::killBuffer);
            add("killFrame", WindowCommands::killFrame);
            add("joinLines", e -> Lines.joinLines(), (e, s) -> Lines.joinLines(s));
            add("killLine", ClipboardCommands::killLine);
            // synonym for unsplitAllWindows
            add("killOtherWindows", WindowCommands::unsplitAllWindows);
            add("killRegion", ClipboardCommands::killRegion);
            add("killWindow", WindowCommands::killWindow, (e, s) -> WindowCommands.killWindow(e, s));
            add("killWordLeft", ClipboardCommands::killWordLeft);
            add("killWordRight", ClipboardCommands::killWordRight);
            add("left", MotionCommands::left);
            add("mode", Editor::mode, (e, s) -> e.mode(s));
            add("moveLinesDown", e -> Lines.moveLinesDown());
            add("moveToWindowBottom", e -> CaretCommands.moveToWindowBottom());
            add("moveToWindowMiddle", e -> CaretCommands.moveToWindowMiddle());
            add("moveToWindowTop", e -> CaretCommands.moveToWindowTop());
            add("moveLinesUp", e -> Lines.moveLinesUp());
            add("movePastCloseAndReindent", ElectricCommands::movePastCloseAndReindent);
            add("mousePaste", ClipboardCommands::mousePaste);
            add("mouseMoveDotToPoint", Editor::mouseMoveDotToPoint);
            add("mouseSelect", Editor::mouseSelect);
            add("mouseSelectColumn", Editor::mouseSelectColumn);
            add("mouseShowContextMenu", Editor::mouseShowContextMenu);
            add("newBuffer", BufferCommands::newBuffer);
            add("newFrame", Editor::newFrame);
            add("newline", EditCommands::newline);
            add("newlineAndIndent", EditCommands::newlineAndIndent);
            add("nextBuffer", BufferCommands::nextBuffer);
            add("nextFrame", WindowCommands::nextFrame);
            add("nextWindow", WindowCommands::nextWindow, (e, s) -> WindowCommands.nextWindow(e, s));
            add("offset", Editor::offset);
            add("openFile", FileCommands::openFile);
            add("openFileInOtherWindow", FileCommands::openFileInOtherWindow);
            add("openFileInSplit", null, (e, s) -> FileCommands.openFileInSplit(e, s));
            add("openFileInVsplit", null, (e, s) -> FileCommands.openFileInVsplit(e, s));
            add("otherWindow", WindowCommands::otherWindow);
            add("previousWindow", WindowCommands::previousWindow, (e, s) -> WindowCommands.previousWindow(e, s));
            add("priorWindow", WindowCommands::priorWindow);
            add("pageDown", MotionCommands::pageDown, (e, s) -> MotionCommands.pageDown(e, s));
            add("pageDownOtherWindow", MotionCommands::pageDownOtherWindow);
            add("pageUp", MotionCommands::pageUp, (e, s) -> MotionCommands.pageUp(e, s));
            add("pageUpOtherWindow", MotionCommands::pageUpOtherWindow);
            add("paste", ClipboardCommands::paste, (e, s) -> ClipboardCommands.paste(e, s));
            add("pasteColumn", ClipboardCommands::pasteColumn);
            add("popPosition", Editor::popPosition);
            add("prevBuffer", BufferCommands::prevBuffer, (e, s) -> BufferCommands.prevBuffer(e, s));
            add("pushPosition", Editor::pushPosition);
            add("quit", FileCommands::quit);
            add("redo", EditCommands::redo);
            add("resetDisplay", e -> Editor.resetDisplay());
            add("revertBuffer", FileCommands::revertBuffer);
            add("right", MotionCommands::right);
            add("save", FileCommands::save);
            add("saveAll", FileCommands::saveAll);
            add("saveAllExit", FileCommands::saveAllExit);
            add("saveAs", FileCommands::saveAs, (e, s) -> FileCommands.saveAs(e, s));
            add("saveCopy", FileCommands::saveCopy, (e, s) -> FileCommands.saveCopy(e, s));
            add("selectAll", MotionCommands::selectAll);
            add("selectBob", MotionCommands::selectBob);
            add("selectDown", MotionCommands::selectDown);
            add("selectEnd", MotionCommands::selectEnd);
            add("selectEob", MotionCommands::selectEob);
            add("selectHome", MotionCommands::selectHome);
            add("selectLeft", MotionCommands::selectLeft);
            add("selectPageDown", MotionCommands::selectPageDown);
            add("selectPageUp", MotionCommands::selectPageUp);
            add("selectRight", MotionCommands::selectRight);
            add("shiftLinesLeft", e -> Lines.shiftLinesLeft());
            add("shiftLinesRight", e -> Lines.shiftLinesRight());
            add("selectSyntax", CaretCommands::selectSyntax);
            add("selectUp", MotionCommands::selectUp);
            add("selectWord", MotionCommands::selectWord);
            add("selectWordLeft", MotionCommands::selectWordLeft, (e, s) -> MotionCommands.selectWordLeft(e, s));
            add("selectWordRight", MotionCommands::selectWordRight, (e, s) -> MotionCommands.selectWordRight(e, s));
            add("setEncoding", FileCommands::setEncoding, (e, s) -> FileCommands.setEncoding(e, s));
            add("shrinkWindowIfLargerThanBuffer", WindowCommands::shrinkWindowIfLargerThanBuffer);
            add("sidebarListBuffers", WindowCommands::sidebarListBuffers);
            add("sidebarListTags", WindowCommands::sidebarListTags);
            add("slideIn", IndentCommands::slideIn);
            add("slideOut", IndentCommands::slideOut);
            add("splitWindow", WindowCommands::splitWindow, (e, s) -> WindowCommands.splitWindow(e, s));
            add("stamp", EditCommands::stamp);
            add("tab", IndentCommands::tab);
            add("tempBufferQuit", BufferCommands::tempBufferQuit);
            add("textMode", Editor::textMode);
            add("tillCharInLine", null, (e, s) -> CaretCommands.tillCharInLine(s));
            add("tillCharInLineBackward", null, (e, s) -> CaretCommands.tillCharInLineBackward(s));
            add("toBottom", MotionCommands::toBottom);
            add("toCenter", MotionCommands::toCenter);
            add("toTop", MotionCommands::toTop);
            add("toggleCaseRegion", e -> RegionCommands.toggleCaseRegion());
            add("toggleFold", FoldCommands::toggleFold);
            add("toggleSidebar", WindowCommands::toggleSidebar);
            add("top", MotionCommands::top);
            add("uncommentRegion", IndentCommands::uncommentRegion);
            add("undo", EditCommands::undo);
            add("unfold", FoldCommands::unfold);
            add("unfoldAll", FoldCommands::unfoldAll);
            add("unfoldHere", FoldCommands::unfoldHere);
            add("unsplitAllWindows", WindowCommands::unsplitAllWindows);
            add("unsplitWindow", WindowCommands::unsplitWindow);
            add("unwrapParagraph", IndentCommands::unwrapParagraph);
            add("up", MotionCommands::up);
            add("visibleTabs", WindowCommands::visibleTabs);
            add("vsplitWindow", WindowCommands::vsplitWindow, (e, s) -> WindowCommands.vsplitWindow(e, s));
            add("whatChar", EditCommands::whatChar);
            add("windowDown", MotionCommands::windowDown);
            add("windowUp", MotionCommands::windowUp);
            add("wordLeft", MotionCommands::wordLeft, (e, s) -> MotionCommands.wordLeft(e, s));
            add("wordRight", MotionCommands::wordRight, (e, s) -> MotionCommands.wordRight(e, s));
            add("wrapParagraph", IndentCommands::wrapParagraph);
            add("wrapParagraphsInRegion", IndentCommands::wrapParagraphsInRegion);
            add("wrapRegion", IndentCommands::wrapRegion);

            // Commands implemented in other classes.
            add("about", e -> AboutDialog.about());
            add("alias", e -> AliasDialog.alias(), (e, s) -> AliasDialog.alias(s));
            add("alignStrings", e -> AlignStrings.alignStrings(), (e, s) -> AlignStrings.alignStrings(s));
            add("apropos", e -> Help.apropos(), (e, s) -> Help.apropos(s));
            add("archiveOpenFile", e -> ArchiveMode.archiveOpenFile());
            add("backwardSexp", e -> LispMode.backwardSexp());
            add("backwardUpList", e -> LispMode.backwardUpList());
            add("binaryMode", e -> BinaryMode.binaryMode());
            add("browseFileAtDot", e -> BrowseFile.browseFileAtDot());
            add("centerTag", e -> TagCommands.centerTag());
            add("changes", e -> ChangeMarks.changes());
            add("checkPath", e -> CheckPath.checkPath());
            add("chmod", e -> DirectoryBuffer.chmod());
            add("clearRegister", e -> Registers.clearRegister(), (e, s) -> Registers.clearRegister(s));
            add("compile", e -> CompilationCommands.compile(), (e, s) -> CompilationCommands.compile(s));
            add("compileAndLoadLispFile", e -> LispMode.compileAndLoadLispFile());
            add("compileLispFile", e -> LispMode.compileLispFile());
            add("copyLink", e -> WebBuffer.copyLink());
            add("copyXPath", e -> XmlMode.copyXPath());
            add("decodeRegion", e -> RegionCommands.decodeRegion());
            add("decrementNumber", e -> NumberCommands.decrementNumber(), (e, s) -> NumberCommands.decrementNumber(s));
            add("defaultKeyMaps", e -> KeyMap.defaultKeyMaps());
            add("describe", e -> LispShellMode.describe(), (e, s) -> LispShellMode.describe(s));
            add("describeBindings", e -> Help.describeBindings());
            add("describeKey", e -> DescribeKeyDialog.describeKey());
            add("detabRegion", e -> RegionCommands.detabRegion());
            add("diff", e -> DiffMode.diff(), (e, s) -> DiffMode.diff(s));
            add("diffGotoFile", e -> DiffMode.gotoFile());
            add("dir", e -> DirectoryBuffer.dir());
            add("dirBack", e -> DirectoryBuffer.dirBack());
            add("dirCycleSortBy", e -> DirectoryBuffer.dirCycleSortBy());
            add("dirDoShellCommand", e -> DirectoryBuffer.dirDoShellCommand());
            add("dirForward", e -> DirectoryBuffer.dirForward());
            add("dirLimit", e -> DirectoryBuffer.dirLimit(), (e, s) -> DirectoryBuffer.dirLimit(s));
            add("dirOpenFile", e -> DirectoryBuffer.dirOpenFile());
            add("dirOpenFileAndKillDirectory", e -> DirectoryBuffer.dirOpenFileAndKillDirectory());
            add("dirUnlimit", e -> DirectoryBuffer.dirUnlimit());
            add("doShellCommandOnRegion", e -> RegionCommands.doShellCommandOnRegion());
            add("downList", e -> LispMode.downList());
            add("editPrefs", e -> Preferences.editPrefs());
            add("editRegister", e -> Registers.editRegister(), (e, s) -> Registers.editRegister(s));
            add("endMacro", e -> Macro.endMacro());
            add("entabRegion", e -> RegionCommands.entabRegion());
            add("evalDefunLisp", e -> LispMode.evalDefunLisp());
            add("compileDefunLisp", e -> LispMode.compileDefunLisp());
            add("electricCloseParen", e -> LispShellMode.electricCloseParen());
            add("evalRegionLisp", e -> LispMode.evalRegionLisp());
            add("expand", e -> Expansion.expand());
            add("find", e -> FindDialog.find());
            add("findInFiles", e -> FindInFiles.findInFiles());
            add("findOccurrenceAtDot", e -> ListOccurrencesBuffer.findOccurrenceAtDot());
            add("findOccurrenceAtDotAndKillList", e -> ListOccurrencesBuffer.findOccurrenceAtDotAndKillList());
            add("findTag", e -> TagCommands.findTag());
            add("findTagAtDot", e -> TagCommands.findTagAtDot());
            add("findTagAtDotOtherWindow", e -> TagCommands.findTagAtDotOtherWindow());
            add("finish", e -> CheckinBuffer.finish());
            add("foldHeadings", e -> MarkdownFolding.foldHeadings(), (e, s) -> MarkdownFolding.foldHeadings(s));
            add("followContext", e -> FollowContextTask.followContext());
            add("followLink", e -> FollowLink.followLink());
            add("followLinkOrTask", e -> MarkdownTasks.followLinkOrTask());
            add("forwardSexp", e -> LispMode.forwardSexp());
            add("git", e -> Git.git(), (e, s) -> Git.git(s));
            add("google", e -> WebMode.google(), (e, s) -> WebMode.google(s));
            add("gotoFile", e -> GotoFile.gotoFile());
            add("help", e -> Help.help(), (e, s) -> Help.help(s));
            add("htmlBold", e -> HtmlMode.htmlBold());
            add("htmlElectricEquals", e -> HtmlMode.htmlElectricEquals());
            add("htmlEndTag", e -> HtmlMode.htmlEndTag());
            add("htmlFindMatch", e -> HtmlMode.htmlFindMatch());
            add("htmlInsertMatchingEndTag", e -> HtmlMode.htmlInsertMatchingEndTag());
            add("htmlInsertTag", e -> HtmlMode.htmlInsertTag(), (e, s) -> HtmlMode.htmlInsertTag(s));
            add("htmlStartTag", e -> HtmlMode.htmlStartTag());
            add("httpShowHeaders", e -> HttpLoadProcess.httpShowHeaders());
            add("hyperspec", e -> LispMode.hyperspec(), (e, s) -> LispMode.hyperspec(s));
            add("iList", e -> IList.iList(), (e, s) -> IList.iList(s));
            add("imageCycleBackground", e -> ImageMode.imageCycleBackground());
            add("imageFit", e -> ImageMode.imageFit());
            add("imageRestore", e -> ImageMode.imageRestore());
            add("imageZoomIn", e -> ImageMode.imageZoomIn());
            add("imageZoomOut", e -> ImageMode.imageZoomOut());
            add("incrementNumber", e -> NumberCommands.incrementNumber(), (e, s) -> NumberCommands.incrementNumber(s));
            add("insertRegister", e -> Registers.insertRegister(), (e, s) -> Registers.insertRegister(s));
            add("jdkHelp", e -> JDKHelp.jdkHelp(), (e, s) -> JDKHelp.jdkHelp(s));
            add("jumpBack", e -> JumpList.jumpBack());
            add("jumpForward", e -> JumpList.jumpForward());
            add("jumpToColumn", e -> JumpCommands.jumpToColumn());
            add("jumpToLine", e -> JumpCommands.jumpToLine());
            add("jumpToOffset", e -> JumpCommands.jumpToOffset());
            add("jumpToTag", e -> ListTagsMode.jumpToTag());
            add("jumpToTagAndKillList", e -> ListTagsMode.jumpToTagAndKillList());
            add("killCompilation", e -> CompilationBuffer.killCompilation());
            add("lisp", e -> LispShellBuffer.lisp(), (e, s) -> LispShellBuffer.lisp(s));
            add("listFiles", e -> FindInFiles.listFiles());
            add("lispShellEnter", e -> LispShellMode.enter());
            add("lispFindMatchingChar", e -> LispMode.lispFindMatchingChar());
            add("lispSelectSyntax", e -> LispMode.lispSelectSyntax());
            add("listIncludes", e -> CheckPath.listIncludes());
            add("listMatchingTags", e -> TagCommands.listMatchingTags(), (e, s) -> TagCommands.listMatchingTags(s));
            add("listMatchingTagsAtDot", e -> TagCommands.listMatchingTagsAtDot());
            add("listOccurrences", e -> ListOccurrencesBuffer.listOccurrences());
            add("listOccurrencesOfPatternAtDot", e -> ListOccurrencesBuffer.listOccurrencesOfPatternAtDot());
            add("listProperties", e -> PropertiesDialog.listProperties());
            add("listRegisters", e -> Registers.listRegisters());
            add("listStyles", e -> ListStyles.listStyles(), (e, s) -> ListStyles.listStyles(s));
            add("listTags", e -> ListTagsDialog.listTags());
            add("listThreads", e -> Debug.listThreads());
            add("loadLispFile", e -> LispMode.loadLispFile());
            add("loadSession", e -> Session.loadSession(), (e, s) -> Session.loadSession(s));
            add("lowerCaseRegion", e -> RegionCommands.lowerCaseRegion());
            add("makeTagFile", e -> TagCommands.makeTagFile());
            add("man", e -> ManMode.man(), (e, s) -> ManMode.man(s));
            add("manFollowLink", e -> ManMode.manFollowLink());
            add("markSexp", e -> LispMode.markSexp());
            add("mouseCopyToInput", e -> LispShellMode.mouseCopyToInput());
            add("mouseFindOccurrence", e -> ListOccurrencesBuffer.mouseFindOccurrence());
            add("mouseFindTag", e -> TagCommands.mouseFindTag());
            add("mouseJumpToTag", e -> ListTagsMode.mouseJumpToTag());
            add("nextChange", e -> ChangeMarks.nextChange());
            add("nextComment", e -> CheckinBuffer.nextComment());
            add("nextError", e -> CompilationCommands.nextError());
            add("nextTag", e -> TagCommands.nextTag());
            add("openFileInOtherFrame", e -> OpenFileDialog.openFileInOtherFrame());
            add("pastePrimarySelection", e -> SystemSelection.pastePrimarySelection());
            add("phpHelp", e -> PHPMode.phpHelp(), (e, s) -> PHPMode.phpHelp(s));
            add("playbackMacro", e -> Macro.playbackMacro());
            add("previousChange", e -> ChangeMarks.previousChange());
            add("previousComment", e -> CheckinBuffer.previousComment());
            add("previousError", e -> CompilationCommands.previousError());
            add("previousTag", e -> TagCommands.previousTag());
            add("print", e -> PrintCommands.print());
            add("printBuffer", e -> PrintCommands.printBuffer());
            add("printRegion", e -> PrintCommands.printRegion());
            add("properties", e -> PropertiesDialog.properties());
            add("recentFiles", e -> RecentFilesDialog.recentFiles());
            add("recompile", e -> CompilationCommands.recompile());
            add("recordMacro", e -> Macro.recordMacro());
            add("reloadKeyMaps", e -> KeyMap.reloadKeyMaps());
            add("renumberRegion", e -> RegionCommands.renumberRegion(), (e, s) -> RegionCommands.renumberRegion(s));
            add("replace", e -> ReplaceDialog.replace());
            add("replaceChar", null, (e, s) -> CaretCommands.replaceChar(s));
            add("replaceInFiles", e -> FindInFiles.replaceInFiles());
            add("rescanProject", ProjectCommands::rescanProject);
            add("resetLisp", e -> LispShellMode.resetLisp());
            add("saveSession", e -> Session.saveSession(), (e, s) -> Session.saveSession(s));
            add("saveToRegister", e -> Registers.saveToRegister(), (e, s) -> Registers.saveToRegister(s));
            add("selectToMarker", e -> Marker.selectToMarker(), (e, s) -> Marker.selectToMarker(s));
            add("selectToTemporaryMarker", e -> Marker.selectToTemporaryMarker());
            add("shellCommand", e -> ShellCommand.shellCommand(), (e, s) -> ShellCommand.shellCommand(s));
            add("shell", e -> ShellBuffer.shell(), (e, s) -> ShellBuffer.shell(s));
            add("shellBackspace", e -> CommandInterpreterBuffer.shellBackspace());
            add("shellEnter", e -> CommandInterpreterBuffer.shellEnter());
            add("shellEscape", e -> CommandInterpreterBuffer.shellEscape());
            add("shellHome", e -> CommandInterpreterBuffer.shellHome());
            add("shellInterrupt", e -> ShellBuffer.shellInterrupt());
            add("shellNextInput", e -> CommandInterpreterBuffer.shellNextInput());
            add("shellNextPrompt", e -> CommandInterpreterBuffer.shellNextPrompt());
            add("shellPreviousInput", e -> CommandInterpreterBuffer.shellPreviousInput());
            add("shellPreviousPrompt", e -> CommandInterpreterBuffer.shellPreviousPrompt());
            add("shellTab", e -> ShellBuffer.shellTab());
            add("showMessage", e -> CompilationCommands.showMessage());
            //addCommand("slime", "mode.lisp.LispShellBuffer");
            add("sortLines", e -> Sort.sortLines(), (e, s) -> Sort.sortLines(s));
            add("source", e -> JDKHelp.source(), (e, s) -> JDKHelp.source(s));
            add("startMacro", e -> Macro.startMacro());
            add("statusDiffFile", e -> StatusMode.diffFile());
            add("statusGotoFile", e -> StatusMode.gotoFile());
            add("ssh", e -> RemoteShellBuffer.ssh(), (e, s) -> RemoteShellBuffer.ssh(s));
            add("svn", e -> SVN.svn(), (e, s) -> SVN.svn(s));
            add("svnAdd", e -> SVN.add());
            add("svnChangeList", e -> SVN.changelist(), (e, s) -> SVN.changelist(s));
            add("svnCommit", e -> SVN.commit(), (e, s) -> SVN.commit(s));
            add("svnDiff", e -> SVN.diff());
            add("svnDiffDir", e -> SVN.diffDir());
            add("svnLog", e -> SVN.log(), (e, s) -> SVN.log(s));
            add("svnRevert", e -> SVN.revert());
            add("svnStatus", e -> SVN.status());
            add("tagDown", e -> ListTagsMode.tagDown());
            add("tagUp", e -> ListTagsMode.tagUp());
            add("task", e -> MarkdownTasks.task(), (e, s) -> MarkdownTasks.task(s));
            add("telnet", e -> RemoteShellBuffer.telnet(), (e, s) -> RemoteShellBuffer.telnet(s));
            add("thisError", e -> CompilationCommands.thisError());
            add("toggleToolbar", WindowCommands::toggleToolbar);
            add("toggleWrap", e -> WrapText.toggleWrap());
            add("upperCaseRegion", e -> RegionCommands.upperCaseRegion());
            add("whereIs", e -> ExecuteCommandDialog.whereIs(), (e, s) -> ExecuteCommandDialog.whereIs(s));
            add("wrapComment", e -> WrapText.wrapComment());
            add("writeGlobalKeyMap", e -> SaveFileDialog.writeGlobalKeyMap());
            add("writeLocalKeyMap", e -> SaveFileDialog.writeLocalKeyMap());
            add("xmlElectricEquals", e -> XmlMode.xmlElectricEquals());
            add("xmlElectricSlash", e -> XmlMode.xmlElectricSlash());
            add("xmlFindCurrentNode", e -> XmlMode.xmlFindCurrentNode());
            add("xmlFindMatch", e -> XmlMode.xmlFindMatch());
            add("xmlInsertEmptyElementTag", e -> XmlMode.xmlInsertEmptyElementTag());
            add("xmlInsertMatchingEndTag", e -> XmlMode.xmlInsertMatchingEndTag());
            add("xmlInsertTag", e -> XmlMode.xmlInsertTag(), (e, s) -> XmlMode.xmlInsertTag(s));
            add("xmlParseBuffer", e -> XmlMode.xmlParseBuffer());
            add("xmlValidateBuffer", e -> XmlMode.xmlValidateBuffer());

            // jdb commands.
            add("jdb", e -> JdbCommands.jdb(), (e, s) -> JdbCommands.jdb(s));
            add("jdbContinue", e -> JdbCommands.jdbContinue());
            add("jdbDeleteBreakpoint", e -> Jdb.jdbDeleteBreakpoint());
            add("jdbFinish", e -> JdbCommands.jdbFinish());
            add("jdbLocals", e -> JdbCommands.jdbLocals());
            add("jdbNext", e -> JdbCommands.jdbNext());
            add("jdbQuit", e -> JdbCommands.jdbQuit());
            add("jdbRestart", e -> JdbCommands.jdbRestart());
            add("jdbRunToCurrentLine", e -> Jdb.jdbRunToCurrentLine());
            add("jdbSetBreakpoint", e -> Jdb.jdbSetBreakpoint());
            add("jdbStep", e -> JdbCommands.jdbStep());
            add("jdbSuspend", e -> JdbCommands.jdbSuspend());
            add("jdbToggleBreakpoint", e -> Jdb.jdbToggleBreakpoint());

            // Web browser commands.
            add("webBack", e -> WebBuffer.back());
            add("webForward", e -> WebBuffer.forward());
            add("webReload", e -> WebBuffer.refresh());
            add("mouseFollowLink", e -> WebBuffer.mouseFollowLink());
            add("viewPage", e -> WebBuffer.viewPage());
            add("viewSource", e -> WebBuffer.viewSource());

            // Abbreviations.
            add("sr", e -> Registers.saveToRegister(), (e, s) -> Registers.saveToRegister(s));
            add("ir", e -> Registers.insertRegister(), (e, s) -> Registers.insertRegister(s));
            add("lr", e -> Registers.listRegisters());
            add("hs", e -> LispMode.hyperspec(), (e, s) -> LispMode.hyperspec(s));
            add("clhs", e -> LispMode.hyperspec(), (e, s) -> LispMode.hyperspec(s));
            add("abcl", e -> LispShellBuffer.lisp(), (e, s) -> LispShellBuffer.lisp(s));

            if (Editor.isDebugEnabled() && map.size() > INITIAL_CAPACITY * 0.9) {
                Log.error("CommandTable.init need to increase initial capacity!");
                Log.error("CommandTable.init size = " + map.size());
            }
        }
    }

    private static void add(String name, Consumer<Editor> run) {
        add(name, run, null);
    }

    private static void add(String name, Consumer<Editor> run, BiConsumer<Editor, String> runWithArgument) {
        map.put(name.toLowerCase(Locale.ROOT), new Command(name, run, runWithArgument));
    }

    public static List<String> getCompletionsForPrefix(String prefix) {
        init();
        String lower = prefix.toLowerCase(Locale.ROOT);
        ArrayList<String> list = new ArrayList<>();
        for (Command command : map.values()) {
            if (command.getName().toLowerCase(Locale.ROOT).startsWith(lower))
                list.add(command.getName());
        }
        return list;
    }

    public static List<String> apropos(String s) {
        init();
        String lower = s.toLowerCase(Locale.ROOT);
        ArrayList<String> list = new ArrayList<>();
        for (Command command : map.values()) {
            String name = command.getName();
            if (name.toLowerCase(Locale.ROOT).contains(lower))
                list.add(name);
        }
        return list;
    }
}
