package com.itways.assistant.journey.engine.model;

import com.itways.assistant.journey.model.RunStepLog;
import com.itways.assistant.journey.model.RunStatus;
import java.util.Date;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Durable run lifecycle event emitted by the engine. Hosts persist these
 * idempotently keyed by {@link #executionId}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JourneyRunLifecycleEvent {

    private String executionId;
    private String parentExecutionId;
    private String rootExecutionId;
    private Long journeyId;
    private String accountId;
    private String triggerIntent;
    private RunStatus status;
    private Date startedAt;
    private Date completedAt;
    private Long durationMs;
    private String userId;
    private String message;
    private List<RunStepLog> stepLogs;
    private Map<String, Object> variables;
    private Map<String, Object> stepResults;
}
