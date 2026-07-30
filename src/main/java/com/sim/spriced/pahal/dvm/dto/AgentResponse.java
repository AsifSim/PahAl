package com.sim.spriced.pahal.dvm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {
    private String pid;
    private Object response;

    @JsonProperty("response_payload")
    private ResponsePayload responsePayload;

    private String error;

    @JsonProperty("timings_ms")
    private Timings timingsMs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponsePayload {

        @JsonProperty("dvm")
        private Object dvm;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Timings {
        @JsonProperty("request_read")
        private long requestRead;

        @JsonProperty("python_startup")
        private long pythonStartup;

        private long agent;

        @JsonProperty("response_write")
        private long responseWrite;

        @JsonProperty("python_shutdown")
        private long pythonShutdown;

        private long total;
    }
}
