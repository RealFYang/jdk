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

import sun.jvm.hotspot.debugger.*;
import sun.jvm.hotspot.debugger.riscv64.*;
import sun.jvm.hotspot.debugger.linux.*;
import sun.jvm.hotspot.runtime.*;

public class LinuxRISCV64ThreadContext extends RISCV64ThreadContext {
  private LinuxDebugger debugger;

  public LinuxRISCV64ThreadContext(LinuxDebugger debugger) {
    super();
    this.debugger = debugger;
  }

  public void setRegisterAsAddress(int index, Address value) {
    setRegister(index, debugger.getAddressValue(value));
  }

  public Address getRegisterAsAddress(int index) {
    return debugger.newAddress(getRegister(index));
  }

  public static Address getRegFromSignalTrampoline(Address sp, int index) {
    if (index < 0 || index >= RISCV64ThreadContext.NPRGREG) {
      throw new IllegalArgumentException("Unsupported register index: " + index);
    }

    // ucontext_t locates at 2nd element of rt_sigframe.
    // See definition of rt_sigframe in arch/riscv/kernel/signal.c
    // in Linux Kernel.
    Address addrUContext = sp.addOffsetTo(128); // sizeof(siginfo_t) = 128
    Address addrUCMContext = addrUContext.addOffsetTo(168); // offsetof(ucontext_t, uc_mcontext) = 168

    // struct sigcontext begins with struct user_regs_struct sc_regs, whose
    // layout matches the register indices of RISCV64ThreadContext.
    return addrUCMContext.getAddressAt(index * VM.getVM().getAddressSize());
  }
}
