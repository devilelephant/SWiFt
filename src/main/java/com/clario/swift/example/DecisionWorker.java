package com.clario.swift.example;

import com.clario.swift.DecisionPoller;
import com.clario.swift.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;


/**
 * @author George Coller
 */
public class DecisionWorker {
    public static final Logger log = LoggerFactory.getLogger(ActivityWorker.class);

    public static void main(String[] args) throws IOException {
        Config config = Config.getConfig();
        int threads = config.getPoolSize() / 2;

        for (int it = 1; it <= threads; it++) {
            String executionContext = System.getProperty("user.name");
            String pollerId = String.format("decision poller %d", it);

            DecisionPoller poller = new DecisionPoller(pollerId, "dev-clario", "default", executionContext);
            poller.setSwf(config.getAmazonSimpleWorkflow());
            poller.addWorkflow(new Workflow("Demo Workflow", "1.0", "default"));
            config.getService().submit(poller);
        }
    }
}
