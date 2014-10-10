package com.clario.swift.action;

import com.amazonaws.services.simpleworkflow.model.CancelTimerDecisionAttributes;
import com.amazonaws.services.simpleworkflow.model.Decision;
import com.amazonaws.services.simpleworkflow.model.DecisionType;
import com.clario.swift.DecisionPoller;
import com.clario.swift.Event;
import com.clario.swift.EventList;
import com.clario.swift.Workflow;
import com.clario.swift.retry.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.amazonaws.services.simpleworkflow.model.EventType.*;
import static com.clario.swift.Event.State.*;
import static com.clario.swift.EventList.byActionId;
import static com.clario.swift.EventList.byEventType;
import static com.clario.swift.SwiftUtil.*;
import static com.clario.swift.Workflow.createCompleteWorkflowExecutionDecision;
import static com.clario.swift.Workflow.createFailWorkflowExecutionDecision;
import static java.lang.String.format;

/**
 * Combines the concepts of SWF Activities, Signals, Child Workflows, and Timers and their current running state.
 * <p/>
 * Note: The name "Action" was chosen to avoid naming conflicts with the parallel SWF concept "Task".
 *
 * @author George Coller
 */
public abstract class Action<T extends Action> {

    private final Logger log;

    private final String actionId;

    private Workflow workflow;
    private RetryPolicy errorRetryPolicy;
    private RetryPolicy repeatActionRetryPolicy;
    private boolean failWorkflowOnError = true;
    private boolean completeWorkflowOnSuccess = false;
    private boolean cancelActiveRetryTimer = false;

    /**
     * Each action requires a workflow-unique identifier.
     *
     * @param actionId workflow-unique identifier.
     */
    public Action(String actionId) {
        this.actionId = assertSwfValue(assertMaxLength(actionId, MAX_ID_LENGTH));
        log = LoggerFactory.getLogger(format("%s '%s'", getClass().getSimpleName(), getActionId()));
    }

    /**
     * Subclass overrides to provide "this" which allows super class to do method chaining
     * without compiler warnings.  Generics, what can you do?
     */
    protected abstract T thisObject();

    /**
     * @return the workflow-unique identifier given at construction
     */
    public String getActionId() { return actionId; }

    /**
     * Called by {@link DecisionPoller} to set the current state for a workflow run.
     */
    public void setWorkflow(Workflow workflow) { this.workflow = workflow; }

    /**
     * @return current state of a workflow run.
     * @see #setWorkflow
     */
    public Workflow getWorkflow() { return workflow; }

    /**
     * By default {@link #decide} adds a @link DecisionType#FailWorkflowExecution} decision
     * if this action finishes in an {@link Event.State#ERROR} state.
     * <p/>
     * Calling this method deactivates that decision for use cases where a workflow can
     * continue even if this action fails.
     */
    public T withNoFailWorkflowOnError() {
        failWorkflowOnError = false;
        return thisObject();
    }

    /**
     * Calling this will activate a {@link DecisionType#CompleteWorkflowExecution} decision
     * if this action finishes in an {@link Event.State#SUCCESS} state.
     * <p/>
     * By default this behavior is deactivated but is useful to create final actions in a workflow.
     *
     * @see #completeWorkflowOnSuccess
     */
    public T withCompleteWorkflowOnSuccess() {
        completeWorkflowOnSuccess = true;
        return thisObject();
    }

    /**
     * Sets this instance to be retried if the action errors using the given {@link RetryPolicy}.
     * <p/>
     * NOTE: unsupported on {@link TimerAction}.
     */
    public T withOnErrorRetryPolicy(RetryPolicy retryPolicy) {
        this.errorRetryPolicy = retryPolicy;
        if (retryPolicy != null) {
            this.errorRetryPolicy.validate();
        }
        return thisObject();
    }

    /**
     * Sets this instance to be rescheduled after each successful completion after a delay determined by the given {@link RetryPolicy}.
     * <p/>
     * NOTE: unsupported on {@link TimerAction}.
     */
    public T withOnSuccessRetryPolicy(RetryPolicy retryPolicy) {
        this.repeatActionRetryPolicy = retryPolicy;
        if (retryPolicy != null) {
            this.repeatActionRetryPolicy.validate();
        }
        return thisObject();
    }

    /**
     * Add a {@link DecisionType#CancelTimer} decision to the next call to {@link #decide}, which
     * will cancel an active retry timer (if one is currently in progress) for this action.
     * <p/>
     * Useful as a way to cancel a long delay time and force the retry immediately.
     * One scenario could be a workflow with an activity that runs every hour but allows for
     * receiving an external signal that instead kicks off the activity immediately.
     */
    public void withCancelActiveRetryTimer() {
        cancelActiveRetryTimer = true;
    }

    /**
     * Create a {@link DecisionType#CancelTimer} decision that will cancel any active retry timer
     * for this action.
     *
     * @see #withCancelActiveRetryTimer()
     */
    public Decision createCancelRetryTimerDecision() {
        return new Decision()
            .withDecisionType(DecisionType.CancelTimer)
            .withCancelTimerDecisionAttributes(new CancelTimerDecisionAttributes()
                    .withTimerId(getActionId())
            );
    }

    /**
     * Return output of action.
     *
     * @return result of action, null if action produces no output
     * @throws IllegalStateException if activity did not complete successfully.
     */
    public String getOutput() {
        if (isSuccess()) {
            return getCurrentEvent().getData1();
        } else if (repeatActionRetryPolicy != null && getState() == RETRY) {
            List<Event> completed = getEvents().select(byEventType(ActivityTaskCompleted));
            if (completed.isEmpty()) {
                throw new IllegalStateException("ActivityTaskCompleted event prior to retryOnSuccess not available.  Probably need to adjust Workflow.isContinuePollingForHistoryEvents algorithm");
            } else {
                return completed.get(0).getData1();
            }
        } else {
            throw new IllegalStateException("method not available when action state is " + getState());
        }
    }

