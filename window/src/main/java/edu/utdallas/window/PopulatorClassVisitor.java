package edu.utdallas.window;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class PopulatorClassVisitor extends ClassVisitor implements Opcodes {
    private final Window window;

    public PopulatorClassVisitor(Window window) {
        super(ASM7);
        this.window = window;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        return new PopulatorMethodVisitor(this.window);
    }
}
