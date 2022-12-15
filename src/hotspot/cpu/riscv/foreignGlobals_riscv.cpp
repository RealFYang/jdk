/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2021, Huawei Technologies Co., Ltd. All rights reserved.
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

#include "precompiled.hpp"
#include "code/vmreg.hpp"
#include "code/vmreg.inline.hpp"
#include "prims/foreignGlobals.hpp"
#include "prims/foreignGlobals.inline.hpp"
#include "utilities/debug.hpp"
#include "runtime/jniHandles.hpp"
#include "runtime/jniHandles.inline.hpp"
#include "oops/typeArrayOop.inline.hpp"
#include "oops/oopCast.inline.hpp"
#include "prims/vmstorage.hpp"
#include "utilities/formatBuffer.hpp"

const ABIDescriptor ForeignGlobals::parse_abi_descriptor(jobject jabi) {
  ShouldNotCallThis();
  return {};
}

int RegSpiller::pd_reg_size(VMStorage reg) {
  Unimplemented();
  return -1;
}

void RegSpiller::pd_store_reg(MacroAssembler* masm, int offset, VMStorage reg) {
  Unimplemented();
}

void RegSpiller::pd_load_reg(MacroAssembler* masm, int offset, VMStorage reg) {
  Unimplemented();
}

static constexpr int FP_BIAS = 16; // skip old fp and lr

static void move_reg64(MacroAssembler* masm, int out_stk_bias,
                       Register from_reg, VMStorage to_reg) {
  int out_bias = 0;
  switch (to_reg.type()) {
    case StorageType::INTEGER:
      assert(to_reg.segment_mask() == REG64_MASK, "only moves to 64-bit integer registers supported");
      masm->mv(as_Register(to_reg), from_reg);
      break;
    case StorageType::STACK:
      out_bias = out_stk_bias;
    case StorageType::FRAME_DATA:
      Address dest(sp, to_reg.offset() + out_bias);
      switch (to_reg.stack_size()) {
        case 8: masm->sd(from_reg, dest); break;
        case 4: masm->sw(from_reg, dest); break;
        case 2: masm->sh(from_reg, dest); break;
        case 1: masm->sb(from_reg, dest); break;
        default: ShouldNotReachHere();
      }
      break;
    default: ShouldNotReachHere();
  }
}

static void move_stack(MacroAssembler* masm, Register tmp_reg, int in_stk_bias, int out_stk_bias,
                       VMStorage from_reg, VMStorage to_reg) {
  Address from_addr(fp, FP_BIAS + from_reg.offset() + in_stk_bias);
  int out_bias = 0;
  switch (to_reg.type()) {
    case StorageType::INTEGER:
      assert(to_reg.segment_mask() == REG64_MASK, "only moves to 64-bit integer registers supported");
      switch (from_reg.stack_size()) {
        case 8: masm->ld(as_Register(to_reg), from_addr); break;
        case 4: masm->lw(as_Register(to_reg), from_addr); break;
        case 2: masm->lh(as_Register(to_reg), from_addr); break;
        case 1: masm->lb(as_Register(to_reg), from_addr); break;
        default: ShouldNotReachHere();
      }
      break;
    case StorageType::FLOAT:
      assert(to_reg.segment_mask() == FP_MASK, "only moves to floating-point registers supported");
      switch (from_reg.stack_size()) {
        case 8: masm->fld(as_FloatRegister(to_reg), from_addr); break;
        case 4: masm->flw(as_FloatRegister(to_reg), from_addr); break;
        default: ShouldNotReachHere();
      }
      break;
    case StorageType::STACK:
      out_bias = out_stk_bias;
    case StorageType::FRAME_DATA:
      switch (from_reg.stack_size()) {
        case 8: masm->ld(tmp_reg, from_addr); break;
        case 4: masm->lw(tmp_reg, from_addr); break;
        case 2: masm->lh(tmp_reg, from_addr); break;
        case 1: masm->lb(tmp_reg, from_addr); break;
        default: ShouldNotReachHere();
      }
      Address dest(sp, to_reg.offset() + out_bias);
      switch (to_reg.stack_size()) {
        case 8: masm->sd(tmp_reg, dest); break;
        case 4: masm->sw(tmp_reg, dest); break;
        case 2: masm->sh(tmp_reg, dest); break;
        case 1: masm->sb(tmp_reg, dest); break;
        default: ShouldNotReachHere();
      }
      break;
    default: ShouldNotReachHere();
  }
}

static void move_fp(MacroAssembler* masm, int out_stk_bias,
                    FloatRegister from_reg, VMStorage to_reg) {
  switch (to_reg.type()) {
    case StorageType::INTEGER:
      assert(to_reg.segment_mask() == REG64_MASK, "only moves to 64-bit integer registers supported");
      switch (to_reg.stack_size()) {
        case 8: masm->fmv_x_d(as_Register(to_reg), from_reg); break;
        case 4: masm->fmv_x_w(as_Register(to_reg), from_reg); break;
        default: ShouldNotReachHere();
      }
      break;
    case StorageType::FLOAT:
      assert(to_reg.segment_mask() == FP_MASK, "only moves to floating-point registers supported");
      switch (to_reg.stack_size()) {
        case 8: masm->fmv_d(as_FloatRegister(to_reg), from_reg); break;
        case 4: masm->fmv_w(as_FloatRegister(to_reg), from_reg); break;
        default: ShouldNotReachHere();
      }
      break;
    case StorageType::STACK:
      Address dest(sp, to_reg.offset() + out_stk_bias);
      switch (to_reg.stack_size()) {
        case 8: masm->fsd(from_reg, dest); break;
        case 4: masm->fsw(from_reg, dest); break;
        default: ShouldNotReachHere();
      }
      break;
    default: ShouldNotReachHere();
  }
}

void ArgumentShuffle::pd_generate(MacroAssembler* masm, VMStorage tmp, int in_stk_bias, int out_stk_bias, const StubLocations& locs) const {
  Register tmp_reg = as_Register(tmp);
  for (int i = 0; i < _moves.length(); i++) {
    Move move = _moves.at(i);
    VMStorage from_reg = move.from;
    VMStorage to_reg   = move.to;

    // replace any placeholders
    if (from_reg.type() == StorageType::PLACEHOLDER) {
      from_reg = locs.get(from_reg);
    }
    if (to_reg.type() == StorageType::PLACEHOLDER) {
      to_reg = locs.get(to_reg);
    }

    switch (from_reg.type()) {
      case StorageType::INTEGER:
        assert(from_reg.segment_mask() == REG64_MASK, "only 64-bit integer register supported");
        move_reg64(masm, out_stk_bias, as_Register(from_reg), to_reg);
        break;
      case StorageType::FLOAT:
        assert(from_reg.segment_mask() == FP_MASK, "only floating-point register supported");
        move_fp(masm, out_stk_bias, as_FloatRegister(from_reg), to_reg);
        break;
      case StorageType::STACK:
        move_stack(masm, tmp_reg, in_stk_bias, out_stk_bias, from_reg, to_reg);
        break;
      default: ShouldNotReachHere();
    }
  }
}
