/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.gui.view.component;

import org.fife.ui.rsyntaxtextarea.DocumentRange;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.modes.JavaTokenMaker;
import org.fife.ui.rtextarea.Marker;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.SmartHighlightPainter;
import org.fife.ui.rtextarea.ToolTipSupplier;
import org.jd.core.v1.ClassFileToJavaSourceDecompiler;
import org.jd.core.v1.api.printer.Printer;
import org.jd.gui.api.API;
import org.jd.gui.api.model.Container;
import org.jd.gui.model.container.JarContainer;
import org.jd.gui.service.project.JavaIdentifier;
import org.jd.gui.service.project.JavaProject;
import org.jd.gui.util.decompiler.*;
import org.jd.gui.util.exception.ExceptionUtil;
import org.jd.gui.util.io.NewlineOutputStream;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class ClassFilePage extends TypePage {
    protected static final String ESCAPE_UNICODE_CHARACTERS   = "ClassFileDecompilerPreferences.escapeUnicodeCharacters";
    protected static final String REALIGN_LINE_NUMBERS        = "ClassFileDecompilerPreferences.realignLineNumbers";
    protected static final String WRITE_LINE_NUMBERS          = "ClassFileSaverPreferences.writeLineNumbers";
    protected static final String WRITE_METADATA              = "ClassFileSaverPreferences.writeMetadata";
    protected static final String JD_CORE_VERSION             = "JdGuiPreferences.jdCoreVersion";

    protected static final ClassFileToJavaSourceDecompiler DECOMPILER = new ClassFileToJavaSourceDecompiler();

    protected int maximumLineNumber = -1;

    private static final Executor EXECUTOR = Executors.newFixedThreadPool(1);

    static {
        // Early class loading
        try {
            String internalTypeName = ClassFilePage.class.getName().replace('.', '/');
            DECOMPILER.decompile(new ClassPathLoader(), new NopPrinter(), internalTypeName);
        } catch (Throwable t) {
            ExceptionUtil.printStackTrace(t);
        }
    }

    private final TreeMap<Integer, DescData> descMap = new TreeMap<>();
    private static class DescData {
        int startPosition;
        int endPosition;
        JavaIdentifier identifier;

        public DescData(int startPosition, int endPosition, JavaIdentifier identifier) {
            this.startPosition = startPosition;
            this.endPosition = endPosition;
            this.identifier = identifier;
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        Container container = this.entry.getContainer();
        if (container instanceof JarContainer) {
            JavaProject project = ((JarContainer) container).getProject();
            project.removeIdentifierAliasListener(this.entry.getPath());
        }
    }

    public ClassFilePage(API api, Container.Entry entry) {
        super(api, entry);
        this.initJavaProjectListeners();

        Map<String, String> preferences = api.getPreferences();
        // Init view
        setErrorForeground(Color.decode(preferences.get("JdGuiPreferences.errorBackgroundColor")));
        // Display source
        decompile(preferences);
    }

    private JavaProject project;
    private void initJavaProjectListeners() {
        Container container = this.entry.getContainer();
        if (!(container instanceof JarContainer)) {
            return;
        }
        this.project = ((JarContainer) container).getProject();
        this.project.addIdentifierAliasListener(this.entry.getPath(), id -> {
            this.preferencesChanged(api.getPreferences());
            this.indexesChanged(api.getCollectionOfFutureIndexes());
        });

        AtomicReference<JavaIdentifier> currentIdentifier = new AtomicReference<>();

        // popup menu
        // alias
        JPopupMenu menu = new JPopupMenu();
        AbstractAction setAliasAction = new AbstractAction("Set Alias") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (project != null) {
                    JavaIdentifier id = currentIdentifier.get();
                    String result = JOptionPane.showInputDialog("Set New Alias For: \n" + id.getFriendlyDisplay(), id.getAlias());
                    if (result != null) {
                        project.setAlias(id, result);
                    }
                    EXECUTOR.execute(project::saveIdentifiers);
                }
            }
        };
        JMenuItem itemAlias = new JMenuItem(setAliasAction);
        itemAlias.setAccelerator(null);
        menu.add(itemAlias);
        // comment
        AbstractAction setCommentAction = new AbstractAction("Set Comment") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (project != null) {
                    JavaIdentifier id = currentIdentifier.get();
                    String result = JOptionPane.showInputDialog("Set New Comment For: \n" + id.getFriendlyDisplay(), id.getComment());
                    if (result != null) {
                        id.setComment(result);
                    }
                    EXECUTOR.execute(project::saveIdentifiers);
                }
            }
        };
        JMenuItem itemComment = new JMenuItem(setCommentAction);
        itemComment.setAccelerator(null);
        menu.add(itemComment);

        textArea.setPopupMenu(menu);
        Function<MouseEvent, DescData> getDescDataAtMouse = event -> {
            int offset = textArea.viewToModel(new Point(event.getX(), event.getY()));
            if (offset != -1) {
                Map.Entry<Integer, DescData> descEntry = descMap.floorEntry(offset);
                if ((descEntry != null)) {
                    DescData entryData = descEntry.getValue();
                    if (entryData != null
                        && (offset < entryData.endPosition) && (offset >= entryData.startPosition)) {
                        return entryData;
                    }
                }
            }
            return null;
        };
        textArea.setToolTipSupplier((area, e) -> {
            DescData entryData = getDescDataAtMouse.apply(e);
            if (entryData != null) {
                return entryData.identifier.getFriendlyDisplay();
            }
            return null;
        });

        MouseAdapter listener = new MouseAdapter() {

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JavaIdentifier id = null;
                    DescData entryData = getDescDataAtMouse.apply(e);
                    if (entryData != null) {
                        id = entryData.identifier;
                    }
                    currentIdentifier.set(id);
                    itemAlias.setEnabled(id != null && id.canSetAlias());
                    itemComment.setEnabled(id != null && id.canSetAlias());
                }
            }
        };
        textArea.addMouseListener(listener);
    }

    public void decompile(Map<String, String> preferences) {
        try {
            // Clear ...
            clearHyperlinks();
            clearLineNumbers();
            declarations.clear();
            typeDeclarations.clear();
            strings.clear();

            // Init preferences
            boolean realignmentLineNumbers = getPreferenceValue(preferences, REALIGN_LINE_NUMBERS, false);
            boolean unicodeEscape = getPreferenceValue(preferences, ESCAPE_UNICODE_CHARACTERS, false);

            Map<String, Object> configuration = new HashMap<>();
            configuration.put("realignLineNumbers", realignmentLineNumbers);

            setShowMisalignment(realignmentLineNumbers);

            // Init loader
            ContainerLoader loader = new ContainerLoader(entry);

            // Init printer
            ClassFilePrinter printer = new ClassFilePrinter();
            printer.setRealignmentLineNumber(realignmentLineNumbers);
            printer.setUnicodeEscape(unicodeEscape);

            // Format internal name
            String entryPath = entry.getPath();
            assert entryPath.endsWith(".class");
            String entryInternalName = entryPath.substring(0, entryPath.length() - 6); // 6 = ".class".length()


            descMap.clear();
            Highlighter highlighter = textArea.getHighlighter();
            highlighter.removeAllHighlights();
            Marker.clearMarkAllHighlights(textArea);

            // Decompile class file
            DECOMPILER.decompile(loader, printer, entryInternalName, configuration);

            try {
                Color c = Color.decode("0x7FFFAA");
                SmartHighlightPainter painter = new SmartHighlightPainter(
                    new Color(c.getRed(), c.getGreen(), c.getBlue(), 77));
                Document doc = textArea.getDocument();
                for (DescData descData : descMap.values()) {
                    if (!descData.identifier.hasAlias()) {
                        continue;
                    }
                    int start = doc.createPosition(descData.startPosition).getOffset();
                    int end = doc.createPosition(descData.endPosition).getOffset();
                    highlighter.addHighlight(start, end, painter);
                }
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        } catch (Throwable t) {
            ExceptionUtil.printStackTrace(t);
            setText("// INTERNAL ERROR //");
        }

        maximumLineNumber = getMaximumSourceLineNumber();
    }

    protected static boolean getPreferenceValue(Map<String, String> preferences, String key, boolean defaultValue) {
        String v = preferences.get(key);
        return (v == null) ? defaultValue : Boolean.parseBoolean(v);
    }

    @Override
    public String getSyntaxStyle() { return SyntaxConstants.SYNTAX_STYLE_JAVA; }

    // --- ContentSavable --- //
    @Override
    public String getFileName() {
        String path = entry.getPath();
        int index = path.lastIndexOf('.');
        return path.substring(0, index) + ".java";
    }

    @Override
    public void save(API api, OutputStream os) {
        try {
            // Init preferences
            Map<String, String> preferences = api.getPreferences();
            boolean realignmentLineNumbers = getPreferenceValue(preferences, REALIGN_LINE_NUMBERS, false);
            boolean unicodeEscape = getPreferenceValue(preferences, ESCAPE_UNICODE_CHARACTERS, false);
            boolean showLineNumbers = getPreferenceValue(preferences, WRITE_LINE_NUMBERS, true);

            Map<String, Object> configuration = new HashMap<>();
            configuration.put("realignLineNumbers", realignmentLineNumbers);

            // Init loader
            ContainerLoader loader = new ContainerLoader(entry);

            // Init printer
            LineNumberStringBuilderPrinter printer = new LineNumberStringBuilderPrinter();
            printer.setRealignmentLineNumber(realignmentLineNumbers);
            printer.setUnicodeEscape(unicodeEscape);
            printer.setShowLineNumbers(showLineNumbers);

            // Format internal name
            String entryPath = entry.getPath();
            assert entryPath.endsWith(".class");
            String entryInternalName = entryPath.substring(0, entryPath.length() - 6); // 6 = ".class".length()

            // Decompile class file
            DECOMPILER.decompile(loader, printer, entryInternalName, configuration);

            StringBuilder stringBuffer = printer.getStringBuffer();

            // Metadata
            if (getPreferenceValue(preferences, WRITE_METADATA, true)) {
                // Add location
                String location =
                        new File(entry.getUri()).getPath()
                                // Escape "\ u" sequence to prevent "Invalid unicode" errors
                                .replaceAll("(^|[^\\\\])\\\\u", "\\\\\\\\u");
                stringBuffer.append("\n\n/* Location:              ");
                stringBuffer.append(location);
                // Add Java compiler version
                int majorVersion = printer.getMajorVersion();

                if (majorVersion >= 45) {
                    stringBuffer.append("\n * Java compiler version: ");

                    if (majorVersion >= 49) {
                        stringBuffer.append(majorVersion - (49 - 5));
                    } else {
                        stringBuffer.append(majorVersion - (45 - 1));
                    }

                    stringBuffer.append(" (");
                    stringBuffer.append(majorVersion);
                    stringBuffer.append('.');
                    stringBuffer.append(printer.getMinorVersion());
                    stringBuffer.append(')');
                }
                // Add JD-Core version
                stringBuffer.append("\n * JD-Core Version:       ");
                stringBuffer.append(preferences.get(JD_CORE_VERSION));
                stringBuffer.append("\n */");
            }

            try (PrintStream ps = new PrintStream(new NewlineOutputStream(os), true, "UTF-8")) {
                ps.print(stringBuffer.toString());
            } catch (IOException e) {
                ExceptionUtil.printStackTrace(e);
            }
        } catch (Throwable t) {
            ExceptionUtil.printStackTrace(t);

            try (OutputStreamWriter writer = new OutputStreamWriter(os, Charset.defaultCharset())) {
                writer.write("// INTERNAL ERROR //");
            } catch (IOException ee) {
                ExceptionUtil.printStackTrace(ee);
            }
        }
    }

    // --- LineNumberNavigable --- //
    @Override
    public int getMaximumLineNumber() { return maximumLineNumber; }

    @Override
    public void goToLineNumber(int lineNumber) {
        int textAreaLineNumber = getTextAreaLineNumber(lineNumber);
        if (textAreaLineNumber > 0) {
            try {
                int start = textArea.getLineStartOffset(textAreaLineNumber - 1);
                int end = textArea.getLineEndOffset(textAreaLineNumber - 1);
                setCaretPositionAndCenter(new DocumentRange(start, end));
            } catch (BadLocationException e) {
                ExceptionUtil.printStackTrace(e);
            }
        }
    }

    @Override
    public boolean checkLineNumber(int lineNumber) { return lineNumber <= maximumLineNumber; }

    // --- PreferencesChangeListener --- //
    @Override
    public void preferencesChanged(Map<String, String> preferences) {
        DefaultCaret caret = (DefaultCaret)textArea.getCaret();
        int updatePolicy = caret.getUpdatePolicy();

        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        decompile(preferences);
        caret.setUpdatePolicy(updatePolicy);

        super.preferencesChanged(preferences);
    }

    public class ClassFilePrinter extends StringBuilderPrinter {
        protected HashMap<String, ReferenceData> referencesCache = new HashMap<>();

        // Manage line number and misalignment
        int textAreaLineNumber = 1;

        @Override
        public void start(int maxLineNumber, int majorVersion, int minorVersion) {
            super.start(maxLineNumber, majorVersion, minorVersion);

            if (maxLineNumber == 0) {
                scrollPane.setLineNumbersEnabled(false);
            } else {
                setMaxLineNumber(maxLineNumber);
            }
        }

        @Override
        public void end() {
            setText(stringBuffer.toString());
        }

        // --- Add strings --- //
        @Override
        public void printStringConstant(String constant, String ownerInternalName) {
            if (constant == null) constant = "null";
            if (ownerInternalName == null) ownerInternalName = "null";

            strings.add(new TypePage.StringData(stringBuffer.length(), constant.length(), constant, ownerInternalName));
            super.printStringConstant(constant, ownerInternalName);
        }

        @Override
        public void printDeclaration(int type, String internalTypeName, String name, String descriptor) {
            String alias = this.getAlias(type, internalTypeName, name, descriptor);

            if (internalTypeName == null) internalTypeName = "null";
            if (name == null) name = "null";
            if (descriptor == null) descriptor = "null";

            switch (type) {
                case Printer.TYPE:
                    TypePage.DeclarationData data = new TypePage.DeclarationData(stringBuffer.length(), alias.length(), internalTypeName, null, null);
                    declarations.put(internalTypeName, data);
                    typeDeclarations.put(stringBuffer.length(), data);
                    break;
                case Printer.CONSTRUCTOR:
                    declarations.put(internalTypeName + "-<init>-" + descriptor, new TypePage.DeclarationData(stringBuffer.length(), alias.length(), internalTypeName, "<init>", descriptor));
                    break;
                default:
                    declarations.put(internalTypeName + '-' + name + '-' + descriptor, new TypePage.DeclarationData(stringBuffer.length(), alias.length(), internalTypeName, name, descriptor));
                    break;
            }

            super.printDeclaration(type, internalTypeName, alias, descriptor);
        }

        @Override
        public void printReference(int type, String internalTypeName, String name, String descriptor, String ownerInternalName) {
            String alias = this.getAlias(type, internalTypeName, name, descriptor);

            if (internalTypeName == null) internalTypeName = "null";
            if (name == null) name = "null";
            if (descriptor == null) descriptor = "null";

            switch (type) {
                case TYPE:
                    addHyperlink(new TypePage.HyperlinkReferenceData(stringBuffer.length(), alias.length(), newReferenceData(internalTypeName, null, null, ownerInternalName)));
                    break;
                case CONSTRUCTOR:
                    addHyperlink(new TypePage.HyperlinkReferenceData(stringBuffer.length(), alias.length(), newReferenceData(internalTypeName, "<init>", descriptor, ownerInternalName)));
                    break;
                default:
                    addHyperlink(new TypePage.HyperlinkReferenceData(stringBuffer.length(), alias.length(), newReferenceData(internalTypeName, name, descriptor, ownerInternalName)));
                    break;
            }
            super.printReference(type, internalTypeName, alias, descriptor, ownerInternalName);
        }

        private String getAlias(int type, String internalTypeName, String name, String descriptor) {
            if (project != null) {
                JavaIdentifier identifier;
                if (type == Printer.TYPE || type == Printer.CONSTRUCTOR) {
                    identifier = project.getIdentifier(internalTypeName);
                } else {
                    identifier = project.getIdentifier(internalTypeName, name, descriptor);
                }
                if (identifier != null) {
                    if (identifier.hasAlias()) {
                        if (type == Printer.TYPE && name.indexOf('.') != -1) {
                            int idx = name.lastIndexOf('.');
                            name = name.substring(0, idx + 1) + identifier.getAlias();
                        } else {
                            name = identifier.getAlias();
                        }
                    }
                    DescData descData = new DescData(getCurrentPosition(), getCurrentPosition() + name.length(), identifier);
                    descMap.put(getCurrentPosition(), descData);
                }
            }
            return name;
        }

        @Override
        public void startLine(int lineNumber) {
            super.startLine(lineNumber);
            setLineNumber(textAreaLineNumber, lineNumber);
        }
        @Override
        public void endLine() {
            super.endLine();
            textAreaLineNumber++;
        }
        @Override
        public void extraLine(int count) {
            super.extraLine(count);
            if (realignmentLineNumber) {
                textAreaLineNumber += count;
            }
        }

        // --- Add references --- //
        public TypePage.ReferenceData newReferenceData(String internalName, String name, String descriptor, String scopeInternalName) {
            String key = internalName + '-' + name + '-'+ descriptor + '-' + scopeInternalName;
            ReferenceData reference = referencesCache.get(key);

            if (reference == null) {
                reference = new TypePage.ReferenceData(internalName, name, descriptor, scopeInternalName);
                referencesCache.put(key, reference);
                references.add(reference);
            }

            return reference;
        }
    }
}
