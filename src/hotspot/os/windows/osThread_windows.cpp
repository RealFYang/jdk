/*
 * Copyright (c) 1998, 2025, Oracle and/or its affiliates. All rights reserved.
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

#include "runtime/osThread.hpp"

#include <Windows.h>

OSThread::OSThread()
  : _thread_id(0),
    _thread_handle(nullptr),
    _interrupt_event(nullptr) {}

OSThread::~OSThread() {
  if (_interrupt_event != nullptr) {
    CloseHandle(_interrupt_event);
  }
}

// We need to specialize this to interact with the _interrupt_event.

void OSThread::set_interrupted(bool z) {
  if (z) {
    SetEvent(_interrupt_event);
  }
  else {
    // We should only ever clear the interrupt if we are in fact interrupted,
    // and this can only be done by the current thread on itself.
    ResetEvent(_interrupt_event);
  }
}
