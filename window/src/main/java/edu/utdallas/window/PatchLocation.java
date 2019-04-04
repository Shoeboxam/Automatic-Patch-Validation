package edu.utdallas.window;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * @author ali
 */
public class PatchLocation {
    private String methodName;
    private String methodDescriptor;
    private final int lineNumber;
    private int index;

    public PatchLocation(final File classFile, int lineNumber) throws Exception {
        this.lineNumber = lineNumber;
        try (final InputStream inputStream = new FileInputStream(classFile)) {
            final InstCounterClassVisitor classVisitor = new InstCounterClassVisitor();
            final ClassReader classReader = new ClassReader(inputStream);
            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES);
        }
    }

    public String getMethodName() {
        return methodName;
    }

    public String getMethodDescriptor() {
        return methodDescriptor;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public int getInstIndex() {
        return index;
    }

    private class InstCounterClassVisitor
            extends ClassVisitor implements Opcodes {

        InstCounterClassVisitor() {
            super(ASM7);
        }

        @Override
        public MethodVisitor visitMethod(int access,
                                         String name,
                                         String descriptor,
                                         String signature,
                                         String[] exceptions) {
            return new InstCounterMethodVisitor(name, descriptor);
        }
    }

    private class InstCounterMethodVisitor
            extends MethodVisitor implements Opcodes {
        private final String name;
        private final String descriptor;
        private int counter;

        InstCounterMethodVisitor(String name, String descriptor) {
            super(ASM7);
            this.counter = 0;
            this.name = name;
            this.descriptor = descriptor;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            if (line == PatchLocation.this.lineNumber) {
                PatchLocation.this.methodName = this.name;
                PatchLocation.this.methodDescriptor = this.descriptor;
                PatchLocation.this.index = this.counter;
            }
            super.visitLineNumber(line, start);
        }

        @Override
        public void visitInsn(int opcode) {
            this.counter++;
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            this.counter++;
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            this.counter++;
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            this.counter++;
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            this.counter++;
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            this.counter++;
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            this.counter++;
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            this.counter++;
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            this.counter++;
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            this.counter++;
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            this.counter++;
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            this.counter++;
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            this.counter++;
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }
    }
}
