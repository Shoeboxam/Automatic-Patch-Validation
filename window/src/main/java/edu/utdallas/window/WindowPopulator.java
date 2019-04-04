package edu.utdallas.window;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

public final class WindowPopulator {

    private WindowPopulator() {

    }

    public static void populate(File classFile, Window window) throws Exception {
        final PatchLocation patchLocation = new PatchLocation(classFile, window.getCenter());
        try (final InputStream inputStream = new FileInputStream(classFile)) {
            final ClassVisitor classVisitor = new PopulatorClassVisitor(patchLocation, window);
            final ClassReader classReader = new ClassReader(inputStream);
            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES);
        }
    }
}
