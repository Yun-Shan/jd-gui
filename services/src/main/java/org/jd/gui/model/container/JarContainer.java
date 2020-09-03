/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.gui.model.container;

import org.jd.gui.api.API;
import org.jd.gui.api.model.Container;
import org.jd.gui.service.fileloader.AbstractFileLoaderProvider;
import org.jd.gui.service.project.JavaIdentifier;
import org.jd.gui.service.project.JavaProject;
import org.objectweb.asm.*;

import java.nio.file.Path;

public class JarContainer extends GenericContainer {

    private final JavaProject project;

    public JarContainer(API api, Container.Entry parentEntry, Path rootPath) {
        super(api, parentEntry, rootPath);
        this.project = new JavaProject(api.getConfigPath().resolve("projects"),
            ((AbstractFileLoaderProvider.ContainerEntry) parentEntry).getFile().toPath());
    }

    public String getType() {
        return "jar";
    }

    public void readClass(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        cr.accept(new IdentifierVisitor(), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    }

    public JavaProject getProject() {
        return this.project;
    }

    @Override
    public void onClose() {
        super.onClose();
        this.project.unlock();
    }

    private class IdentifierVisitor extends ClassVisitor {

        private String internalClassName;

        public IdentifierVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.internalClassName = name;
            project.addIdentifier(new JavaIdentifier(name));
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            project.addIdentifier(new JavaIdentifier(this.internalClassName, name, descriptor));
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            project.addIdentifier(new JavaIdentifier(this.internalClassName, name, descriptor));
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }

    }
}
