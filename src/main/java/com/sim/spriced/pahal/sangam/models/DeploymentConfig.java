package com.sim.spriced.pahal.sangam.models;
import lombok.Data;

@Data
public class DeploymentConfig {
    private ServerDetails jump1;
    private ServerDetails jump2; // Optional
    private ServerDetails target;

    @Data
    public static class ServerDetails {
        private String host;
        private String user;
        private String keyPath;
        private String remotePath; // Only needed for target
        private String password;
    }
}