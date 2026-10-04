/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary Test block-local elimination of redundant RVV vector configuration
 * @library /test/lib /
 * @requires vm.compiler2.enabled & os.arch == "riscv64" & vm.cpu.features ~= ".*rvv.*"
 * @modules jdk.incubator.vector
 * @run driver compiler.c2.riscv64.TestVSETVLIElimination
 */

package compiler.c2.riscv64;

import compiler.lib.ir_framework.CompLevel;
import compiler.lib.ir_framework.CompilePhase;
import compiler.lib.ir_framework.DontInline;
import compiler.lib.ir_framework.ForceCompile;
import compiler.lib.ir_framework.IR;
import compiler.lib.ir_framework.Run;
import compiler.lib.ir_framework.Test;
import compiler.lib.ir_framework.TestFramework;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.ProcessTools;

import java.util.Arrays;

public class TestVSETVLIElimination {
    private static final String KEPT = "rvv_vsetvli=kept";
    private static final String ELIDED = "rvv_vsetvli=elided";
    private static final int[] IA = {0x12345678, -77, 903, 0x76543210};
    private static final int[] IB = {37, 0x1234, -51, 99};
    private static final int[] IR = new int[4];
    private static final int[] SCALAR_WORK = new int[4];
    private static final int[] SCALAR_RESULT = new int[12];
    private static final byte[] HASH_BUFFER = new byte[256];
    private static final byte[] HASH_RESULT = new byte[64];
    private static final float[] FA = {1.25f, -2.5f, 3.75f, -4.125f};
    private static final float[] FB = {-3.5f, 2.25f, 1.125f, -0.5f};
    private static final float[] FR = new float[4];
    private static final double[] DA = {1.125, -7.25};
    private static final double[] DB = {3.75, 0.625};
    private static final double[] DR = new double[2];
    private static final byte[] BA = new byte[32];
    private static final byte[] BB = new byte[32];
    private static final byte[] BR = new byte[32];
    private static final long[] LR = new long[2];
    private static final int[] SMALL = new int[2];
    private static final boolean[] MASK = {true, false, false, true};
    private static final byte[] EDGE_BA = {
        Byte.MIN_VALUE, Byte.MAX_VALUE, -1, 0, 1, -127, 126, -64,
        64, -5, 5, -100, 100, -2, 2, 42
    };
    private static final byte[] EDGE_BB = {
        0, 1, Byte.MIN_VALUE, Byte.MAX_VALUE, -1, -1, 1, -64,
        64, 7, -7, -28, 28, 1, -1, -42
    };
    private static final short[] EDGE_SA = {
        Short.MIN_VALUE, Short.MAX_VALUE, -1, 0, 1, -16384, 16384, -7
    };
    private static final short[] EDGE_SB = {
        0, 1, Short.MIN_VALUE, Short.MAX_VALUE, -1, -16384, 16384, 19
    };
    private static final int[] EDGE_IA = {
        Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0, 1, Integer.MIN_VALUE + 1,
        Integer.MAX_VALUE - 1, -37, Integer.MIN_VALUE, 1, Integer.MAX_VALUE, 0
    };
    private static final int[] EDGE_IB = {
        0, 1, Integer.MIN_VALUE, Integer.MAX_VALUE, -1, -1,
        1, 19, -1, Integer.MIN_VALUE, Integer.MIN_VALUE, -1
    };
    private static final long[] EDGE_LA = {
        Long.MIN_VALUE, Long.MAX_VALUE, -1, 0, 1, Long.MIN_VALUE + 1,
        Long.MAX_VALUE - 1, -37, Long.MIN_VALUE, 1, Long.MAX_VALUE, 0
    };
    private static final long[] EDGE_LB = {
        0, 1, Long.MIN_VALUE, Long.MAX_VALUE, -1, -1,
        1, 19, -1, Long.MIN_VALUE, Long.MIN_VALUE, -1
    };
    private static final float[] ABS_FA = {
        0.0f, -0.0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
        Float.NaN, -1.25f, Float.MIN_VALUE, -Float.MIN_VALUE,
        Float.MIN_NORMAL, -Float.MIN_NORMAL, Float.MAX_VALUE, -Float.MAX_VALUE
    };
    private static final double[] ABS_DA = {
        0.0, -0.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Double.NaN, -1.25, Double.MIN_VALUE, -Double.MIN_VALUE,
        Double.MIN_NORMAL, -Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE
    };
    private static final byte[] ABS_BR = new byte[16];
    private static final float[] ABS_FR = new float[4];
    private static final double[] ABS_DR = new double[2];
    private static int calls;

    static {
        for (int i = 0; i < BA.length; i++) {
            BA[i] = (byte) (13 * i + 7);
            BB[i] = (byte) (5 * i - 63);
        }
    }

