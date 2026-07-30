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
public class AgentRequest {
    private String fsm;
    private String model;

    @JsonProperty("fsm_path")
    private String fsmPath;
}
