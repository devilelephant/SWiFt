package com.clario.swift.action;

import com.amazonaws.services.simpleworkflow.model.Decision;
import com.clario.swift.TaskType;
import com.clario.swift.Workflow;
import com.clario.swift.event.EventState;

import java.util.ArrayList;
import java.util.List;

import static com.clario.swift.event.EventState.*;
import static java.lang.String.format;
import static java.util.Arrays.asList;

/**
 * @author George Coller
 */
public class MockAction extends Action<MockAction> {
    private String input;
    private Decision decision;
    private boolean nonFinalDecisionMade;

    private List<EventState> eventStates = new ArrayList<>(asList(NOT_STARTED, SUCCESS));

    public MockAction(String actionId) {
        super(actionId);
    }

    public MockAction withInput(String input) {
        this.input = input;
        return this;
    }

    public void setEventStates(EventState... eventStates) {
        this.eventStates = new ArrayList<>(asList(eventStates));
    }

    @Override public String getInput() {
        return input;
    }

    @Override public String getOutput() {
        assert input != null : getActionId() + ": assert input string is not null on getOutput()";
        if (input.isEmpty()) {
            return getActionId();
        } else {
            return input + "->" + getActionId();
        }
    }

    @Override public Action decide(List<Decision> decisions) {
        if (getState() == NOT_STARTED) {
            decision = createInitiateActivityDecision();
            decisions.add(decision);
            nonFinalDecisionMade = true;
        }
        if (getState() == ERROR) {
            decision = Workflow.createFailWorkflowExecutionDecision(getActionId(), "error", "");
            decisions.add(decision);
        }
        return this;
    }

    @Override public EventState getState() {
        assert !eventStates.isEmpty() : getActionId() + ": assert eventStates not empty";
        return eventStates.get(0);
    }

    @Override public TaskType getTaskType() {
        return TaskType.ACTIVITY;
    }

    @Override protected MockAction thisObject() {
        return this;
    }

    @Override public Decision createInitiateActivityDecision() {
        assert decision == null;
        return new Decision().withDecisionType(format("%s", getOutput()));
    }

    public Decision getDecision() {
        return decision;
    }

    public String getDecisionType() {
        return (decision == null) ? null : decision.getDecisionType();
    }

    public void nextState() {
        if (nonFinalDecisionMade) {
            nonFinalDecisionMade = false;
            eventStates.remove(0);
        }
    }
}
