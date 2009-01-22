package de.unisb.cs.st.javaslicer.dependenceAnalysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import de.hammacher.util.ArrayStack;
import de.hammacher.util.IntegerMap;
import de.unisb.cs.st.javaslicer.common.classRepresentation.Instruction;
import de.unisb.cs.st.javaslicer.common.classRepresentation.ReadMethod;
import de.unisb.cs.st.javaslicer.common.classRepresentation.Instruction.InstructionInstance;
import de.unisb.cs.st.javaslicer.common.classRepresentation.instructions.ArrayInstruction.ArrayInstrInstance;
import de.unisb.cs.st.javaslicer.common.classRepresentation.instructions.FieldInstruction.FieldInstrInstance;
import de.unisb.cs.st.javaslicer.controlflowanalysis.ControlFlowAnalyser;
import de.unisb.cs.st.javaslicer.dependenceAnalysis.DependencesVisitor.DataDependenceType;
import de.unisb.cs.st.javaslicer.instructionSimulation.Simulator;
import de.unisb.cs.st.javaslicer.traceResult.BackwardInstructionIterator;
import de.unisb.cs.st.javaslicer.traceResult.ThreadId;
import de.unisb.cs.st.javaslicer.traceResult.TraceResult;
import de.unisb.cs.st.javaslicer.variableUsages.VariableUsages;
import de.unisb.cs.st.javaslicer.variables.ArrayElement;
import de.unisb.cs.st.javaslicer.variables.LocalVariable;
import de.unisb.cs.st.javaslicer.variables.ObjectField;
import de.unisb.cs.st.javaslicer.variables.StackEntry;
import de.unisb.cs.st.javaslicer.variables.Variable;


/**
 * This class iterates (backwards) through the execution trace and visits
 * all dynamic data and control dependencies.
 *
 * @author Clemens Hammacher
 */
public class DependencesExtractor {

    private final TraceResult trace;
    private final Simulator simulator = new Simulator();
    private List<DependencesVisitor> dataDependencyVisitorsReadAfterWrite = null;
    private List<DependencesVisitor> dataDependencyVisitorsWriteAfterRead = null;
    private List<DependencesVisitor> controlDependencyVisitors = null;
    private List<DependencesVisitor> instructionVisitors = null;
    private List<DependencesVisitor> pendingDataDependencyVisitorsReadAfterWrite = null;
    private List<DependencesVisitor> pendingDataDependencyVisitorsWriteAfterRead = null;
    private List<DependencesVisitor> pendingControlDependencyVisitors = null;
    private List<DependencesVisitor> methodEntryLeaveVisitors = null;

    public DependencesExtractor(final TraceResult trace) {
        this.trace = trace;
    }