    /**
     * Make a decision based on the current {@link Event.State} of an action.
     * <p/>
     * Default implementation if {@link Event.State} is:
     * <ul>
     * <li>{@link Event.State#INITIAL}: add decision returned by {@link #createInitiateActivityDecision()}</li>
     * <li>{@link Event.State#RETRY}: retry has been activated, add decision returned by {@link #createInitiateActivityDecision()}</li>
     * <li>{@link Event.State#ACTIVE}: no decisions are added for in-progress actions</li>
     * <li>{@link Event.State#SUCCESS}: if {@link #withNoFailWorkflowOnError()} has previously been called
     * add decision returned by {@link Workflow#createCompleteWorkflowExecutionDecision}</li>
     * <li>{@link Event.State#ERROR}: add decision returned by {@link Workflow#createFailWorkflowExecutionDecision}
     * unless {@link #withNoFailWorkflowOnError} has previously been called on the activity</li>
     * </ul>
     *
     * @param decisions decide adds zero or more decisions to this list
     *
     * @see #withNoFailWorkflowOnError
     */
    public Action decide(List<Decision> decisions) {
        Event.State state = getState();
        Event currentEvent = getCurrentEvent();

        if (cancelActiveRetryTimer && TimerStarted == currentEvent.getType()) {
            decisions.add(createCancelRetryTimerDecision());
            state = RETRY;
        }
        cancelActiveRetryTimer = false;

        switch (state) {
            case INITIAL:
                decisions.add(createInitiateActivityDecision());
                break;
            case ACTIVE:
                break;
            case SUCCESS:
                if (repeatActionRetryPolicy != null) {
                    if (repeatActionRetryPolicy.testResultMatches(currentEvent.getData1())) {
                        log.info(format("%s no more repeats. matched output: %s", this, getOutput()));
                    } else {
                        Decision decision = repeatActionRetryPolicy.calcNextDecision(getActionId(), getEvents());
                        if (decision != null) {
                            decisions.add(decision);
                            log.info("success, start timer delay: {} ", decision);
                        } else {
                            log.info("success, no more attempts: output={}", currentEvent.getData1());
                        }
                    }
                } else if (completeWorkflowOnSuccess) {
                    decisions.add(createCompleteWorkflowExecutionDecision(getOutput()));
                    log.info("success, workflow complete: {}", getOutput());
                } else {
                    log.info("success: {}", getOutput());
                }
                break;
            case RETRY:
                log.info("retry, restart action");
                decisions.add(createInitiateActivityDecision());
                break;

            case ERROR:
                boolean checkFailWorkflowOnError = true;
                if (errorRetryPolicy != null) {
                    if (errorRetryPolicy.testResultMatches(currentEvent.getData1(), currentEvent.getData2())) {
                        log.info(format("%s no more attempts. matched error reason or details: %s %s", this, currentEvent.getData1(), currentEvent.getData2()));
                    } else {
                        Decision decision = errorRetryPolicy.calcNextDecision(getActionId(), getEvents());
                        if (decision != null) {
                            decisions.add(decision);
                            checkFailWorkflowOnError = false;
                            log.info("error, start timer delay: {} ", decision);
                        } else {
                            log.info("error, no more attempts: error={} detail={}", currentEvent.getData1(), currentEvent.getData2());
                        }
                    }
                }
                if (checkFailWorkflowOnError && failWorkflowOnError) {
                    decisions.add(createFailWorkflowExecutionDecision(toString(), currentEvent.getData1(), currentEvent.getData2()));
                }
                break;
            default:
                throw new IllegalStateException(format("%s unknown action state:%s", this, getState()));
        }
        return this;
    }

    /**
     * @return current state for this action.
     * @see Event.State for details on how state is calculated
     */
    public Event.State getState() {
        Event currentEvent = getCurrentEvent();
        if (currentEvent == null) {
            return INITIAL;
        } else if (TimerFired == currentEvent.getType() || TimerCanceled == currentEvent.getType()) {
            return RETRY;
        } else {
            return currentEvent.getActionState();
        }
    }

    /**
     * Return if action completed with state {@link Event.State#SUCCESS}.
     * Can be used in workflows to simply flow logic.  See Swift example workflows.
     */
    public boolean isSuccess() { return SUCCESS == getState(); }


    /**
     * @return true, if this instance is in it's initial state.
     */
    public boolean isInitial() { return INITIAL == getState(); }

    /**
     * Most recently polled {@link Event} for this action
     * or null if none exists (action is in an initial state).
     *
     * @return most recent history event polled for this action.
     */
    public Event getCurrentEvent() {
        return getEvents().getFirst();
    }

    /**
     * @return {@link EventList} of available history events related to this action.
     */
    public EventList getEvents() {
        assertWorkflowSet();
        return workflow.getEvents().select(byActionId(actionId));
    }

    /**
     * Subclass implements to create the specific {@link Decision} that initiates the action.
     */
    public abstract Decision createInitiateActivityDecision();

    Logger getLog() { return log; }

    /** Two actions are considered equal if their id is equal. */
    @Override
    public boolean equals(Object o) {
        return o == this || (o != null && o instanceof Action && actionId.equals(((Action) o).actionId));
    }

    @Override
    public int hashCode() {
        return actionId.hashCode();
    }

    @Override
    public String toString() {
        return format("%s %s", getClass().getSimpleName(), getActionId());
    }

    private void assertWorkflowSet() {
        if (workflow == null) {
            throw new IllegalStateException(format("%s has no associated workflow. Ensure all actions used by a workflow are added to the workflow.", toString()));
        }
    }
}