    public static void main(String[] args) throws Exception {
        ProcessTools.executeTestJava("-XX:+UnlockDiagnosticVMOptions", "-XX:-UseRVV",
                                     "-XX:+VSETVLIElimination", "-XX:+PrintFlagsFinal", "-version")
                .shouldHaveExitValue(0)
                .shouldContain("VSETVLIElimination requires UseRVV. Disabling VSETVLIElimination.")
                .shouldMatch("VSETVLIElimination\\s+= false");

        new TestFramework()
                .addFlags("-XX:-TieredCompilation", "--add-modules=jdk.incubator.vector",
                          "-XX:+UnlockDiagnosticVMOptions", "-XX:+VSETVLIElimination",
                          "-XX:CompileCommand=inline,java.util.Arrays::hashCode",
                          "-XX:CompileCommand=inline,jdk.internal.util.ArraysSupport::hashCode")
                .start();
    }

    @Test
    @IR(counts = {KEPT, ">= 1", ELIDED, ">= 1", "storeV [^\\r\\n]*" + ELIDED, ">= 1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testInt() {
        IntVector a = IntVector.fromArray(IntVector.SPECIES_128, IA, 0);
        IntVector b = IntVector.fromArray(IntVector.SPECIES_128, IB, 0);
        a.add(b).mul(b).lanewise(VectorOperators.XOR, a).sub(b).and(a).or(b).intoArray(IR, 0);
    }

    @Run(test = "testInt")
    public static void runInt() {
        testInt();
        for (int i = 0; i < IR.length; i++) {
            Asserts.assertEQ(IR[i], (((((IA[i] + IB[i]) * IB[i]) ^ IA[i]) - IB[i]) & IA[i]) | IB[i]);
        }
    }

    @Test
    @IR(counts = {KEPT, ">= 1", ELIDED, ">= 1", "storeV [^\\r\\n]*" + ELIDED, ">= 1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testFloat() {
        FloatVector a = FloatVector.fromArray(FloatVector.SPECIES_128, FA, 0);
        FloatVector b = FloatVector.fromArray(FloatVector.SPECIES_128, FB, 0);
        a.add(b).mul(b).sub(a).intoArray(FR, 0);
    }

    @Run(test = "testFloat")
    public static void runFloat() {
        testFloat();
        for (int i = 0; i < FR.length; i++) {
            Asserts.assertEQ(FR[i], (FA[i] + FB[i]) * FB[i] - FA[i]);
        }
    }

    @Test
    @IR(counts = {KEPT, ">= 1", ELIDED, ">= 1", "storeV [^\\r\\n]*" + ELIDED, ">= 1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testDouble() {
        DoubleVector a = DoubleVector.fromArray(DoubleVector.SPECIES_128, DA, 0);
        DoubleVector b = DoubleVector.fromArray(DoubleVector.SPECIES_128, DB, 0);
        a.add(b).mul(b).sub(a).intoArray(DR, 0);
    }

    @Run(test = "testDouble")
    public static void runDouble() {
        testDouble();
        for (int i = 0; i < DR.length; i++) {
            Asserts.assertEQ(DR[i], (DA[i] + DB[i]) * DB[i] - DA[i]);
        }
    }

    @Test
    @IR(counts = {KEPT, ">= 1", ELIDED, ">= 1", "storeV [^\\r\\n]*" + ELIDED, ">= 1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY, applyIf = {"MaxVectorSize", ">= 32"})
    public static void testByte32() {
        ByteVector a = ByteVector.fromArray(ByteVector.SPECIES_256, BA, 0);
        ByteVector b = ByteVector.fromArray(ByteVector.SPECIES_256, BB, 0);
        a.add(b).mul(b).lanewise(VectorOperators.XOR, a).sub(b).intoArray(BR, 0);
    }

    @Run(test = "testByte32")
    public static void runByte32() {
        testByte32();
        for (int i = 0; i < BR.length; i++) {
            Asserts.assertEQ(BR[i], (byte) ((((BA[i] + BB[i]) * BB[i]) ^ BA[i]) - BB[i]));
        }
    }

    @Test
    @IR(counts = {KEPT, ">= 1", "vabs [^\\r\\n]*" + ELIDED, ">= 1",
                  "vmin [^\\r\\n]*" + ELIDED, ">= 1", "vmax [^\\r\\n]*" + ELIDED, ">= 1",
                  "storeV [^\\r\\n]*" + ELIDED, ">= 1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testByteAbsMinMax() {
        ByteVector a = ByteVector.fromArray(ByteVector.SPECIES_128, EDGE_BA, 0);
        ByteVector b = ByteVector.fromArray(ByteVector.SPECIES_128, EDGE_BB, 0);
        a.add(b).abs().min(b).max(a).intoArray(ABS_BR, 0);
    }

    @Run(test = "testByteAbsMinMax")
    public static void runByteAbsMinMax() {
        testByteAbsMinMax();
        for (int i = 0; i < ABS_BR.length; i++) {
            byte sum = (byte) (EDGE_BA[i] + EDGE_BB[i]);
            byte abs = (byte) Math.abs(sum);
            Asserts.assertEQ(ABS_BR[i], (byte) Math.max(Math.min(abs, EDGE_BB[i]), EDGE_BA[i]));
        }
    }

    @Test
    @IR(counts = {KEPT, ">= 1", "vabs [^\\r\\n]*" + ELIDED, ">= 1",
                  "vmin [^\\r\\n]*" + ELIDED, ">= 1", "vmax [^\\r\\n]*" + ELIDED, ">= 1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testShortAbsMinMax(short[] inputA, short[] inputB, int offset) {
        ShortVector a = ShortVector.fromArray(ShortVector.SPECIES_128, inputA, offset);
        ShortVector b = ShortVector.fromArray(ShortVector.SPECIES_128, inputB, offset);
        a.add(b).abs().min(b).max(a).intoArray(inputA, offset);
    }

    @Run(test = "testShortAbsMinMax")
    public static void runShortAbsMinMax() {
        short[] result = EDGE_SA.clone();
        for (int offset = 0; offset < result.length; offset += ShortVector.SPECIES_128.length()) {
            testShortAbsMinMax(result, EDGE_SB, offset);
        }
        for (int i = 0; i < result.length; i++) {
            short sum = (short) (EDGE_SA[i] + EDGE_SB[i]);
            short abs = (short) Math.abs(sum);
            Asserts.assertEQ(result[i], (short) Math.max(Math.min(abs, EDGE_SB[i]), EDGE_SA[i]));
        }
    }

    @Test
    @IR(counts = {KEPT, ">= 1", "vabs [^\\r\\n]*" + ELIDED, ">= 1",
                  "vmin [^\\r\\n]*" + ELIDED, ">= 1", "vmax [^\\r\\n]*" + ELIDED, ">= 1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testIntAbsMinMax(int[] inputA, int[] inputB, int offset) {
        IntVector a = IntVector.fromArray(IntVector.SPECIES_128, inputA, offset);
        IntVector b = IntVector.fromArray(IntVector.SPECIES_128, inputB, offset);
        a.add(b).abs().min(b).max(a).intoArray(inputA, offset);
    }

    @Run(test = "testIntAbsMinMax")
    public static void runIntAbsMinMax() {
        int[] result = EDGE_IA.clone();
        for (int offset = 0; offset < result.length; offset += IntVector.SPECIES_128.length()) {
            testIntAbsMinMax(result, EDGE_IB, offset);
        }
        for (int i = 0; i < result.length; i++) {
            int sum = EDGE_IA[i] + EDGE_IB[i];
            Asserts.assertEQ(result[i], Math.max(Math.min(Math.abs(sum), EDGE_IB[i]), EDGE_IA[i]));
        }
    }

    @Test
    @IR(counts = {KEPT, ">= 1", "vabs [^\\r\\n]*" + ELIDED, ">= 1",
                  "vmin [^\\r\\n]*" + ELIDED, ">= 1", "vmax [^\\r\\n]*" + ELIDED, ">= 1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testLongAbsMinMax(long[] inputA, long[] inputB, int offset) {
        LongVector a = LongVector.fromArray(LongVector.SPECIES_128, inputA, offset);
        LongVector b = LongVector.fromArray(LongVector.SPECIES_128, inputB, offset);
        a.add(b).abs().min(b).max(a).intoArray(inputA, offset);
    }

    @Run(test = "testLongAbsMinMax")
    public static void runLongAbsMinMax() {
        long[] result = EDGE_LA.clone();
        for (int offset = 0; offset < result.length; offset += LongVector.SPECIES_128.length()) {
            testLongAbsMinMax(result, EDGE_LB, offset);
        }
        for (int i = 0; i < result.length; i++) {
            long sum = EDGE_LA[i] + EDGE_LB[i];
            Asserts.assertEQ(result[i], Math.max(Math.min(Math.abs(sum), EDGE_LB[i]), EDGE_LA[i]));
        }
    }

    @Test
    @IR(counts = {KEPT, ">= 1", "vminu [^\\r\\n]*" + ELIDED, ">= 1", "vmaxu [^\\r\\n]*" + ELIDED, ">= 1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testIntUnsignedMinMax(int[] inputA, int[] inputB, int[] result, int offset) {
        IntVector a = IntVector.fromArray(IntVector.SPECIES_128, inputA, offset);
        IntVector b = IntVector.fromArray(IntVector.SPECIES_128, inputB, offset);
        a.add(b).lanewise(VectorOperators.UMIN, b).lanewise(VectorOperators.UMAX, a).intoArray(result, offset);
    }

    @Run(test = "testIntUnsignedMinMax")
    public static void runIntUnsignedMinMax() {
        int[] result = new int[EDGE_IA.length];
        for (int offset = 0; offset < result.length; offset += IntVector.SPECIES_128.length()) {
            testIntUnsignedMinMax(EDGE_IA, EDGE_IB, result, offset);
        }
        for (int i = 0; i < result.length; i++) {
            int sum = EDGE_IA[i] + EDGE_IB[i];
            int min = Integer.compareUnsigned(sum, EDGE_IB[i]) < 0 ? sum : EDGE_IB[i];
            int max = Integer.compareUnsigned(min, EDGE_IA[i]) > 0 ? min : EDGE_IA[i];
            Asserts.assertEQ(result[i], max);
        }
    }

    @Test
    @IR(counts = {KEPT, ">= 1", "vminu [^\\r\\n]*" + ELIDED, ">= 1", "vmaxu [^\\r\\n]*" + ELIDED, ">= 1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testLongUnsignedMinMax(long[] inputA, long[] inputB, long[] result, int offset) {
        LongVector a = LongVector.fromArray(LongVector.SPECIES_128, inputA, offset);
        LongVector b = LongVector.fromArray(LongVector.SPECIES_128, inputB, offset);
        a.add(b).lanewise(VectorOperators.UMIN, b).lanewise(VectorOperators.UMAX, a).intoArray(result, offset);
    }

    @Run(test = "testLongUnsignedMinMax")
    public static void runLongUnsignedMinMax() {
        long[] result = new long[EDGE_LA.length];
        for (int offset = 0; offset < result.length; offset += LongVector.SPECIES_128.length()) {
            testLongUnsignedMinMax(EDGE_LA, EDGE_LB, result, offset);
        }
        for (int i = 0; i < result.length; i++) {
            long sum = EDGE_LA[i] + EDGE_LB[i];
            long min = Long.compareUnsigned(sum, EDGE_LB[i]) < 0 ? sum : EDGE_LB[i];
            long max = Long.compareUnsigned(min, EDGE_LA[i]) > 0 ? min : EDGE_LA[i];
            Asserts.assertEQ(result[i], max);
        }
    }

    @Test
    @IR(counts = {KEPT, ">= 1", "vabs_fp [^\\r\\n]*" + ELIDED, ">= 1"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testFloatAbs() {
        FloatVector a = FloatVector.fromArray(FloatVector.SPECIES_128, ABS_FR, 0);
        a.add(a).abs().intoArray(ABS_FR, 0);
    }

    @Run(test = "testFloatAbs")
    public static void runFloatAbs() {
        for (int offset = 0; offset < ABS_FA.length; offset += ABS_FR.length) {
            System.arraycopy(ABS_FA, offset, ABS_FR, 0, ABS_FR.length);
            testFloatAbs();
            for (int i = 0; i < ABS_FR.length; i++) {
                float input = ABS_FA[offset + i];
                Asserts.assertEQ(Float.floatToIntBits(ABS_FR[i]), Float.floatToIntBits(Math.abs(input + input)));
            }
        }
    }

    @Test
    @IR(counts = {KEPT, ">= 1", "vabs_fp [^\\r\\n]*" + ELIDED, ">= 1"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testDoubleAbs() {
        DoubleVector a = DoubleVector.fromArray(DoubleVector.SPECIES_128, ABS_DR, 0);
        a.add(a).abs().intoArray(ABS_DR, 0);
    }

    @Run(test = "testDoubleAbs")
    public static void runDoubleAbs() {
        for (int offset = 0; offset < ABS_DA.length; offset += ABS_DR.length) {
            System.arraycopy(ABS_DA, offset, ABS_DR, 0, ABS_DR.length);
            testDoubleAbs();
            for (int i = 0; i < ABS_DR.length; i++) {
                double input = ABS_DA[offset + i];
                Asserts.assertEQ(Double.doubleToLongBits(ABS_DR[i]), Double.doubleToLongBits(Math.abs(input + input)));
            }
        }
    }

    @Test
    @IR(counts = {"storeV [^\\r\\n]*" + ELIDED, ">= 1"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testFloatStoreAfterMinAbs() {
        FloatVector a = FloatVector.fromArray(FloatVector.SPECIES_128, FA, 0);
        FloatVector b = FloatVector.fromArray(FloatVector.SPECIES_128, FB, 0);
        a.min(b).abs().intoArray(FR, 0);
    }

    @Run(test = "testFloatStoreAfterMinAbs")
    public static void runFloatStoreAfterMinAbs() {
        testFloatStoreAfterMinAbs();
        for (int i = 0; i < FR.length; i++) {
            Asserts.assertEQ(FR[i], Math.abs(Math.min(FA[i], FB[i])));
        }
    }

    @Test
    @IR(counts = {"storeV [^\\r\\n]*" + ELIDED, ">= 1"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testDoubleStoreAfterMinAbs() {
        DoubleVector a = DoubleVector.fromArray(DoubleVector.SPECIES_128, DA, 0);
        DoubleVector b = DoubleVector.fromArray(DoubleVector.SPECIES_128, DB, 0);
        a.min(b).abs().intoArray(DR, 0);
    }

    @Run(test = "testDoubleStoreAfterMinAbs")
    public static void runDoubleStoreAfterMinAbs() {
        testDoubleStoreAfterMinAbs();
        for (int i = 0; i < DR.length; i++) {
            Asserts.assertEQ(DR[i], Math.abs(Math.min(DA[i], DB[i])));
        }
    }

    @Test
    @IR(counts = {"vmul [^\\r\\n]*" + KEPT, ">= 1"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testMasked() {
        IntVector a = IntVector.fromArray(IntVector.SPECIES_128, IA, 0);
        IntVector b = IntVector.fromArray(IntVector.SPECIES_128, IB, 0);
        IntVector c = a.add(b, VectorMask.fromArray(IntVector.SPECIES_128, MASK, 0));
        c.mul(c).intoArray(IR, 0);
    }

    @Run(test = "testMasked")
    public static void runMasked() {
        testMasked();
        for (int i = 0; i < IR.length; i++) {
            int c = MASK[i] ? IA[i] + IB[i] : IA[i];
            Asserts.assertEQ(IR[i], c * c);
        }
    }

    @Test
    @IR(counts = {"vadd_masked ", ">= 1", "storeV [^\\r\\n]*" + KEPT, ">= 1"},
        failOn = {"storeV [^\\r\\n]*" + ELIDED}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testStoreAfterMasked() {
        IntVector a = IntVector.fromArray(IntVector.SPECIES_128, IA, 0);
        IntVector b = IntVector.fromArray(IntVector.SPECIES_128, IB, 0);
        a.add(b, VectorMask.fromArray(IntVector.SPECIES_128, MASK, 0)).intoArray(IR, 0);
    }

    @Run(test = "testStoreAfterMasked")
    public static void runStoreAfterMasked() {
        testStoreAfterMasked();
        for (int i = 0; i < IR.length; i++) {
            Asserts.assertEQ(IR[i], MASK[i] ? IA[i] + IB[i] : IA[i]);
        }
    }

    @Test
    @IR(counts = {"storeV [^\\r\\n]*" + ELIDED, "2"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static int testScalarAddress(int salt) {
        IntVector a = IntVector.fromArray(IntVector.SPECIES_128, IA, 0);
        IntVector b = IntVector.fromArray(IntVector.SPECIES_128, IB, 0);
        IntVector sum = a.add(b);
        sum.intoArray(SCALAR_WORK, 0);
        int offset = 2 + (((SCALAR_WORK[0] + salt) ^ (SCALAR_WORK[1] - salt)) & 3);
        sum.intoArray(SCALAR_RESULT, offset);
        return offset;
    }

    @Run(test = "testScalarAddress")
    public static void runScalarAddress() {
        int sentinel = 0x5a5a5a5a;
        for (int salt : new int[] {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            Arrays.fill(SCALAR_RESULT, sentinel);
            int offset = testScalarAddress(salt);
            int expectedOffset = 2 + ((((IA[0] + IB[0]) + salt) ^ ((IA[1] + IB[1]) - salt)) & 3);
            Asserts.assertEQ(offset, expectedOffset);
            for (int i = 0; i < SCALAR_WORK.length; i++) {
                Asserts.assertEQ(SCALAR_WORK[i], IA[i] + IB[i]);
            }
            for (int i = 0; i < SCALAR_RESULT.length; i++) {
                int expected = i >= offset && i < offset + 4 ? IA[i - offset] + IB[i - offset] : sentinel;
                Asserts.assertEQ(SCALAR_RESULT[i], expected);
            }
        }
    }

    @Test
    @IR(counts = {"Array HashCode", "1", "Array HashCode[\\s\\S]*storeV [^\\r\\n]*" + KEPT, "1"},
        failOn = {"spill ", "CALL,"}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static int testVectorTemporary() {
        ByteVector a = ByteVector.fromArray(ByteVector.SPECIES_128, HASH_BUFFER, 0);
        ByteVector sum = a.add(a);
        sum.intoArray(HASH_BUFFER, 0);
        int hash = Arrays.hashCode(HASH_BUFFER);
        sum.intoArray(HASH_RESULT, 8 + (hash & 15));
        return hash;
    }

    @Run(test = "testVectorTemporary")
    public static void runVectorTemporary() {
        byte sentinel = 0x5a;
        for (int i = 0; i < HASH_BUFFER.length; i++) {
            HASH_BUFFER[i] = (byte) (37 * i + 11);
        }
        Arrays.fill(HASH_RESULT, sentinel);
        byte[] expectedBuffer = HASH_BUFFER.clone();
        for (int i = 0; i < ByteVector.SPECIES_128.length(); i++) {
            expectedBuffer[i] += expectedBuffer[i];
        }
        int expectedHash = 1;
        for (byte value : expectedBuffer) {
            expectedHash = 31 * expectedHash + value;
        }
        int hash = testVectorTemporary();
        Asserts.assertEQ(hash, expectedHash);
        Asserts.assertTrue(Arrays.equals(HASH_BUFFER, expectedBuffer));
        int offset = 8 + (hash & 15);
        for (int i = 0; i < HASH_RESULT.length; i++) {
            byte expected = i >= offset && i < offset + 16 ? expectedBuffer[i - offset] : sentinel;
            Asserts.assertEQ(HASH_RESULT[i], expected);
        }
    }

    @Test
    @IR(counts = {"# reinterpret [^\\r\\n]*\\R[0-9a-f]+\\h+vmul [^\\r\\n]*" + KEPT, ">= 1",
                  "reinterpretResize [^\\r\\n]*\\R[0-9a-f]+\\h+vmul [^\\r\\n]*" + KEPT, ">= 1",
                  "storeV [^\\r\\n]*" + ELIDED + "\\R[0-9a-f]+\\h+storeV [^\\r\\n]*" + KEPT, ">= 1"},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testShapeChanges() {
        IntVector a = IntVector.fromArray(IntVector.SPECIES_128, IA, 0);
        IntVector b = IntVector.fromArray(IntVector.SPECIES_128, IB, 0);
        IntVector sum = a.add(b);
        LongVector wide = sum.reinterpretAsLongs();
        wide.mul(wide).intoArray(LR, 0);
        IntVector small = (IntVector) sum.reinterpretShape(IntVector.SPECIES_64, 0);
        small.mul(small).intoArray(SMALL, 0);
        sum.mul(sum).intoArray(IR, 0);
    }

    @Run(test = "testShapeChanges")
    public static void runShapeChanges() {
        testShapeChanges();
        for (int i = 0; i < LR.length; i++) {
            long low = Integer.toUnsignedLong(IA[2 * i] + IB[2 * i]);
            long high = (long) (IA[2 * i + 1] + IB[2 * i + 1]) << 32;
            long value = high | low;
            Asserts.assertEQ(LR[i], value * value);
        }
        for (int i = 0; i < IR.length; i++) {
            int sum = IA[i] + IB[i];
            Asserts.assertEQ(IR[i], sum * sum);
        }
        for (int i = 0; i < SMALL.length; i++) {
            Asserts.assertEQ(SMALL[i], IR[i]);
        }
    }

    private static int[] pressureInput(int length, int seed) {
        int[] input = new int[length];
        for (int i = 0; i < length; i++) {
            input[i] = Integer.rotateLeft(0x9e3779b9 * (i + 1), i + seed) ^ (seed * 0x45d9f3b);
        }
        return input;
    }

    @Test
    @IR(counts = {"spill [^\\r\\n]*# vector spill size = \\d+\\R[0-9a-f]+\\h+(loadV|vxor) [^\\r\\n]*" + KEPT, ">= 1",
                  "spill \\[sp,[^\\r\\n]*# spill size = (32|64)\\R[0-9a-f]+\\h+loadV [^\\r\\n]*" + ELIDED, ">= 1"},
        failOn = {"spill [^\\r\\n]*# vector spill size = \\d+\\R[0-9a-f]+\\h+(loadV|vxor) [^\\r\\n]*" + ELIDED},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testVectorSpillPressure(int[] input, int[] replacement, int[] result,
                                             int storeOffset, int gateOffset) {
        IntVector v0 = IntVector.fromArray(IntVector.SPECIES_128, input, 0);
        IntVector v1 = IntVector.fromArray(IntVector.SPECIES_128, input, 4);
        IntVector v2 = IntVector.fromArray(IntVector.SPECIES_128, input, 8);
        IntVector v3 = IntVector.fromArray(IntVector.SPECIES_128, input, 12);
        IntVector v4 = IntVector.fromArray(IntVector.SPECIES_128, input, 16);
        IntVector v5 = IntVector.fromArray(IntVector.SPECIES_128, input, 20);
        IntVector v6 = IntVector.fromArray(IntVector.SPECIES_128, input, 24);
        IntVector v7 = IntVector.fromArray(IntVector.SPECIES_128, input, 28);
        IntVector v8 = IntVector.fromArray(IntVector.SPECIES_128, input, 32);
        IntVector v9 = IntVector.fromArray(IntVector.SPECIES_128, input, 36);
        IntVector v10 = IntVector.fromArray(IntVector.SPECIES_128, input, 40);
        IntVector v11 = IntVector.fromArray(IntVector.SPECIES_128, input, 44);
        IntVector v12 = IntVector.fromArray(IntVector.SPECIES_128, input, 48);
        IntVector v13 = IntVector.fromArray(IntVector.SPECIES_128, input, 52);
        IntVector v14 = IntVector.fromArray(IntVector.SPECIES_128, input, 56);
        IntVector v15 = IntVector.fromArray(IntVector.SPECIES_128, input, 60);
        IntVector v16 = IntVector.fromArray(IntVector.SPECIES_128, input, 64);
        IntVector v17 = IntVector.fromArray(IntVector.SPECIES_128, input, 68);
        IntVector v18 = IntVector.fromArray(IntVector.SPECIES_128, input, 72);
        IntVector v19 = IntVector.fromArray(IntVector.SPECIES_128, input, 76);
        IntVector v20 = IntVector.fromArray(IntVector.SPECIES_128, input, 80);
        IntVector v21 = IntVector.fromArray(IntVector.SPECIES_128, input, 84);
        IntVector v22 = IntVector.fromArray(IntVector.SPECIES_128, input, 88);
        IntVector v23 = IntVector.fromArray(IntVector.SPECIES_128, input, 92);
        IntVector v24 = IntVector.fromArray(IntVector.SPECIES_128, input, 96);
        IntVector v25 = IntVector.fromArray(IntVector.SPECIES_128, input, 100);
        IntVector v26 = IntVector.fromArray(IntVector.SPECIES_128, input, 104);
        IntVector v27 = IntVector.fromArray(IntVector.SPECIES_128, input, 108);
        IntVector v28 = IntVector.fromArray(IntVector.SPECIES_128, input, 112);
        IntVector v29 = IntVector.fromArray(IntVector.SPECIES_128, input, 116);
        IntVector v30 = IntVector.fromArray(IntVector.SPECIES_128, input, 120);
        IntVector v31 = IntVector.fromArray(IntVector.SPECIES_128, input, 124);
        IntVector.fromArray(IntVector.SPECIES_128, replacement, 0).add(17).intoArray(input, storeOffset);
        IntVector gate = IntVector.fromArray(IntVector.SPECIES_128, input, gateOffset);
        v0.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 4);
        v1.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 8);
        v2.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 12);
        v3.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 16);
        v4.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 20);
        v5.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 24);
        v6.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 28);
        v7.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 32);
        v8.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 36);
        v9.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 40);
        v10.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 44);
        v11.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 48);
        v12.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 52);
        v13.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 56);
        v14.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 60);
        v15.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 64);
        v16.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 68);
        v17.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 72);
        v18.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 76);
        v19.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 80);
        v20.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 84);
        v21.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 88);
        v22.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 92);
        v23.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 96);
        v24.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 100);
        v25.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 104);
        v26.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 108);
        v27.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 112);
        v28.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 116);
        v29.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 120);
        v30.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 124);
        v31.lanewise(VectorOperators.XOR, gate).add(gate).intoArray(result, 128);
    }

    @Run(test = "testVectorSpillPressure")
    public static void runVectorSpillPressure() {
        int sentinel = 0x5a5a5a5a;
        for (int variant = 0; variant < 8; variant++) {
            int[] input = pressureInput(136, variant + 31);
            int[] snapshot = input.clone();
            int[] expectedInput = snapshot.clone();
            int[] replacement = pressureInput(4, variant + 41);
            int[] result = new int[136];
            Arrays.fill(result, sentinel);
            int storeOffset = variant * 18;
            int gateOffset = (variant & 1) == 0 ? storeOffset + 1 : (storeOffset + 13) % 129;
            for (int lane = 0; lane < 4; lane++) {
                expectedInput[storeOffset + lane] = replacement[lane] + 17;
            }
            testVectorSpillPressure(input, replacement, result, storeOffset, gateOffset);
            for (int i = 0; i < input.length; i++) {
                Asserts.assertEQ(input[i], expectedInput[i]);
                int expected = sentinel;
                if (i >= 4 && i < 132) {
                    int gate = expectedInput[gateOffset + ((i - 4) & 3)];
                    expected = (snapshot[i - 4] ^ gate) + gate;
                }
                Asserts.assertEQ(result[i], expected);
            }
        }
    }

    @Test
    @IR(counts = {"spill [^\\r\\n]*# vmask spill size = \\d+\\R[0-9a-f]+\\h+loadV [^\\r\\n]*" + KEPT, ">= 1"},
        failOn = {"spill [^\\r\\n]*# vmask spill size = \\d+\\R[0-9a-f]+\\h+loadV [^\\r\\n]*" + ELIDED},
        phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static long testMaskSpillPressure(int[] input, int[] replacement, int[] result,
                                           int storeOffset, int gateOffset) {
        VectorMask<Integer> m0 = IntVector.fromArray(IntVector.SPECIES_128, input, 0).compare(VectorOperators.LT, 0);
        VectorMask<Integer> m1 = IntVector.fromArray(IntVector.SPECIES_128, input, 4).compare(VectorOperators.LT, 0);
        VectorMask<Integer> m2 = IntVector.fromArray(IntVector.SPECIES_128, input, 8).compare(VectorOperators.LT, 0);
        VectorMask<Integer> m3 = IntVector.fromArray(IntVector.SPECIES_128, input, 12).compare(VectorOperators.LT, 0);
        VectorMask<Integer> m4 = IntVector.fromArray(IntVector.SPECIES_128, input, 16).compare(VectorOperators.LT, 0);
        VectorMask<Integer> m5 = IntVector.fromArray(IntVector.SPECIES_128, input, 20).compare(VectorOperators.LT, 0);
        VectorMask<Integer> m6 = IntVector.fromArray(IntVector.SPECIES_128, input, 24).compare(VectorOperators.LT, 0);
        VectorMask<Integer> m7 = IntVector.fromArray(IntVector.SPECIES_128, input, 28).compare(VectorOperators.LT, 0);
        IntVector.fromArray(IntVector.SPECIES_128, replacement, 0).add(17).intoArray(input, storeOffset);
        IntVector gateVector = IntVector.fromArray(IntVector.SPECIES_128, input, gateOffset);
        VectorMask<Integer> gate = gateVector.compare(VectorOperators.LT, 0);
        m0 = m0.and(gate); m1 = m1.and(gate);
        m2 = m2.and(gate); m3 = m3.and(gate);
        m4 = m4.and(gate); m5 = m5.and(gate);
        m6 = m6.and(gate); m7 = m7.and(gate);
        long bits = m0.toLong() | (m1.toLong() << 4) | (m2.toLong() << 8) | (m3.toLong() << 12)
                | (m4.toLong() << 16) | (m5.toLong() << 20) | (m6.toLong() << 24) | (m7.toLong() << 28);
        int count = m0.trueCount() + m1.trueCount() + m2.trueCount() + m3.trueCount()
                + m4.trueCount() + m5.trueCount() + m6.trueCount() + m7.trueCount();
        gateVector.add(gateVector).intoArray(result, 1 + (count & 7));
        return bits | ((long) count << 32);
    }

    @Run(test = "testMaskSpillPressure")
    public static void runMaskSpillPressure() {
        int sentinel = 0x5a5a5a5a;
        for (int variant = 0; variant < 8; variant++) {
            int[] input = pressureInput(40, variant + 43);
            for (int i = 0; i < 32; i++) {
                int pattern = (i / 4 + variant * 3) & 15;
                input[i] = (pattern & (1 << (i & 3))) != 0 ? -(i + 1) : i + 1;
            }
            int[] snapshot = input.clone();
            int[] expectedInput = snapshot.clone();
            int[] replacement = pressureInput(4, variant + 47);
            int[] result = new int[16];
            Arrays.fill(result, sentinel);
            int storeOffset = variant * 5;
            int gateOffset = (variant & 1) == 0 ? storeOffset : (storeOffset + 9) % 37;
            for (int lane = 0; lane < 4; lane++) {
                expectedInput[storeOffset + lane] = replacement[lane] + 17;
            }
            long expectedBits = 0;
            for (int i = 0; i < 32; i++) {
                if (snapshot[i] < 0 && expectedInput[gateOffset + (i & 3)] < 0) {
                    expectedBits |= 1L << i;
                }
            }
            int expectedCount = Long.bitCount(expectedBits);
            long actual = testMaskSpillPressure(input, replacement, result, storeOffset, gateOffset);
            Asserts.assertEQ(actual & 0xffff_ffffL, expectedBits);
            Asserts.assertEQ(actual >>> 32, (long) expectedCount);
            for (int i = 0; i < input.length; i++) {
                Asserts.assertEQ(input[i], expectedInput[i]);
            }
            int resultOffset = 1 + (expectedCount & 7);
            for (int i = 0; i < result.length; i++) {
                Asserts.assertEQ(result[i], i >= resultOffset && i < resultOffset + 4
                        ? 2 * expectedInput[gateOffset + i - resultOffset] : sentinel);
            }
        }
    }

    @DontInline
    @ForceCompile(CompLevel.C2)
    public static void clobberVectorState() {
        calls++;
        ByteVector a = ByteVector.fromArray(ByteVector.SPECIES_128, BA, 0);
        a.mul(a).intoArray(BR, 0);
    }

    // C2 can hoist the multiply before the call; the merged XOR must reestablish the configuration.
    @Test
    @IR(counts = {"CALL,[^\\r\\n]*::clobberVectorState", "1",
                  "vsub [^\\r\\n]*" + KEPT, "1", "vxor [^\\r\\n]*" + KEPT, "1",
                  "storeV [^\\r\\n]*" + ELIDED, "1"},
        failOn = {"(vsub|vxor) [^\\r\\n]*" + ELIDED}, phase = CompilePhase.PRINT_OPTO_ASSEMBLY)
    public static void testCallAndBranch(boolean call) {
        IntVector a = IntVector.fromArray(IntVector.SPECIES_128, IA, 0);
        IntVector b = IntVector.fromArray(IntVector.SPECIES_128, IB, 0);
        IntVector c = a.add(b);
        if (call) {
            clobberVectorState();
            c = c.mul(b);
        } else {
            c = c.sub(b);
        }
        c.lanewise(VectorOperators.XOR, a).intoArray(IR, 0);
    }

    @Run(test = "testCallAndBranch")
    public static void runCallAndBranch() {
        int previousCalls = calls;
        for (boolean call : new boolean[] {false, true}) {
            testCallAndBranch(call);
            for (int i = 0; i < IR.length; i++) {
                int sum = IA[i] + IB[i];
                int value = call ? sum * IB[i] : sum - IB[i];
                Asserts.assertEQ(IR[i], value ^ IA[i]);
            }
        }
        Asserts.assertEQ(calls, previousCalls + 1);
    }
}