    /**
     * Registers a {@link DependencesVisitor} with this {@link DependencesExtractor}.
     * This method should only be called before {@link #processBackwardTrace(long)},
     * otherwise you might get {@link ConcurrentModificationException}s.
     *
     * @param visitor the {@link DependencesVisitor} to register
     * @param capabilities the capabilities of the visitor (determines which
     *                     methods are called on the visitor)
     * @return <code>true</code> if the visitor was registered with any capabilities
     */
    public boolean registerVisitor(final DependencesVisitor visitor, final VisitorCapabilities... capabilities) {
        boolean change = false;
        for (final VisitorCapabilities cap: capabilities) {
            switch (cap) {
            case DATA_DEPENDENCIES_ALL:
                if (this.dataDependencyVisitorsReadAfterWrite == null)
                    this.dataDependencyVisitorsReadAfterWrite = new ArrayList<DependencesVisitor>();
                change |= this.dataDependencyVisitorsReadAfterWrite.add(visitor);

                if (this.dataDependencyVisitorsWriteAfterRead == null)
                    this.dataDependencyVisitorsWriteAfterRead = new ArrayList<DependencesVisitor>();
                change |= this.dataDependencyVisitorsWriteAfterRead.add(visitor);
                break;
            case DATA_DEPENDENCIES_READ_AFTER_WRITE:
                if (this.dataDependencyVisitorsReadAfterWrite == null)
                    this.dataDependencyVisitorsReadAfterWrite = new ArrayList<DependencesVisitor>();
                change |= this.dataDependencyVisitorsReadAfterWrite.add(visitor);
                break;
            case DATA_DEPENDENCIES_WRITE_AFTER_READ:
                if (this.dataDependencyVisitorsWriteAfterRead == null)
                    this.dataDependencyVisitorsWriteAfterRead = new ArrayList<DependencesVisitor>();
                change |= this.dataDependencyVisitorsWriteAfterRead.add(visitor);
                break;
            case CONTROL_DEPENDENCIES:
                if (this.controlDependencyVisitors == null)
                    this.controlDependencyVisitors = new ArrayList<DependencesVisitor>();
                change |= this.controlDependencyVisitors.add(visitor);
                break;
            case INSTRUCTION_EXECUTIONS:
                if (this.instructionVisitors == null)
                    this.instructionVisitors = new ArrayList<DependencesVisitor>();
                change |= this.instructionVisitors.add(visitor);
                break;
            case PENDING_CONTROL_DEPENDENCIES:
                if (this.pendingControlDependencyVisitors == null)
                    this.pendingControlDependencyVisitors = new ArrayList<DependencesVisitor>();
                change |= this.pendingControlDependencyVisitors.add(visitor);
                break;
            case PENDING_DATA_DEPENDENCIES_ALL:
                if (this.pendingDataDependencyVisitorsReadAfterWrite == null)
                    this.pendingDataDependencyVisitorsReadAfterWrite = new ArrayList<DependencesVisitor>();
                change |= this.pendingDataDependencyVisitorsReadAfterWrite.add(visitor);

                if (this.pendingDataDependencyVisitorsWriteAfterRead == null)
                    this.pendingDataDependencyVisitorsWriteAfterRead = new ArrayList<DependencesVisitor>();
                change |= this.pendingDataDependencyVisitorsWriteAfterRead.add(visitor);
                break;
            case PENDING_DATA_DEPENDENCIES_READ_AFTER_WRITE:
                if (this.pendingDataDependencyVisitorsReadAfterWrite == null)
                    this.pendingDataDependencyVisitorsReadAfterWrite = new ArrayList<DependencesVisitor>();
                change |= this.pendingDataDependencyVisitorsReadAfterWrite.add(visitor);
                break;
            case PENDING_DATA_DEPENDENCIES_WRITE_AFTER_READ:
                if (this.pendingDataDependencyVisitorsWriteAfterRead == null)
                    this.pendingDataDependencyVisitorsWriteAfterRead = new ArrayList<DependencesVisitor>();
                change |= this.pendingDataDependencyVisitorsWriteAfterRead.add(visitor);
                break;
            case METHOD_ENTRY_LEAVE:
                if (this.methodEntryLeaveVisitors == null)
                    this.methodEntryLeaveVisitors = new ArrayList<DependencesVisitor>();
                change |= this.methodEntryLeaveVisitors.add(visitor);
                break;
            }
        }
        return change;
    }

    public boolean unregisterVisitor(final DependencesVisitor visitor) {
        boolean change = false;
        if (this.dataDependencyVisitorsReadAfterWrite.remove(visitor)) {
            change = true;
            if (this.dataDependencyVisitorsReadAfterWrite.isEmpty())
                this.dataDependencyVisitorsReadAfterWrite = null;
        }
        if (this.dataDependencyVisitorsWriteAfterRead.remove(visitor)) {
            change = true;
            if (this.dataDependencyVisitorsWriteAfterRead.isEmpty())
                this.dataDependencyVisitorsWriteAfterRead = null;
        }
        if (this.controlDependencyVisitors.remove(visitor)) {
            change = true;
            if (this.controlDependencyVisitors.isEmpty())
                this.controlDependencyVisitors = null;
        }
        if (this.instructionVisitors.remove(visitor)) {
            change = true;
            if (this.instructionVisitors.isEmpty())
                this.instructionVisitors = null;
        }
        if (this.pendingControlDependencyVisitors.remove(visitor)) {
            change = true;
            if (this.pendingControlDependencyVisitors.isEmpty())
                this.pendingControlDependencyVisitors = null;
        }
        if (this.pendingDataDependencyVisitorsReadAfterWrite.remove(visitor)) {
            change = true;
            if (this.pendingDataDependencyVisitorsReadAfterWrite.isEmpty())
                this.pendingDataDependencyVisitorsReadAfterWrite = null;
        }
        if (this.methodEntryLeaveVisitors.remove(visitor)) {
            change = true;
            if (this.methodEntryLeaveVisitors.isEmpty())
                this.methodEntryLeaveVisitors = null;
        }
        return change;
    }

