package edu.utdallas.window;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class PopulatorClassVisitor extends ClassVisitor implements Opcodes {
    private final PatchLocation patchLocation;
    private final Window window;

    public PopulatorClassVisitor(PatchLocation patchLocation, Window window) {
        super(ASM7);
        this.patchLocation = patchLocation;
        this.window = window;
    }

    @Override
    public MethodVisitor visitMethod(int access,
                                     String name,
                                     String descriptor,
                                     String signature,
                                     String[] exceptions) {
        if (name.equals(this.patchLocation.getMethodName())
                && descriptor.equals(this.patchLocation.getMethodDescriptor())) {
            final int index = this.patchLocation.getInstIndex();
            return new PopulatorMethodVisitor(this.window, index);
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }
}
