package edu.utdallas.window;

import org.objectweb.asm.Opcodes;
import org.omg.CORBA.UNKNOWN;

public enum AbstractInstruction {
    NOP(Opcodes.NOP),
    CONST_NULL(Opcodes.ACONST_NULL),
    CONST_INT(Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
            Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5),
    CONST_LONG(Opcodes.LCONST_0, Opcodes.LCONST_1),
    CONST_FLOAT(Opcodes.FCONST_0, Opcodes.FCONST_2),
    CONST_DOUBLE(Opcodes.DCONST_0, Opcodes.DCONST_1),
    CONST_BYTE(Opcodes.BIPUSH),
    CONST_SHORT(Opcodes.SIPUSH),
    LDC(Opcodes.LDC),
    LOAD_LOCAL(Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD),
    LOAD_ARRAY(Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
            Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.SALOAD, Opcodes.CALOAD),
    STORE_LOCAL(Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE),
    STORE_ARRAY(Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
            Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.SASTORE, Opcodes.CASTORE),
    POP(Opcodes.POP, Opcodes.POP2),
    DUP(Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2, Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2),
    SWAP(Opcodes.SWAP),
    ADD(Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD),
    SUB(Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB),
    MUL(Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL),
    DIV(Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV),
    REM(Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM),
    NEG(Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG),
    SHIFT(Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR, Opcodes.IUSHR, Opcodes.LUSHR),
    AND(Opcodes.IAND, Opcodes.LAND),
    OR(Opcodes.IOR, Opcodes.LOR),
    XOR(Opcodes.IXOR, Opcodes.LXOR),
    IINC(Opcodes.IINC), // you might want to move this under ADD
    FROM_INT_CAST(Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S),
    FROM_LONG_CAST(Opcodes.L2I, Opcodes.L2F, Opcodes.L2D),
    FROM_FLOAT_CAST(Opcodes.F2I, Opcodes.F2L, Opcodes.F2D),
    FROM_DOUBLE_CAST(Opcodes.D2I, Opcodes.D2L, Opcodes.D2F),
    SGN_COMP(Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG),
    EQUAL(Opcodes.IFEQ, Opcodes.IF_ICMPEQ, Opcodes.IF_ACMPEQ, Opcodes.IFNULL),
    NOT_EQUAL(Opcodes.IFNE, Opcodes.IF_ICMPNE, Opcodes.IF_ACMPNE, Opcodes.IFNONNULL),
    LESS_THAN(Opcodes.IFLT, Opcodes.IF_ICMPLT),
    LESS_THAN_EQUAL(Opcodes.IFLE, Opcodes.IF_ICMPLE),
    GREATER_THAN(Opcodes.IFGT, Opcodes.IF_ICMPGT),
    GREATER_THAN_EQUAL(Opcodes.IFGE, Opcodes.IF_ICMPGE),
    ACC_STATIC_FIELD(Opcodes.GETSTATIC, Opcodes.PUTSTATIC),
    ACC_INST_FIELD(Opcodes.GETFIELD, Opcodes.PUTFIELD),
    INVOKE_VIRTUAL(Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE),
    INVOKE_DYNAMIC(Opcodes.INVOKEDYNAMIC),
    INVOKE_STATIC(Opcodes.INVOKESTATIC),
    NEW(Opcodes.NEW),
    NEW_ARRAY(Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY),
    ARRAY_LEN(Opcodes.ARRAYLENGTH),
    THROW(Opcodes.ATHROW),
    CHECK_CAST(Opcodes.CHECKCAST),
    MONITOR(Opcodes.MONITORENTER, Opcodes.MONITOREXIT),
    GOTO(Opcodes.GOTO),
    JSR(Opcodes.JSR),
    RET(Opcodes.RET),
    SWITCH_CASE(Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH),
    RETURN(Opcodes.RETURN, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN,
            Opcodes.DRETURN, Opcodes.ARETURN),
    UNKNOWN;

    private final int[] ops;

    AbstractInstruction(final int... ops) {
        this.ops = ops;
    }

    public boolean isIn(final int opcode) {
        if (this == UNKNOWN) {
            return true;
        }
        for (final int op : ops) {
            if (op == opcode) {
                return true;
            }
        }
        return false;
    }

    public static AbstractInstruction fromOpcode(final int opcode) {
        for (final AbstractInstruction ic : values()) {
            if (ic.isIn(opcode)) {
                return ic;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return String.valueOf(this.ordinal());
    }
}