    /**
     * Go backwards through the execution trace of the given threadId and extract
     * all dependencies. {@link DependencesVisitor}s should have been added before
     * by calling {@link #registerVisitor(DependencesVisitor, VisitorCapabilities...)}.
     *
     * If you know the exact {@link ThreadId} of the thread to process, you should
     * use {@link #processBackwardTrace(ThreadId)} instead, since a java thread id
     * does not have to be unique.
     *
     * @param javaThreadId identifies the thread whose trace should be analyzed
     */
    public void processBackwardTrace(final long javaThreadId) {
        final ThreadId id = this.trace.getThreadId(javaThreadId);
        if (id != null)
            processBackwardTrace(id);
    }

    /**
     * Go backwards through the execution trace of the given threadId and extract
     * all dependencies. {@link DependencesVisitor}s should have been added before
     * by calling {@link #registerVisitor(DependencesVisitor, VisitorCapabilities...)}.
     *
     * @param threadId identifies the thread whose trace should be analyzed
     */
    public void processBackwardTrace(final ThreadId threadId) {

        // quickfix until the NEW bytecode knows the object it is creating:
        // we first traverse the trace and store the first occurence of each object
        Map<Long, Long> firstUsage = new HashMap<Long, Long>();
        {
            BackwardInstructionIterator quickFixBackwardIterator =
                this.trace.getBackwardIterator(threadId, null);

            long stepNr = 0;
            while (quickFixBackwardIterator.hasNext()) {
                InstructionInstance inst = quickFixBackwardIterator.next();
                if (inst instanceof ArrayInstrInstance)
                    firstUsage.put(((ArrayInstrInstance)inst).getArrayId(), stepNr);
                else if (inst instanceof FieldInstrInstance)
                    firstUsage.put(((FieldInstrInstance)inst).getObjectId(), stepNr);
                ++stepNr;
            }

        }

        final BackwardInstructionIterator backwardInsnItr =
            this.trace.getBackwardIterator(threadId, null);

        final IntegerMap<Set<Instruction>> controlDependencies = new IntegerMap<Set<Instruction>>();

        final ArrayStack<ExecutionFrame> frames = new ArrayStack<ExecutionFrame>();
        ExecutionFrame currentFrame = null;

        for (ReadMethod method: backwardInsnItr.getInitialStackMethods()) {
            currentFrame = new ExecutionFrame();
            currentFrame.method = method;
            frames.push(currentFrame);
            if (this.methodEntryLeaveVisitors != null)
                for (DependencesVisitor vis: this.methodEntryLeaveVisitors)
                    vis.visitMethodLeave(method);
        }

        // the lastWriter is needed for WAR data dependencies
        final Map<Variable, InstructionInstance> lastWriter = new HashMap<Variable, InstructionInstance>();
        // lastReaders are needed for RAW data dependencies
        final Map<Variable, List<InstructionInstance>> lastReaders = new HashMap<Variable, List<InstructionInstance>>();

        // this set contains all objects that have been created during the backwards traversal
        // of the trace. so these objects cannot have any dependencies any more. from time to time,
        // corresponding variables are delete from lastWriter and lastReader.
        final Set<Long> createdObjects = new HashSet<Long>();

        // these variables control when lastReaders and lastWriter are cleaned up based on createdObjects
        int nextCleanupOfLastWriter = 1<<16;
        int nextCleanupOfLastReaders = 1<<16;

        long stepNr = 0;

        while (backwardInsnItr.hasNext()) {
            final InstructionInstance instance = backwardInsnItr.next();
            final Instruction instruction = instance.getInstruction();

            ExecutionFrame removedFrame = null;
            final int stackDepth = instance.getStackDepth();
            assert stackDepth > 0;

            if (frames.size() != stackDepth) {
                if (frames.size() > stackDepth) {
                    assert frames.size() == stackDepth+1;
                    removedFrame = frames.pop();
                    assert removedFrame.method != null;
                    if (this.methodEntryLeaveVisitors != null)
                        for (DependencesVisitor vis: this.methodEntryLeaveVisitors)
                            vis.visitMethodEntry(removedFrame.method);
                } else {
                    // in all steps, the stackDepth can change by at most 1
                    assert frames.size() == stackDepth-1;
                    final ExecutionFrame newFrame = new ExecutionFrame();
                    if (frames.size() > 0 && frames.peek().atCacheBlockStart != null)
                        newFrame.throwsException = true;
                    newFrame.method = instruction.getMethod();
                    frames.push(newFrame);
                    if (this.methodEntryLeaveVisitors != null)
                        for (DependencesVisitor vis: this.methodEntryLeaveVisitors)
                            vis.visitMethodLeave(newFrame.method);
                }
                currentFrame = frames.peek();
            }
            assert currentFrame != null;

            // it is possible that we see successive instructions of different methods,
            // e.g. when called from native code
            if (currentFrame.method != instruction.getMethod()) {
                if (currentFrame.method == null) {
                    currentFrame.method = instruction.getMethod();
                } else {
                    ReadMethod newMethod = instruction.getMethod();
                    if (this.methodEntryLeaveVisitors != null)
                        for (DependencesVisitor vis: this.methodEntryLeaveVisitors) {
                            vis.visitMethodEntry(currentFrame.method);
                            vis.visitMethodLeave(newMethod);
                        }
                    currentFrame = new ExecutionFrame();
                    currentFrame.method = newMethod;
                    frames.set(stackDepth-1, currentFrame);
                }
            }

            final VariableUsages dynInfo = this.simulator.simulateInstruction(instance, currentFrame,
                    removedFrame, frames);

            if (this.instructionVisitors != null)
                for (final DependencesVisitor vis: this.instructionVisitors)
                    vis.visitInstructionExecution(instance);

            /*
            // fill createdObjects
            {
                Collection<Long> tmp = dynInfo.getCreatedObjects();
                if (!tmp.isEmpty()) {
                    if (tmp.size() == 1)
                        createdObjects.add(tmp.iterator().next());
                    else
                        createdObjects.addAll(tmp);
                }
            }
            */

            // the computation of control dependencies only has to be performed
            // if there are any controlDependencyVisitors
            if (this.controlDependencyVisitors != null) {
                Set<Instruction> instrControlDependencies = controlDependencies.get(instruction.getIndex());
                if (instrControlDependencies == null) {
                    computeControlDependencies(instruction.getMethod(), controlDependencies);
                    instrControlDependencies = controlDependencies.get(instruction.getIndex());
                    assert instrControlDependencies != null;
                }
                // get all interesting instructions, that are dependent on the current one
                Set<InstructionInstance> dependantInterestingInstances = getInstanceIntersection(instrControlDependencies,
                        currentFrame.interestingInstances);
                if (currentFrame.throwsException) {
                    currentFrame.throwsException = false;
                    // in this case, we have an additional control dependency from the catching to
                    // the throwing instruction
                    for (int i = stackDepth-2; i >= 0; --i) {
                        final ExecutionFrame f = frames.get(i);
                        if (f.atCacheBlockStart != null) {
                            if (f.interestingInstances.contains(f.atCacheBlockStart)) {
                                if (dependantInterestingInstances.isEmpty())
                                    dependantInterestingInstances = Collections.singleton(f.atCacheBlockStart);
                                else
                                    dependantInterestingInstances.add(f.atCacheBlockStart);
                            }
                            break;
                        }
                    }
                }
                if (!dependantInterestingInstances.isEmpty()) {
                    for (final InstructionInstance depend: dependantInterestingInstances) {
                        for (final DependencesVisitor vis: this.controlDependencyVisitors) {
                            vis.visitControlDependence(depend, instance);
                        }
                    }
                    currentFrame.interestingInstances.removeAll(dependantInterestingInstances);
                }
                currentFrame.interestingInstances.add(instance);
                if (this.pendingControlDependencyVisitors != null)
                    for (DependencesVisitor vis: this.pendingControlDependencyVisitors)
                        vis.visitPendingControlDependence(instance);
            }
            if (this.pendingControlDependencyVisitors != null) {
                currentFrame.interestingInstances.add(instance);
                for (DependencesVisitor vis: this.pendingControlDependencyVisitors)
                    vis.visitPendingControlDependence(instance);
            }

            if (!dynInfo.getDefinedVariables().isEmpty()) {
                if (this.dataDependencyVisitorsReadAfterWrite != null
                        || this.dataDependencyVisitorsWriteAfterRead != null
                        || this.pendingDataDependencyVisitorsWriteAfterRead != null) {
                    for (final Variable definedVariable: dynInfo.getDefinedVariables()) {
                        if (this.pendingDataDependencyVisitorsWriteAfterRead != null && !(definedVariable instanceof StackEntry)) {
                            // for each defined variable, we have a pending WAR dependency
                            // if the lastWriter is not null, we first discard old pending dependencies
                            InstructionInstance varLastWriter = lastWriter.put(definedVariable, instance);
                            for (DependencesVisitor vis: this.pendingDataDependencyVisitorsWriteAfterRead) {
                                if (varLastWriter != null)
                                    vis.discardPendingDataDependence(varLastWriter, definedVariable, DataDependenceType.WRITE_AFTER_READ);
                                vis.visitPendingDataDependence(instance, definedVariable, DataDependenceType.WRITE_AFTER_READ);
                            }
                        // otherwise, if there are WAR visitors, we only update the lastWriter
                        } else if (this.dataDependencyVisitorsWriteAfterRead != null) {
                            lastWriter.put(definedVariable, instance);
                        }
                        // if we have RAW visitors, we need to analyse the lastReaders
                        if (this.dataDependencyVisitorsReadAfterWrite != null
                                || this.pendingDataDependencyVisitorsReadAfterWrite != null) {
                            final List<InstructionInstance> readers = lastReaders.remove(definedVariable);
                            if (readers != null)
                                for (final InstructionInstance reader: readers) {
                                    if (this.dataDependencyVisitorsReadAfterWrite != null)
                                        for (final DependencesVisitor vis: this.dataDependencyVisitorsReadAfterWrite)
                                            vis.visitDataDependence(reader, instance, definedVariable, DataDependenceType.READ_AFTER_WRITE);
                                    if (this.pendingDataDependencyVisitorsReadAfterWrite != null)
                                        for (DependencesVisitor vis: this.pendingDataDependencyVisitorsReadAfterWrite)
                                            vis.discardPendingDataDependence(reader, definedVariable, DataDependenceType.READ_AFTER_WRITE);
                                }
                        }
                    }
                    if (lastWriter.size() > nextCleanupOfLastWriter) {
                        cleanUpMaps(lastWriter, lastReaders, createdObjects, firstUsage,
                            stepNr, frames, false);
                        nextCleanupOfLastWriter = Math.max(1<<16, 2*lastWriter.size());
                        nextCleanupOfLastReaders = Math.max(1<<16, 2*lastReaders.size());
                    }
                }
            }

            if (!dynInfo.getUsedVariables().isEmpty()) {
                if (this.dataDependencyVisitorsWriteAfterRead != null ||
                        this.dataDependencyVisitorsReadAfterWrite != null) {
                    for (final Variable usedVariable: dynInfo.getUsedVariables()) {
                        // if we have WAR visitors, we inform them about a new dependency
                        if (this.dataDependencyVisitorsWriteAfterRead != null && !(usedVariable instanceof StackEntry)) {
                            final InstructionInstance lastWriterInst = lastWriter.get(usedVariable);

                            if (lastWriterInst != null) {
                                for (final DependencesVisitor vis: this.dataDependencyVisitorsWriteAfterRead)
                                    vis.visitDataDependence(lastWriterInst, instance, usedVariable, DataDependenceType.WRITE_AFTER_READ);
                            }
                        }

                        // for RAW visitors, update the lastReaders
                        if (this.dataDependencyVisitorsReadAfterWrite != null
                                || this.pendingDataDependencyVisitorsReadAfterWrite != null) {
                            List<InstructionInstance> readers = lastReaders.get(usedVariable);
                            if (readers == null) {
                                readers = new ArrayList<InstructionInstance>(4);
                                lastReaders.put(usedVariable, readers);
                                if (lastReaders.size() > nextCleanupOfLastReaders) {
                                    cleanUpMaps(lastWriter, lastReaders, createdObjects,
                                        firstUsage, stepNr, frames, false);
                                    nextCleanupOfLastWriter = Math.max(1<<16, 2*lastWriter.size());
                                    nextCleanupOfLastReaders = Math.max(1<<16, 2*lastReaders.size());
                                }
                            }
                            readers.add(instance);
                            // for each used variable, we have a pending RAW dependency
                            if (this.pendingDataDependencyVisitorsReadAfterWrite != null) {
                                for (DependencesVisitor vis: this.pendingDataDependencyVisitorsReadAfterWrite)
                                    vis.visitPendingDataDependence(instance, usedVariable, DataDependenceType.READ_AFTER_WRITE);
                            }
                        }
                    }
                }
            }

            if (dynInfo.isCatchBlock())
                currentFrame.atCacheBlockStart = instance;
            else if (currentFrame.atCacheBlockStart != null)
                currentFrame.atCacheBlockStart = null;

            ++stepNr;
        }
        cleanUpMaps(lastWriter, lastReaders, createdObjects, firstUsage, stepNr, frames, true);
    }

