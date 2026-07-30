package com.sim.spriced.pahal.dvm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "python.agent")
public class PythonAgentConfig {
    private String basePath = "dvm/adk_final-initial_commit";
    private String venvPath = ".venv";
    private String script = "main.py";
    private String pythonCommand = "python3";
    private int timeoutSeconds = 300;

    public String getVenvPython() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return venvPath + "\\Scripts\\python.exe";
        }
        return venvPath + "/bin/python";
    }

    public String getGeneratedFilePath() {
        return System.getProperty("generated.file.path",
                System.getProperty("java.io.tmpdir") + "/dvm-output");
    }
}