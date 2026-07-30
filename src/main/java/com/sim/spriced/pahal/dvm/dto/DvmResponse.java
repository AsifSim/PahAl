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
public class DvmResponse {
    private String path;
    private Object dvm;

    @JsonProperty("dvm_path")
    private String dvmPath;
}

