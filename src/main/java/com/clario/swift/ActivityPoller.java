package com.clario.swift;

import com.amazonaws.services.simpleworkflow.model.*;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.clario.swift.SwiftUtil.join;
import static com.clario.swift.SwiftUtil.joinEntries;
import static java.lang.String.format;
import static java.lang.String.valueOf;

/**
 * Polls for activities on a given domain and task list and executes them.
 *
 * @author George Coller
 */
public class ActivityPoller extends BasePoller {
    public static final int MAX_REASON_DETAILS_LENGTH = 32768;
    private Map<String, ActivityInvoker> activityMap = new LinkedHashMap<>();
    private MapSerializer ioSerializer = new MapSerializer();

    public ActivityPoller(String id, String domain, String taskList) {
        super(id, domain, taskList);
    }

    /**
     * Register one or more objects having methods annotated with {@link ActivityMethod}.
     *
     * @param activities annotated objects
     */
    public void addActivities(Object... activities) {
        for (Object activity : activities) {
            for (Method m : activity.getClass().getDeclaredMethods()) {
                if (m != null && m.isAnnotationPresent(ActivityMethod.class)) {
                    ActivityMethod activityMethod = m.getAnnotation(ActivityMethod.class);
                    String key = BasePoller.makeKey(activityMethod.name(), activityMethod.version());
                    log.info("Register activity " + key);
                    activityMap.put(key, new ActivityInvoker(this, m, activity));
                }
            }
        }
    }

    @Override
    protected void poll() {
        PollForActivityTaskRequest request = new PollForActivityTaskRequest()
            .withDomain(domain)
            .withTaskList(new TaskList()
                .withName(taskList))
            .withIdentity(this.getId());
        com.amazonaws.services.simpleworkflow.model.ActivityTask task = swf.pollForActivityTask(request);
        if (task.getTaskToken() == null) {
            log.info("poll timeout");
            return;

        }

        try {
            String key = makeKey(task.getActivityType().getName(), task.getActivityType().getVersion());
            log.info(format("invoke '%s': %s", task.getActivityId(), key));
            if (activityMap.containsKey(key)) {
                Map<String, String> inputs = ioSerializer.unmarshal(task.getInput());
                Map<String, String> outputs = activityMap.get(key).invoke(task, inputs);
                String result = ioSerializer.marshal(outputs);

                if (log.isInfoEnabled()) {
                    String outputString = join(joinEntries(outputs, " -> "), ", ");
                    log.info(format("completed '%s': %s = '%s'", task.getActivityId(), key, outputString));
                }
                RespondActivityTaskCompletedRequest resp = new RespondActivityTaskCompletedRequest()
                    .withTaskToken(task.getTaskToken())
                    .withResult(result);
                swf.respondActivityTaskCompleted(resp);
            } else {
                log.error("failed not registered \'" + task.getActivityId() + "\'");
                respondActivityTaskFailed(task.getTaskToken(), "activity not registered " + valueOf(task) + " on " + getId(), null);
            }

        } catch (Exception e) {
            log.error("failed \'" + task.getActivityId() + "\'", e);
            respondActivityTaskFailed(task.getTaskToken(), e.getMessage(), BasePoller.printStackTrace(e));
        }

    }

    /**
     * Record a heartbeat on SWF.
     *
     * @param taskToken identifies the task recording the heartbeat
     * @param details information to be recorded
     */
    public void recordHeartbeat(String taskToken, String details) {
        try {
            RecordActivityTaskHeartbeatRequest request = new RecordActivityTaskHeartbeatRequest()
                .withTaskToken(taskToken)
                .withDetails(details);
            swf.recordActivityTaskHeartbeat(request);
        } catch (Throwable e) {
            log.warn("Failed to record heartbeat: " + taskToken + ", " + details, e);
        }

    }

    public void respondActivityTaskFailed(String taskToken, String reason, String details) {
        RespondActivityTaskFailedRequest failedRequest = new RespondActivityTaskFailedRequest()
            .withTaskToken(taskToken)
            .withReason(trimToMaxLength(reason))
            .withDetails(trimToMaxLength(details));
        log.warn("poll :" + valueOf(failedRequest));
        swf.respondActivityTaskFailed(failedRequest);
    }

    private static String trimToMaxLength(String str) {
        if (str != null && str.length() > MAX_REASON_DETAILS_LENGTH) {
            return str.substring(0, MAX_REASON_DETAILS_LENGTH - 1);
        } else {
            return str;
        }
    }

    public void setIoSerializer(MapSerializer ioSerializer) {
        this.ioSerializer = ioSerializer;
    }

    static class ActivityInvoker implements ActivityContext {
        private final ActivityPoller poller;
        private final Method method;
        private final Object instance;
        private Map<String, String> inputs = new LinkedHashMap<>();
        private Map<String, String> outputs = new LinkedHashMap<>();
        private ActivityTask task;

        ActivityInvoker(ActivityPoller poller, Method method, Object instance) {
            this.poller = poller;
            this.method = method;
            this.instance = instance;
        }

        Map<String, String> invoke(final ActivityTask task, Map<String, String> inputs) {
            try {
                this.task = task;
                this.inputs = inputs;
                outputs = new LinkedHashMap<>();
                method.invoke(instance, this);
                return outputs;
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to invoke with: " + task.getActivityId() + ": " + valueOf(inputs), e);
            }
        }

        @Override
        public String getId() {
            return task.getActivityId();
        }

        @Override
        public void recordHeartbeat(String details) {
            poller.recordHeartbeat(task.getTaskToken(), details);
        }

        @Override
        public Map<String, String> getInputs() {
            return inputs;
        }

        @Override
        public void setOutput(String value) { outputs.put(getId(), value); }
    }
}