    private void cleanUpMaps(Map<Variable, InstructionInstance> lastWriter,
            Map<Variable, List<InstructionInstance>> lastReaders, Set<Long> createdObjects,
            Map<Long, Long> firstUsage, long stepNr,
            Collection<ExecutionFrame> activeFrames, boolean cleanCompletely) {
        Set<ExecutionFrame> activeFrameSet = new HashSet<ExecutionFrame>(activeFrames);

        Iterator<Entry<Variable, InstructionInstance>> lastWriterIt = lastWriter.entrySet().iterator();
        while (lastWriterIt.hasNext()) {
            Entry<Variable, InstructionInstance> e = lastWriterIt.next();
            Variable var = e.getKey();
            if (cleanCompletely
                    || (var instanceof ArrayElement && firstUsage.get(((ArrayElement)var).getArrayId()) < stepNr)
                    || (var instanceof ObjectField && firstUsage.get(((ObjectField)var).getObjectId()) < stepNr)
                    || (var instanceof StackEntry && !activeFrameSet.contains(((StackEntry)var).getFrame()))
                    || (var instanceof LocalVariable && !activeFrameSet.contains(((LocalVariable)var).getFrame()))) {
                if (this.pendingDataDependencyVisitorsWriteAfterRead != null)
                    for (DependencesVisitor vis: this.pendingDataDependencyVisitorsWriteAfterRead)
                        vis.discardPendingDataDependence(e.getValue(), var, DataDependenceType.WRITE_AFTER_READ);
                lastWriterIt.remove();
            }
        }

        Iterator<Entry<Variable, List<InstructionInstance>>> lastReadersIt = lastReaders.entrySet().iterator();
        while (lastReadersIt.hasNext()) {
            Entry<Variable, List<InstructionInstance>> e = lastReadersIt.next();
            Variable var = e.getKey();
            if (cleanCompletely
                    || (var instanceof ArrayElement && firstUsage.get(((ArrayElement)var).getArrayId()) < stepNr)
                    || (var instanceof ObjectField && firstUsage.get(((ObjectField)var).getObjectId()) < stepNr)
                    || (var instanceof StackEntry && !activeFrameSet.contains(((StackEntry)var).getFrame()))
                    || (var instanceof LocalVariable && !activeFrameSet.contains(((LocalVariable)var).getFrame()))) {
                if (this.pendingDataDependencyVisitorsReadAfterWrite != null)
                    for (InstructionInstance inst: e.getValue())
                        for (DependencesVisitor vis: this.pendingDataDependencyVisitorsReadAfterWrite)
                            vis.discardPendingDataDependence(inst, var, DataDependenceType.READ_AFTER_WRITE);
                lastReadersIt.remove();
            }
        }

        createdObjects.clear();
    }

    private Set<InstructionInstance> getInstanceIntersection(
            Set<Instruction> instructions,
            Set<InstructionInstance> instances) {
        if (instructions.isEmpty() || instances.isEmpty())
            return Collections.emptySet();

        // TODO make more efficient

        Set<InstructionInstance> intersectInstances = new HashSet<InstructionInstance>();
        for (InstructionInstance inst: instances) {
            if (instructions.contains(inst.getInstruction()))
                intersectInstances.add(inst);
        }

        return intersectInstances;
    }

    private void computeControlDependencies(final ReadMethod method, final IntegerMap<Set<Instruction>> controlDependencies) {
        final Map<Instruction, Set<Instruction>> deps = ControlFlowAnalyser.getInstance().getInvControlDependencies(method);
        for (final Entry<Instruction, Set<Instruction>> entry: deps.entrySet()) {
            final int index = entry.getKey().getIndex();
            assert !controlDependencies.containsKey(index);
            controlDependencies.put(index, entry.getValue());
        }
    }

}