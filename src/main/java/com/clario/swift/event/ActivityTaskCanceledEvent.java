package com.clario.swift.event;

import com.amazonaws.services.simpleworkflow.model.*;

/**
 * @author George Coller
 */
public class ActivityTaskCanceledEvent extends Event {

    protected ActivityTaskCanceledEvent(HistoryEvent historyEvent) {
        super(historyEvent);
    }

    @Override public EventState getState() { return EventState.ERROR; }

    @Override public EventCategory getCategory() { return EventCategory.ACTION; }

    @Override public Long getInitialEventId() { return getScheduledEventId(); }

    @Override public boolean isInitialAction() { return false; }

    @Override public String getActionId() { return null; }

    @Override public String getReason() {  return null; } 

    public ActivityTaskCanceledEventAttributes getAttributes() {return historyEvent.getActivityTaskCanceledEventAttributes();}

    public String getDetails() { return getAttributes().getDetails(); }

    public Long getScheduledEventId() { return getAttributes().getScheduledEventId(); }

    public Long getStartedEventId() { return getAttributes().getStartedEventId(); }

    public Long getLatestCancelRequestedEventId() { return getAttributes().getLatestCancelRequestedEventId(); }

}
