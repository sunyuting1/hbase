/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.procedure2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.shaded.protobuf.generated.ProcedureProtos.ProcedureState;

/**
 * Internal state of the ProcedureExecutor that describes the state of a "Root Procedure". A "Root
 * Procedure" is a Procedure without parent, each subprocedure will be added to the "Root Procedure"
 * stack (or rollback-stack). RootProcedureState is used and managed only by the ProcedureExecutor.
 *
 * <pre>
 *   Long rootProcId = getRootProcedureId(proc);
 *   rollbackStack.get(rootProcId).acquire(proc)
 *   rollbackStack.get(rootProcId).release(proc)
 *   ...
 * </pre>
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
class RootProcedureState<TEnvironment> {
  private static final Logger LOG = LoggerFactory.getLogger(RootProcedureState.class);

  private enum State {
    RUNNING, // The Procedure is running or ready to run
    FAILED, // The Procedure failed, waiting for the rollback executing
    ROLLINGBACK, // The Procedure failed and the execution was rolledback
  }

  private Set<Procedure<TEnvironment>> subprocs = null;
  private ArrayList<Procedure<TEnvironment>> subprocStack = null;
  private State state = State.RUNNING;
  private int running = 0;
  // for some procedures such as SCP and TRSP, there is no way to rollback, so we do not need to
  // maintain the rollback steps
  // TODO: the rollback logic is a bit complicated, so here we will only test whether the root
  // procedure supports rollback at the very beginning, actually, lots of procedure can only
  // rollback at the pre check step, after that there is no rollback too, we should try to support
  // this too.
  private boolean rollbackSupported;

  protected synchronized boolean isFailed() {
    switch (state) {
      case ROLLINGBACK:
      case FAILED:
        return true;
      default:
        break;
    }
    return false;
  }

  protected synchronized boolean isRollingback() {
    return state == State.ROLLINGBACK;
  }

  /**
   * Called by the ProcedureExecutor to mark rollback execution
   */
  protected synchronized boolean setRollback() {
    if (running == 0 && state == State.FAILED) {
      state = State.ROLLINGBACK;
      return true;
    }
    return false;
  }

  /**
   * Called by the ProcedureExecutor to mark rollback execution
   */
  protected synchronized void unsetRollback() {
    assert state == State.ROLLINGBACK;
    state = State.FAILED;
  }

  protected synchronized void setRollbackSupported(boolean rollbackSupported) {
    this.rollbackSupported = rollbackSupported;
  }

  protected synchronized boolean isRollbackSupported() {
    return rollbackSupported;
  }

  protected synchronized long[] getSubprocedureIds() {
    if (subprocs == null) {
      return null;
    }
    return subprocs.stream().mapToLong(Procedure::getProcId).toArray();
  }

  protected synchronized Collection<Procedure<TEnvironment>> getSubprocs() {
    return subprocs;
  }

  protected synchronized List<Procedure<TEnvironment>> getSubproceduresStack() {
    return subprocStack;
  }

  protected synchronized RemoteProcedureException getException() {
    if (subprocs != null) {
      for (Procedure<TEnvironment> proc : subprocs) {
        if (proc.hasException()) {
          return proc.getException();
        }
      }
    }
    return null;
  }

  /**
   * Called by the ProcedureExecutor to mark the procedure step as running.
   */
  protected synchronized boolean acquire() {
    if (state != State.RUNNING) {
      return false;
    }

    running++;
    return true;
  }

  /**
   * Called by the ProcedureExecutor to mark the procedure step as finished.
   */
  protected synchronized void release() {
    running--;
  }

  protected synchronized void abort() {
    if (state == State.RUNNING) {
      state = State.FAILED;
    }
  }

  /**
   * Called by the ProcedureExecutor after the procedure step is completed, to add the step to the
   * rollback list (or procedure stack).
   * <p>
   * Return whether we successfully added the rollback step. If the root procedure has already
   * crossed the PONR, we do not need to maintain the rollback step,
   */
  protected synchronized boolean addRollbackStep(Procedure<TEnvironment> proc) {
    if (proc.isFailed()) {
      state = State.FAILED;
    }
    if (!rollbackSupported) {
      // just record executed, skip adding rollback step
      proc.setExecuted();
      return false;
    }
    if (subprocStack == null) {
      subprocStack = new ArrayList<>();
    }
    proc.addStackIndex(subprocStack.size());
    LOG.trace("Add procedure {} as the {}th rollback step", proc, subprocStack.size());
    subprocStack.add(proc);
    return true;
  }

  protected synchronized void addSubProcedure(Procedure<TEnvironment> proc) {
    if (!proc.hasParent()) {
      return;
    }
    if (subprocs == null) {
      subprocs = new HashSet<>();
    }
    subprocs.add(proc);
  }

  /**
   * Called on store load by the ProcedureExecutor to load part of the stack. Each procedure has its
   * own stack-positions. Which means we have to write to the store only the Procedure we executed,
   * and nothing else. on load we recreate the full stack by aggregating each procedure
   * stack-positions.
   */
  protected synchronized void loadStack(Procedure<TEnvironment> proc) {
    addSubProcedure(proc);
    int[] stackIndexes = proc.getStackIndexes();
    if (stackIndexes != null) {
      if (subprocStack == null) {
        subprocStack = new ArrayList<>();
      }
      int diff = (1 + stackIndexes[stackIndexes.length - 1]) - subprocStack.size();
      if (diff > 0) {
        subprocStack.ensureCapacity(1 + stackIndexes[stackIndexes.length - 1]);
        while (diff-- > 0) {
          subprocStack.add(null);
        }
      }
      for (int i = 0; i < stackIndexes.length; ++i) {
        subprocStack.set(stackIndexes[i], proc);
      }
    }
    if (proc.getState() == ProcedureState.ROLLEDBACK) {
      state = State.ROLLINGBACK;
    } else if (proc.isFailed()) {
      state = State.FAILED;
    }
  }

  /**
   * Called on store load by the ProcedureExecutor to validate the procedure stack.
   */
  protected synchronized boolean isValid() {
    if (subprocStack != null) {
      for (Procedure<TEnvironment> proc : subprocStack) {
        if (proc == null) {
          return false;
        }
      }
    }
    return true;
  }
}
