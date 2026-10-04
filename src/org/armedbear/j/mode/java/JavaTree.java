/*
 * JavaTree.java
 *
 * Copyright (C) 2002-2003 Peter Graves
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

package org.armedbear.j.mode.java;

import static org.armedbear.j.Constants.*;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.armedbear.j.Buffer;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.LocalTag;
import org.armedbear.j.LocationBar;
import org.armedbear.j.NavigationComponent;
import org.armedbear.j.Position;
import org.armedbear.j.SidebarTree;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Keys;

public final class JavaTree extends SidebarTree implements NavigationComponent, KeyListener, MouseListener {
    private static final String CAPTION_FIELDS = "Fields";
    private static final String CAPTION_CONSTRUCTORS = "Constructors";
    private static final String CAPTION_METHODS = "Methods";
    private static final String CAPTION_NESTED_CLASSES = "Nested Classes";

    private static final String KEY_ARRANGE_BY_TYPE =
        "JavaMode.tree.arrangeByType";
    private static final String KEY_SORT = "JavaMode.tree.sort";

    private static boolean arrangeByType =
        Editor.getSessionProperties().getBooleanProperty(KEY_ARRANGE_BY_TYPE, true);
    private static boolean sort =
        Editor.getSessionProperties().getBooleanProperty(KEY_SORT, false);

    private final Editor editor;
    private List<LocalTag> tags;
    private boolean arrangedByType;
    private boolean sorted;

    public static final void setArrangeByType(boolean b) {
        if (b != arrangeByType) {
            arrangeByType = b;
            Editor.getSessionProperties().setBooleanProperty(KEY_ARRANGE_BY_TYPE, b);
        }
    }

    public static final boolean getArrangeByType() {
        return arrangeByType;
    }

    public static final void setSort(boolean b) {
        if (b != sort) {
            sort = b;
            Editor.getSessionProperties().setBooleanProperty(KEY_SORT, b);
        }
    }

    public static final boolean getSort() {
        return sort;
    }

    public JavaTree(Editor editor) {
        super((TreeModel) null);
        this.editor = editor;
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        setRootVisible(false);
        setCellRenderer(new TreeCellRenderer());
        setFocusTraversalKeysEnabled(false);
        addKeyListener(this);
        addMouseListener(this);
        setToolTipText("");
    }

    @Override
    public void refresh() {
        boolean force = (arrangedByType != arrangeByType)
            ||
            (sorted != sort);
        refresh(force);
    }

    public void refresh(boolean force) {
        final Buffer buffer = editor.getBuffer();
        final List<LocalTag> bufferTags = buffer.getTags();
        if (!force)
            if (tags != null && tags == bufferTags)
                return; // Nothing to do.
        Background.start("JavaTree.refresh()", () -> refreshInternal(buffer, bufferTags));
    }

    private void refreshInternal(Buffer buffer, List<LocalTag> bufferTags) {
        if (bufferTags == null)
            bufferTags = buffer.getTags(true); // Runs tagger synchronously.
        if (bufferTags != null) {
            final TreeModel model =
                getDefaultModel(bufferTags, arrangeByType, sort);
            final List<LocalTag> finalBufferTags = bufferTags;
            Runnable completionRunnable = () -> {
                setModel(model);
                arrangedByType = arrangeByType;
                sorted = sort;
                tags = finalBufferTags;
                expandRow(0);
                updatePosition();
            };
            SwingUtilities.invokeLater(completionRunnable);
        }
    }

    // Never returns null!
    private static TreeModel getDefaultModel(
        List<LocalTag> bufferTags,
        boolean arrangeByType,
        boolean sort
    ) {
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
        List<LocalTag> list;
        if (sort)
            list = sort(bufferTags);
        else
            list = bufferTags;
        final int size = list.size();
        for (int i = 0; i < size; i++) {
            JavaTag tag = (JavaTag) list.get(i);
            JavaClass parent = tag.getParent();
            if (parent == null) {
                addNode(rootNode, tag, arrangeByType);
            } else {
                DefaultMutableTreeNode parentNode =
                    findParentNodeForTag(tag, rootNode);
                addNode(parentNode, tag, arrangeByType);
            }
        }
        return new DefaultTreeModel(rootNode);
    }

    // Doesn't modify passed-in list.
    private static List<LocalTag> sort(List<LocalTag> list) {
        List<JavaTag> methodsAndFields = new ArrayList<>();
        List<LocalTag> allTags = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            JavaTag t = (JavaTag) list.get(i);
            switch (t.getType()) {
                case TAG_METHOD:
                case TAG_FIELD:
                    methodsAndFields.add(t);
                    break;
                default:
                    allTags.add(t);
                    break;
            }
        }
        Collections.sort(
            methodsAndFields,
            (t1, t2) -> t1.toString().compareTo(t2.toString())
        );
        allTags.addAll(methodsAndFields);
        return allTags;
    }

    private static DefaultMutableTreeNode findParentNodeForTag(
        JavaTag tag,
        DefaultMutableTreeNode rootNode
    ) {
        JavaClass parent = tag.getParent();
        if (parent == null)
            return rootNode;
        final String parentName = parent.getName();
        Enumeration<TreeNode> nodes = rootNode.breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) nodes.nextElement();
            Object obj = node.getUserObject();
            if (obj instanceof JavaTag t) {
                String name = t.getName();
                switch (t.getType()) {
                    case TAG_CLASS:
                        if (name.startsWith("class "))
                            name = name.substring(6);
                        if (name.equals(parentName))
                            return node;
                        break;
                    case TAG_INTERFACE:
                        if (name.startsWith("interface "))
                            name = name.substring(10);
                        if (name.equals(parentName))
                            return node;
                        break;
                    default:
                        break;
                }
            }
        }
        return rootNode;
    }

    private static void addNode(
        DefaultMutableTreeNode parentNode,
        JavaTag tag,
        boolean arrangeByType
    ) {
        if (parentNode instanceof ClassNode classNode) {
            classNode.addTag(tag);
        } else {
            final int type = tag.getType();
            if (type == TAG_CLASS || type == TAG_INTERFACE)
                parentNode.add(new ClassNode(tag, arrangeByType));
        }
    }

    @Override
    public void updatePosition() {
        TreeModel model = getModel();
        if (model == null)
            return;
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        if (root == null)
            return;
        if (tags != null) {
            final Position dot = editor.getDotCopy();
            JavaTag tag = findTag(dot);
            if (tag != null) {
                DefaultMutableTreeNode node = findNode(root, tag);
                if (node != null) {
                    DefaultMutableTreeNode selectedNode = null;
                    TreePath oldPath = getSelectionPath();
                    if (oldPath != null) {
                        selectedNode =
                            (DefaultMutableTreeNode) oldPath.getLastPathComponent();
                    }
                    if (node != selectedNode)
                        scrollNodeToCenter(node);
                    return;
                }
            }
        }
        // Otherwise...
        setSelectionRow(0);
        scrollRowToVisible(0);
        if (arrangeByType)
            expandMethods();
    }

    private JavaTag findTag(Position dot) {
        if (dot == null)
            return null;
        final Line dotLine = dot.getLine();
        JavaTag tag = null;
        Line lastTagLine = null;
        final int size = tags.size();
        for (int i = 0; i < size; i++) {
            final JavaTag t = (JavaTag) tags.get(i);
            if (t.getPosition().isAfter(dot)) {
                if (t.getLine() == dotLine && t.getLine() != lastTagLine)
                    tag = t;
                break;
            } else {
                tag = t;
                lastTagLine = t.getLine();
            }
        }
        return tag;
    }

    private DefaultMutableTreeNode findNode(
        DefaultMutableTreeNode root,
        JavaTag tag
    ) {
        Enumeration<TreeNode> nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() instanceof JavaTag) {
                JavaTag t = (JavaTag) node.getUserObject();
                if (t == tag)
                    return node;
            }
        }
        return null;
    }

    private void expandMethods() {
        for (int i = 0; i < getRowCount(); i++) {
            TreePath path = getPathForRow(i);
            if (path != null) {
                DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode) path.getLastPathComponent();
                Object obj = node.getUserObject();
                if (obj instanceof String && obj.equals(CAPTION_METHODS)) {
                    expandRow(i);
                    break;
                }
            }
        }
    }

    @Override
    public final String getLabelText() {
        File file = editor.getBuffer().getFile();
        return file != null ? file.getName() : null;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        JavaTag t = getJavaTagAtPoint(e.getPoint());
        return t != null ? t.getToolTipText() : null;
    }

    private JavaTag getJavaTagAtPoint(Point point) {
        TreePath treePath = getPathForLocation(point.x, point.y);
        if (treePath != null) {
            DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) treePath.getLastPathComponent();
            Object obj = node.getUserObject();
            if (obj instanceof JavaTag javaTag)
                return javaTag;
        }
        return null;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        final TreePath path = getSelectionPath();
        LocalTag selected = null;
        if (path != null) {
            final Object obj =
                ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (obj instanceof LocalTag localTag)
                selected = localTag;
        }
        tagKeyPressed(editor, e, selected, this::updatePosition);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        tagKeyReleased(editor, e);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        e.consume();
    }

    @Override
    protected void processMouseEvent(MouseEvent e) {
        if (e.isPopupTrigger()) {
            JavaTreePopupMenu popup = new JavaTreePopupMenu(this);
            popup.show(this, e.getX(), e.getY());
        } else
            super.processMouseEvent(e);
    }

    @Override
    public void mousePressed(MouseEvent e) {}

    @Override
    public void mouseReleased(MouseEvent e) {}

    @Override
    public void mouseClicked(MouseEvent e) {
        LocationBar.cancelInput();
        editor.ensureActive();
        final int button = e.getButton();
        final boolean unmodified = Keys.isUnmodified(e);
        if (!(unmodified && button == MouseEvent.BUTTON1) && !(unmodified && button == MouseEvent.BUTTON2)) {
            e.consume();
            editor.setFocusToDisplay();
            return;
        }
        JavaTag t = getJavaTagAtPoint(e.getPoint());
        if (t != null)
            t.gotoTag(editor);
        editor.setFocusToDisplay();
    }

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {
        giveBackFocus(editor);
    }

    private static class ClassNode extends DefaultMutableTreeNode {
        final String className;
        final boolean arrangeByType;

        DefaultMutableTreeNode fields;
        DefaultMutableTreeNode constructors;
        DefaultMutableTreeNode methods;
        DefaultMutableTreeNode nestedClasses;
        int index;

        ClassNode(JavaTag tag, boolean arrangeByType) {
            super(tag);
            String s = tag.getName();
            if (s.startsWith("class "))
                className = s.substring(6);
            else
                className = s;
            this.arrangeByType = arrangeByType;
            if (arrangeByType) {
                fields = new DefaultMutableTreeNode(CAPTION_FIELDS);
                add(fields);
                constructors = new DefaultMutableTreeNode(CAPTION_CONSTRUCTORS);
                add(constructors);
                methods = new DefaultMutableTreeNode(CAPTION_METHODS);
                add(methods);
            } else
                fields = constructors = methods = nestedClasses = this;
        }

        void addTag(JavaTag tag) {
            switch (tag.getType()) {
                case TAG_CLASS:
                case TAG_INTERFACE:
                    if (nestedClasses == null) {
                        nestedClasses =
                            new DefaultMutableTreeNode(CAPTION_NESTED_CLASSES);
                        add(nestedClasses);
                    }
                    nestedClasses.add(new ClassNode(tag, arrangeByType));
                    break;
                case TAG_EXTENDS:
                    insert(new DefaultMutableTreeNode(tag), 0);
                    ++index;
                    break;
                case TAG_IMPLEMENTS:
                    insert(new DefaultMutableTreeNode(tag), index++);
                    break;
                case TAG_FIELD:
                    addField(tag);
                    break;
                default:
                    if (tag.getMethodName().equals(className))
                        addConstructor(tag);
                    else
                        addMethod(tag);
                    break;
            }
        }

        void addField(JavaTag tag) {
            fields.add(new DefaultMutableTreeNode(tag));
        }

        void addConstructor(JavaTag tag) {
            constructors.add(new DefaultMutableTreeNode(tag));
        }

        void addMethod(JavaTag tag) {
            methods.add(new DefaultMutableTreeNode(tag));
        }
    }

    private static class TreeCellRenderer extends DefaultTreeCellRenderer {
        private Color oldBackgroundSelectionColor;

        public TreeCellRenderer() {
            super();
            oldBackgroundSelectionColor = getBackgroundSelectionColor();
        }

        @Override
        public Component getTreeCellRendererComponent(
            JTree tree,
            Object value,
            boolean selected,
            boolean expanded,
            boolean leaf,
            int row,
            boolean hasFocus
        ) {
            super.getTreeCellRendererComponent(
                tree,
                value,
                selected,
                expanded,
                leaf,
                row,
                hasFocus
            );
            if (selected)
                super.setForeground(getTextSelectionColor());
            else
                super.setForeground(getTextNonSelectionColor());
            if (Editor.getCurrentFrame().getFocusedComponent() == tree)
                setBackgroundSelectionColor(oldBackgroundSelectionColor);
            else
                setBackgroundSelectionColor(NO_FOCUS_SELECTION_BACKGROUND);
            if (value instanceof DefaultMutableTreeNode defaultMutableTreeNode) {
                Object obj = defaultMutableTreeNode.getUserObject();
                if (obj instanceof JavaTag t) {
                    setIcon(t.getIcon());
                    setText(t.getSidebarText());
                } else if (obj instanceof String) {
                    if (obj.equals(CAPTION_FIELDS))
                        setIcon(Icons.getIconFromFile("field"));
                    else if (obj.equals(CAPTION_CONSTRUCTORS))
                        setIcon(Icons.getIconFromFile("method"));
                    else if (obj.equals(CAPTION_METHODS))
                        setIcon(Icons.getIconFromFile("method"));
                    else if (obj.equals(CAPTION_NESTED_CLASSES))
                        setIcon(Icons.getIconFromFile("class"));
                }
            }
            return this;
        }

        @Override
        public void paintComponent(Graphics g) {
            Display.setRenderingHints(g);
            super.paintComponent(g);
        }
    }
}
