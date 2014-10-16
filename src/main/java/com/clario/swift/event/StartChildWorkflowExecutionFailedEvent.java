package com.clario.swift.event;

import com.amazonaws.services.simpleworkflow.model.*;

/**
 * @author George Coller
 */
public class StartChildWorkflowExecutionFailedEvent extends Event {

    protected StartChildWorkflowExecutionFailedEvent(HistoryEvent historyEvent) {
        super(historyEvent);
    }

    @Override public EventState getState() { return EventState.ERROR; }

    @Override public EventCategory getCategory() { return EventCategory.ACTION; }

    @Override public Long getInitialEventId() { return getInitiatedEventId(); }

    @Override public boolean isInitialAction() { return false; }

    @Override public String getActionId() { return null; }

    @Override public String getReason() {  return "StartChildWorkflowExecutionFailed"; }

    @Override public String getDetails() {  return getCause(); }

    public StartChildWorkflowExecutionFailedEventAttributes getAttributes() {return historyEvent.getStartChildWorkflowExecutionFailedEventAttributes();}

    public  String getWorkflowName() { return getAttributes().getWorkflowType().getName(); }

    public  String getWorkflowVersion() { return getAttributes().getWorkflowType().getVersion(); }

    public  String getCause() { return getAttributes().getCause(); }

    public  String getWorkflowId() { return getAttributes().getWorkflowId(); }

    public  Long getInitiatedEventId() { return getAttributes().getInitiatedEventId(); }

    public  Long getDecisionTaskCompletedEventId() { return getAttributes().getDecisionTaskCompletedEventId(); }

    public @Override  String getControl() { return getAttributes().getControl(); }

}