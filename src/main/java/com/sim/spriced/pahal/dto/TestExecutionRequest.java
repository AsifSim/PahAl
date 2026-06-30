package com.sim.spriced.pahal.dto;

import java.util.List;
import java.util.Map;

public record TestExecutionRequest(
        Map<String, Object> requestData,
        List<Object> responseList,
        String entity,
        String workflowId,
        List<Endpoint> endpoints,
        String communicationType,
        String version
) {}

record Endpoint(String functionName, String version) {}