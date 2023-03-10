/*
 * The MIT License
 *
 * Copyright (c) 2013-2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.workflow.rest.external;

import com.cloudbees.workflow.flownode.FlowNodeUtil;
import com.cloudbees.workflow.rest.endpoints.RunAPI;
import com.cloudbees.workflow.rest.hal.Link;
import com.cloudbees.workflow.rest.hal.Links;
import com.fasterxml.jackson.annotation.JsonInclude;
import hudson.model.Result;
import jenkins.model.StandardArtifactManager;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graphanalysis.ForkScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.StageChunkFinder;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * External API response object for pipeline run
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public class RunExt {

    private RunLinks _links;
    private String id;
    private String name;
    private StatusExt status;
    private long startTimeMillis;
    private long endTimeMillis;
    private long durationMillis;
    private long queueDurationMillis;
    private long pauseDurationMillis;
    private List<StageNodeExt> stages;
    public RunLinks get_links() {
        return _links;
    }

    public void set_links(RunLinks _links) {
        this._links = _links;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public StatusExt getStatus() {
        return status;
    }

    public void setStatus(StatusExt status) {
        this.status = status;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public void setStartTimeMillis(long startTimeMillis) {
        this.startTimeMillis = startTimeMillis;
    }

    public long getEndTimeMillis() {
        return endTimeMillis;
    }

    public void setEndTimeMillis(long endTimeMillis) {
        this.endTimeMillis = endTimeMillis;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public long getQueueDurationMillis() {
        return queueDurationMillis;
    }

    public void setQueueDurationMillis(long queueDurationMillis) {
        this.queueDurationMillis = queueDurationMillis;
    }

    public long getPauseDurationMillis() {
        return pauseDurationMillis;
    }

    public void setPauseDurationMillis(long pauseDurationMillis) {
        this.pauseDurationMillis = pauseDurationMillis;
    }

    public List<StageNodeExt> getStages() {
        return stages;
    }

    public void setStages(List<StageNodeExt> stages) {
        this.stages = stages;
    }

    public static final class RunLinks extends Links {
        private Link changesets;
        private Link pendingInputActions;
        private Link nextPendingInputAction;
        private Link artifacts;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Link getChangesets() {
            return changesets;
        }

        public void setChangesets(Link changesets) {
            this.changesets = changesets;
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Link getPendingInputActions() {
            return pendingInputActions;
        }

        public void setPendingInputActions(Link pendingInputActions) {
            this.pendingInputActions = pendingInputActions;
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Link getNextPendingInputAction() {
            return nextPendingInputAction;
        }

        public void setNextPendingInputAction(Link nextPendingInputAction) {
            this.nextPendingInputAction = nextPendingInputAction;
        }

        public Link getArtifacts() {
            return artifacts;
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public void setArtifacts(Link artifacts) {
            this.artifacts = artifacts;
        }
    }

    /** Computes timings after the stages have been set up
     *  That means timing of the stage has been computed, and the stages are sorted
     *
     *  Deprecated but retained for external APIs consuming it, use {@link #createNew(WorkflowRun)} instead
     */
    @Deprecated
    public static RunExt computeTimings(RunExt runExt) {

        // Pause is the sum of stage pauses
        for (StageNodeExt stage : runExt.getStages()) {
            runExt.setDurationMillis(runExt.getPauseDurationMillis() + stage.getPauseDurationMillis());
        }

        if (!runExt.getStages().isEmpty()) {
            // We're done when the last stage is, and it is the result of the overall run
            FlowNodeExt lastStage = runExt.getLastStage();
            runExt.setEndTimeMillis(lastStage.getStartTimeMillis()+lastStage.getDurationMillis());
            lastStage.setStatus(runExt.getStatus());
        }

        long currentTimeMillis = System.currentTimeMillis();

        if (runExt.getStatus() == StatusExt.IN_PROGRESS || runExt.getStatus() == StatusExt.PAUSED_PENDING_INPUT) {
            runExt.setEndTimeMillis(currentTimeMillis);
        }

        // FIXME this seems really questionable, we can have *no* stages defined
        //  in which case QueueDuration should be the delay between the first FlowNode executed and the run start time
        if (runExt.getStages().isEmpty()) {
            runExt.setQueueDurationMillis(currentTimeMillis - runExt.getStartTimeMillis());
        } else {
            StageNodeExt firstExecutedStage = runExt.getFirstExecutedStage();
            if (firstExecutedStage != null) {
                runExt.setQueueDurationMillis(firstExecutedStage.getStartTimeMillis() - runExt.getStartTimeMillis());
            }
        }

        runExt.setDurationMillis(Math.max(0, runExt.getEndTimeMillis() - runExt.getStartTimeMillis() - runExt.getQueueDurationMillis()));
        return runExt;
    }

    /** Get basics set up: everything but status/timing/node walking for a run, no cache use */
    public static RunExt createMinimal(WorkflowRun run) {
        FlowExecution execution = run.getExecution();

        final RunExt runExt = new RunExt();
        runExt.set_links(new RunLinks());
        runExt.get_links().initSelf(RunAPI.getDescribeUrl(run));

        runExt.setId(run.getId());
        runExt.setName(run.getDisplayName());
        runExt.initStatus(run);
        runExt.setStartTimeMillis(run.getStartTimeInMillis());
        runExt.setStages(new ArrayList<StageNodeExt>());

        if (execution != null) {
            if (ChangeSetExt.hasChanges(run)) {
                runExt.get_links().setChangesets(Link.newLink(RunAPI.getChangeSetsUrl(run)));
            }
            if (isPendingInput(run)) {
                runExt.get_links().setPendingInputActions(Link.newLink(RunAPI.getPendingInputActionsUrl(run)));
                runExt.get_links().setNextPendingInputAction(Link.newLink(RunAPI.getNextPendingInputActionUrl(run)));
            }
            boolean mightHaveArtifacts;
            if (run.getArtifactManager() instanceof StandardArtifactManager) {
                mightHaveArtifacts = run.getHasArtifacts();
            } else {
                // May be expensive to check; assume that it might.
                mightHaveArtifacts = true;
            }
            if (mightHaveArtifacts) {
                runExt.get_links().setArtifacts(Link.newLink(RunAPI.getArtifactsUrl(run)));
            }
        }
        return runExt;
    }

    /** Creates a wrapper of this that hides the full stage nodes
     *  Use case: returning a minimal view of the run, while using a cached, fully-realized version
     */
    public RunExt createWrapper() {
        return new ChildHidingWrapper(this);
    }

    protected static class ChildHidingWrapper extends RunExt {
        protected RunExt myRun;
        protected List<StageNodeExt> wrappedStages;

        public RunLinks get_links() {return myRun.get_links();}
        public String getId() {return myRun.getId();}
        public String getName() {return myRun.getName();}
        public StatusExt getStatus() {return myRun.getStatus();}
        public long getStartTimeMillis() {return myRun.getStartTimeMillis();}
        public long getEndTimeMillis() {return myRun.getEndTimeMillis();}
        public long getDurationMillis() {return myRun.getDurationMillis();}
        public long getQueueDurationMillis() {return myRun.getQueueDurationMillis();}
        public long getPauseDurationMillis() {return myRun.getPauseDurationMillis();}
        public List<StageNodeExt> getStages() {return Collections.unmodifiableList(wrappedStages);}

        protected ChildHidingWrapper(RunExt run) {
            this.myRun = run;
            List<StageNodeExt> myWrappedStages = new ArrayList<StageNodeExt>();
            if (wrappedStages == null) {
                for(StageNodeExt stage : run.getStages()) {
                    myWrappedStages.add(stage.myWrapper());
                }
                this.wrappedStages = myWrappedStages;
            } else {
                wrappedStages = null;
            }
        }
    }

    public static RunExt create(WorkflowRun run) {
        FlowExecution execution = run.getExecution();

        // Use cache if eligible
        boolean isNotRunning = FlowNodeUtil.isNotPartOfRunningBuild(execution);
        if (isNotRunning) {
            RunExt myRun = FlowNodeUtil.getCachedRun(run);
            if (myRun != null) {
                return myRun;
            }
        }
        // Compute the entire flow
        RunExt myRun = createNew(run);
        if (isNotRunning) {
            FlowNodeUtil.cacheRun(run, myRun);
        }
        return myRun;
    }

    public static RunExt createNew(WorkflowRun run) {
        final RunExt runExt = createMinimal(run);
        FlowExecution execution = run.getExecution();
        if (execution != null) {
            ChunkVisitor visitor = new ChunkVisitor(run);
            ForkScanner.visitSimpleChunks(execution.getCurrentHeads(), visitor, new StageChunkFinder());
            final List<StageNodeExt> visitorStages = new ArrayList<>(visitor.stages);
            visitorStages.sort(RunExt::compareStageNodeExt);
            runExt.setStages(visitorStages);
        }

        long currentTimeMillis = System.currentTimeMillis();
        if (runExt.getStatus() == StatusExt.IN_PROGRESS || runExt.getStatus() == StatusExt.PAUSED_PENDING_INPUT) {
            runExt.setEndTimeMillis(currentTimeMillis);
        } else {
            runExt.setEndTimeMillis(run.getStartTimeInMillis()+run.getDuration());
        }

        // Run has a timestamp when enqueued, and start time when it gets and Executor and begins running
        if (run.hasntStartedYet()) {
            runExt.setQueueDurationMillis(currentTimeMillis - run.getTimeInMillis());  // Enqueued time, not runtime
        } else {
            runExt.setQueueDurationMillis(Math.max(0, run.getStartTimeInMillis()-run.getTimeInMillis()));
        }

        runExt.setDurationMillis(Math.max(0, runExt.getEndTimeMillis() - runExt.getStartTimeMillis()));

        return runExt;
    }

    private static int compareStageNodeExt(StageNodeExt s1, StageNodeExt s2) {
        // Natural and fastest compare used first
        int compare = Long.compare(s1.getStartTimeMillis(), s2.getStartTimeMillis());
        if (compare != 0) {
            return compare;
        }

        // Second best compare based on assumed numerical id following order of node creation in pipeline
        try {
            // StageNodeExt#getId() is assumed not null from other uses in code though not guarantied by design
            compare = Integer.compare(Integer.parseInt(s1.getId()), Integer.parseInt(s2.getId()));
            if (compare != 0) {
                return compare;
            }
        } catch (NumberFormatException e) {
            // Safety if StageNodeExt#getId() were to return non numerical string
        }

        // Slow and arbitrary compare based on name if nothing else
        // StageNodeExt#getName() can be null, null value is arbitrarily compared as first
        return Comparator.nullsFirst(String::compareTo).compare(s1.getName(), s2.getName());
    }

    public static boolean isPendingInput(WorkflowRun run) {
        InputAction inputAction = run.getAction(InputAction.class);
        if (inputAction != null) {
            try {
                List<InputStepExecution> executions = inputAction.getExecutions();
                if (executions != null && !executions.isEmpty()) {
                    return true;
                }
            } catch (InterruptedException | TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }

    private void initStatus(WorkflowRun run) {
        FlowExecution execution = run.getExecution();

        if (execution == null) {
            setStatus(StatusExt.NOT_EXECUTED);
        } else if (execution.getCauseOfFailure() != null) {
            setStatus(StatusExt.valueOf(execution.getCauseOfFailure()));
        } else if (execution.isComplete()) {
            Result r = run.getResult();
            if (null == r) {
                r = ((FlowEndNode) execution.getCurrentHeads().get(0)).getResult();
            }
            setStatus(StatusExt.valueOf(r));
        } else if (isPendingInput(run)) {
            setStatus(StatusExt.PAUSED_PENDING_INPUT);
        } else {
            setStatus(StatusExt.IN_PROGRESS);
        }
    }

    private StageNodeExt getFirstExecutedStage() {
        for (int i = 0; i < getStages().size(); i++) {
            StageNodeExt stage = getStages().get(i);
            if (stage.getStatus() != StatusExt.NOT_EXECUTED) {
                return stage;
            }
        }
        return null;
    }

    private FlowNodeExt getLastStage() {
        if (getStages().isEmpty()) {
            return null;
        } else {
            return getStages().get(getStages().size() - 1);
        }
    }
}
