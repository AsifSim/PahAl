package com.sim.spriced.pahal.sangam.models;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList; 
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BuildResponse {
    private String buildId;
    private String status;
    private String projectType;
    private String repositoryUrl;
    private String branch;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String buildOutput;
    private String currentStage;

    public String getCurrentStage(){
        return currentStage;
    }

    public void setCurrentStage(String currentStage){
        this.currentStage = currentStage;
    }


     private List<String> buildLogs = new ArrayList<>();

    private String errorMessage;
    // Inside BuildResponse class
    private String cloneStatus = "PENDING";
    private String startupStatus = "N/A";
    
}