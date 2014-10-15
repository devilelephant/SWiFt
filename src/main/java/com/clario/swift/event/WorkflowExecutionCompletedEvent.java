package com.clario.swift.event;

import com.amazonaws.services.simpleworkflow.model.*;

/**
 * @author George Coller
 */
public class WorkflowExecutionCompletedEvent extends Event {

    protected WorkflowExecutionCompletedEvent(HistoryEvent historyEvent) {
        super(historyEvent);
    }

    @Override public EventState getState() { return EventState.INFO; }

    @Override public EventCategory getCategory() { return EventCategory.EXTERNAL; }

    @Override public Long getInitialEventId() { return getEventId(); }

    @Override public boolean isInitialAction() { return false; }

    @Override public String getActionId() { return null; }

    @Override public String getData1() { return null; }

    @Override public String getData2() { return null; }

    public WorkflowExecutionCompletedEventAttributes getAttributes() {return historyEvent.getWorkflowExecutionCompletedEventAttributes();}

    public String getResult() { return getAttributes().getResult(); }

    public Long getDecisionTaskCompletedEventId() { return getAttributes().getDecisionTaskCompletedEventId(); }

}
