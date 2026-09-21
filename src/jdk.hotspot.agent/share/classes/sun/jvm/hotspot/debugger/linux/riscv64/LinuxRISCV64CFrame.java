/*
 * Copyright (c) 2003, 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2015, Red Hat Inc.
 * Copyright (c) 2021, Huawei Technologies Co., Ltd. All rights reserved.
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
 *
 */

package sun.jvm.hotspot.debugger.linux.riscv64;

import java.util.function.Function;

import sun.jvm.hotspot.debugger.*;
import sun.jvm.hotspot.debugger.riscv64.*;
import sun.jvm.hotspot.debugger.linux.*;
import sun.jvm.hotspot.debugger.cdbg.*;
import sun.jvm.hotspot.code.*;
import sun.jvm.hotspot.runtime.*;
import sun.jvm.hotspot.runtime.riscv64.*;

public final class LinuxRISCV64CFrame extends DwarfCFrame {

   private Address ra;

   private static LinuxRISCV64CFrame getFrameFromReg(LinuxDebugger linuxDbg, Function<Integer, Address> getreg) {
      Address pc = getreg.apply(RISCV64ThreadContext.PC);
      Address sp = getreg.apply(RISCV64ThreadContext.SP);
      Address fp = getreg.apply(RISCV64ThreadContext.FP);
      Address ra = getreg.apply(RISCV64ThreadContext.LR);
      Address cfa = null;
      DwarfParser dwarf = createDwarfParser(linuxDbg, pc);

      if (dwarf != null) { // Native frame
        cfa = getreg.apply(dwarf.getCFARegister())
                    .addOffsetTo(dwarf.getCFAOffset());
      }

      return (fp == null && cfa == null)
        ? null
        : new LinuxRISCV64CFrame(linuxDbg, sp, fp, cfa, pc, ra, dwarf);
   }

   public static LinuxRISCV64CFrame getTopFrame(LinuxDebugger linuxDbg, ThreadContext context) {
      return getFrameFromReg(linuxDbg, context::getRegisterAsAddress);
   }

   private LinuxRISCV64CFrame(LinuxDebugger linuxDbg, Address sp, Address fp, Address cfa, Address pc, Address ra, DwarfParser dwarf) {
      this(linuxDbg, sp, fp, cfa, pc, ra, dwarf, false);
   }

   private LinuxRISCV64CFrame(LinuxDebugger linuxDbg, Address sp, Address fp, Address cfa, Address pc, DwarfParser dwarf) {
      this(linuxDbg, sp, fp, cfa, pc, null, dwarf, false);
   }

   private LinuxRISCV64CFrame(LinuxDebugger linuxDbg, Address sp, Address fp, Address cfa, Address pc, DwarfParser dwarf, boolean use1ByteBeforeToLookup) {
      this(linuxDbg, sp, fp, cfa, pc, null, dwarf, use1ByteBeforeToLookup);
   }

   private LinuxRISCV64CFrame(LinuxDebugger linuxDbg, Address sp, Address fp, Address cfa, Address pc, Address ra, DwarfParser dwarf, boolean use1ByteBeforeToLookup) {
      super(linuxDbg, sp, fp, cfa, pc, dwarf, use1ByteBeforeToLookup);

      if (dwarf != null) {
        // Prioritize to use RA from DWARF instead of RA register
        var senderPCFromDwarf = getSenderPC(null);
        if (senderPCFromDwarf != null) {
          ra = senderPCFromDwarf;
        } else if (ra != null) {
          // We should set passed ra to RA of this frame,
          // but throws DebuggerException if ra is not used for RA.
          var raReg = dwarf.getRARegister();
          if (raReg != RISCV64ThreadContext.LR) {
            throw new DebuggerException("Unexpected RA register: " + raReg);
          }
        }
      }

      this.ra = ra;
   }

   private Address getSenderCFA(DwarfParser senderDwarf, Address senderSP, Address senderFP) {
     if (senderDwarf == null) { // Sender frame is Java
       // CFA is not available on Java frame
       return null;
     }

     // Sender frame is Native
     int senderCFAReg = senderDwarf.getCFARegister();
     return switch(senderCFAReg){
       case RISCV64ThreadContext.FP -> senderFP.addOffsetTo(senderDwarf.getCFAOffset());
       case RISCV64ThreadContext.SP -> senderSP.addOffsetTo(senderDwarf.getCFAOffset());
       default -> throw new DebuggerException("Unsupported CFA register: " + senderCFAReg);
     };
   }

