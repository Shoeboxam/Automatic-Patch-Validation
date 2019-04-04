package edu.utdallas.window;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class PopulatorMethodVisitor extends MethodVisitor implements Opcodes {
    private final int centerIndex;
    private final Window window;
    private int counter;

    public PopulatorMethodVisitor(Window window, int centerIndex) {
        super(ASM7);
        this.window = window;
        this.centerIndex = centerIndex;
        this.counter = 0;
    }

    private void record(int opcode) {
        if (Math.abs(this.counter - this.centerIndex) <= Window.WINDOW_CAPACITY / 2) {
            final AbstractInstruction ai = AbstractInstruction.fromOpcode(opcode);
            this.window.add(ai);
        }
    }

    @Override
    public void visitInsn(int opcode) {
        record(opcode);
        this.counter++;
        super.visitInsn(opcode);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        record(opcode);
        this.counter++;
        super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        record(opcode);
        this.counter++;
        super.visitVarInsn(opcode, var);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        record(opcode);
        this.counter++;
        super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        record(opcode);
        this.counter++;
        super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        record(opcode);
        this.counter++;
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        record(INVOKEDYNAMIC);
        this.counter++;
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        record(opcode);
        this.counter++;
        super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitLdcInsn(Object value) {
        record(LDC);
        this.counter++;
        super.visitLdcInsn(value);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        record(IINC);
        this.counter++;
        super.visitIincInsn(var, increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        record(TABLESWITCH);
        this.counter++;
        super.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        record(LOOKUPSWITCH);
        this.counter++;
        super.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        record(MULTIANEWARRAY);
        this.counter++;
        super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }
}
