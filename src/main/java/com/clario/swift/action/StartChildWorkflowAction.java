package com.clario.swift.action;

import com.amazonaws.services.simpleworkflow.model.*;
import com.clario.swift.TaskType;
import com.clario.swift.event.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.amazonaws.services.simpleworkflow.model.EventType.ChildWorkflowExecutionStarted;
import static com.clario.swift.SwiftUtil.*;
import static java.lang.String.format;

/**
 * Start a child workflow from the current workflow.
 * <p/>
 * Child workflows are an SWF concept that allows for composition of smaller workflows into a larger workflow.
 * The parent workflow pauses until the child workflow completes.
 *
 * @author George Coller
 * @see StartChildWorkflowExecutionDecisionAttributes
 */
public class StartChildWorkflowAction extends Action<StartChildWorkflowAction> {
    private String name;
    private String version;
    private String taskList;
    private String input;
    private String executionStartToCloseTimeout;
    private String taskStartToCloseTimeout;
    private String childPolicy = ChildPolicy.TERMINATE.name(); // sensible default
    private final List<String> tags = new ArrayList<String>();

    public StartChildWorkflowAction(String actionId) {
        super(actionId);
    }

    /**
     * Registered SWF Workflows are uniquely identified by the combination of name and version
     * so both are required.
     */
    public StartChildWorkflowAction withNameVersion(String name, String version) {
        this.name = assertSwfValue(assertMaxLength(name, MAX_NAME_LENGTH));
        this.version = assertSwfValue(assertMaxLength(version, MAX_VERSION_LENGTH));
        return this;
    }

    /**
     * Set the taskList for the child workflow.
     * If null, defaults to the calling workflow taskList.
     *
     * @see com.clario.swift.Workflow#getTaskList
     * @see StartChildWorkflowExecutionDecisionAttributes#taskList
     */
    public StartChildWorkflowAction withTaskList(String taskList) {
        this.taskList = assertSwfValue(assertMaxLength(taskList, MAX_NAME_LENGTH));
        return this;
    }

    /**
     * @see StartChildWorkflowExecutionDecisionAttributes#tagList
     */
    public StartChildWorkflowAction withTags(String... tags) {
        this.tags.clear();  // because this needs to be stateless 
        
        for (String tag : tags) {
            this.tags.add(assertMaxLength(tag, MAX_NAME_LENGTH));
        }
        if (this.tags.size() > MAX_NUMBER_TAGS) {
            throw new AssertionError(String.format("More than %d tags not allowed, received: %s", MAX_NUMBER_TAGS, join(this.tags, ",")));
        }
        return this;
    }

    @Override public TaskType getTaskType() { return TaskType.START_CHILD_WORKFLOW; }

    /**
     * @see StartChildWorkflowExecutionDecisionAttributes#input
     */
    public StartChildWorkflowAction withInput(String input) {
        this.input = assertMaxLength(input, MAX_INPUT_LENGTH);
        return this;
    }

    /**
     * Override the child workflow default start to close timeout.
     * Pass null unit or duration &lt;= 0 for a timeout of 365 days.
     *
     * @see StartWorkflowExecutionRequest#executionStartToCloseTimeout
     */
    public StartChildWorkflowAction withExecutionStartToCloseTimeout(TimeUnit unit, long duration) {
        this.executionStartToCloseTimeout = calcTimeoutOrYear(unit, duration);
        return this;
    }

    /**
     * Override the child workflow task default start to close timeout.
     * Pass null unit or duration &lt;= 0 for a timeout of NONE.
     *
     * @see StartWorkflowExecutionRequest#taskStartToCloseTimeout
     */
    public StartChildWorkflowAction withTaskStartToCloseTimeout(TimeUnit unit, long duration) {
        this.taskStartToCloseTimeout = calcTimeoutOrNone(unit, duration);
        return this;
    }

    /**
     * Override child workflow default child policy.
     * Set the child policy, defaults to {@link ChildPolicy#TERMINATE}.
     *
     * @see StartWorkflowExecutionRequest#childPolicy
     */
    public StartChildWorkflowAction withChildPolicy(ChildPolicy childPolicy) {
        this.childPolicy = childPolicy == null ? null : childPolicy.name();
        return this;
    }

    /**
     * @return decision of type {@link DecisionType#StartChildWorkflowExecution}
     */
    @Override
    public Decision createInitiateActivityDecision() {
        return new Decision()
                   .withDecisionType(DecisionType.StartChildWorkflowExecution)
                   .withStartChildWorkflowExecutionDecisionAttributes(new StartChildWorkflowExecutionDecisionAttributes()
                                                                          .withWorkflowId(createUniqueWorkflowId(name))
                                                                          .withControl(getActionId())
                                                                          .withWorkflowType(new WorkflowType().withName(name).withVersion((version)))
                                                                          .withTaskList(new TaskList().withName(taskList == null ? getWorkflow().getTaskList() : taskList))
                                                                          .withInput(trimToMaxLength(input, MAX_INPUT_LENGTH))
                                                                          .withExecutionStartToCloseTimeout(executionStartToCloseTimeout)
                                                                          .withTaskStartToCloseTimeout(taskStartToCloseTimeout)
                                                                          .withChildPolicy(childPolicy)
                                                                          .withTagList(tags)
                   );
    }

    /**
     * Get the workflow identifier created for this child workflow.
     * Clients should ensure that the child workflow has been started in a prior decision task before calling this method.
     *
     * @throws UnsupportedOperationException if child runId is not available
     * @see #getState
     */
    public String getChildWorkflowId() {
        Event event = getEvents().selectEventType(ChildWorkflowExecutionStarted).getFirst();
        if (event == null) {
            throw new IllegalStateException(format("RunId not available %s %s", this, getState()));
        } else {
            return event.getHistoryEvent().getChildWorkflowExecutionStartedEventAttributes().getWorkflowExecution().getWorkflowId();
        }
    }


    @Override
    protected StartChildWorkflowAction thisObject() { return this; }

    @Override
    public String toString() {
        return format("%s %s %s", getClass().getSimpleName(), getActionId(), makeKey(name, version));
    }
}