   private JavaThread getJavaThreadFromThreadProxy(ThreadProxy tp) {
     Threads threads = VM.getVM().getThreads();
     for (int i = 0; i < threads.getNumberOfThreads(); i++) {
       var jthread = threads.getJavaThreadAt(i);
       if (tp.equals(jthread.getThreadProxy())) {
         return jthread;
       }
     }
     throw new DebuggerException("JavaThread not found");
   }

   @Override
   public CFrame sender(ThreadProxy thread, Address senderSP, Address senderFP, Address senderPC) {
      if (linuxDbg().isSignalTrampoline(pc())) {
        // SP points signal context
        //   https://github.com/torvalds/linux/blob/master/arch/riscv/kernel/signal.c
        return getFrameFromReg(linuxDbg(), r -> LinuxRISCV64ThreadContext.getRegFromSignalTrampoline(sp(), r.intValue()));
      }

      if (hasNativeLibrary() && dwarf() == null) {
        // Cannot find a sender frame if DWARF is missing even though PC in native library.
        return null;
      }

      if (senderPC == null) {
        // Use getSenderPC() if current frame is Java because we cannot rely on ra in this case.
        senderPC = dwarf() == null ? getSenderPC(null) : ra;
        if (senderPC == null) {
          return null;
        }
      }

      senderFP = getSenderFP(senderFP);

      if (senderSP == null) {
        CodeCache cc = VM.getVM().getCodeCache();
        CodeBlob currentBlob = cc.findBlobUnsafe(pc());

        // This case is different from HotSpot. See JDK-8371194 and JDK-8382548 for details.
        if (currentBlob == null) { // current frame is native
          senderSP = getSenderSP(null);
        } else { // current frame is Java
          if (currentBlob.isContinuationStub()) {
            var jthread = getJavaThreadFromThreadProxy(thread);
            var contEntry = Continuation.getContinuationEntryForSP(jthread, sp());
            senderSP = contEntry.getEntrySP();
            senderFP = contEntry.getEntryFP();
            senderPC = contEntry.getEntryPC();
          } else if (currentBlob.getFrameSize() == 0) {
            senderSP = fp().addOffsetTo(2 * VM.getVM().getAddressSize());
          } else {
            // Calculate sender SP and FP without FP
            // because we cannot believe FP if PreserveFramePointer is disabled.
            senderSP = sp().addOffsetTo(currentBlob.getFrameSize());
            senderFP = senderSP.getAddressAt(-2 * VM.getVM().getAddressSize());
            senderPC = senderSP.getAddressAt(- VM.getVM().getAddressSize());
          }
        }
      }
      if (senderSP == null) {
        return null;
      }

      DwarfParser senderDwarf = null;
      boolean fallback = false;
      try {
        senderDwarf = createDwarfParser(linuxDbg(), senderPC);
      } catch (DebuggerException _) {
        // Try again with PC-1 in case PC is just outside function bounds,
        // due to function ending with a `call` instruction.
        try {
          senderDwarf = createDwarfParser(linuxDbg(), senderPC.addOffsetTo(-1));
          fallback = true;
        } catch (DebuggerException _) {
          if (linuxDbg().isSignalTrampoline(senderPC)) {
            // We can use the caller frame if it is a signal trampoline.
            // DWARF processing might fail because vDSO .eh_frame might not be available.
            return new LinuxRISCV64CFrame(linuxDbg(), senderSP, senderFP, null /* no CFA */, senderPC, null /* no DWARF */);
          }

          // Returns CFrame if the sender is native frame even though it does not have DWARF,
          // otherwise returns null because we cannot unwind anymore.
          return linuxDbg().findLibPtrByAddress(senderPC) != null
            ? new LinuxRISCV64CFrame(linuxDbg(), senderSP, senderFP, null /* no CFA */, senderPC, null /* no DWARF */)
            : null;
        }
      }

      try {
        Address senderCFA = getSenderCFA(senderDwarf, senderSP, senderFP);
        return isValidFrame(senderCFA, senderFP)
          ? new LinuxRISCV64CFrame(linuxDbg(), senderSP, senderFP, senderCFA, senderPC, senderDwarf, fallback)
          : null;
      } catch (DebuggerException e) {
        if (linuxDbg().isSignalTrampoline(senderPC)) {
          // We can use the caller frame if it is a signal trampoline.
          // getSenderCFA() might fail because DwarfParser cannot find out CFA register.
          return new LinuxRISCV64CFrame(linuxDbg(), senderSP, senderFP, null, senderPC, senderDwarf, fallback);
        }

        // Rethrow the original exception if getSenderCFA() failed
        // and the caller is not signal trampoline.
        throw e;
      }
   }

   @Override
   public Frame toFrame() {
      return new RISCV64Frame(sp(), fp(), pc());
   }

}
