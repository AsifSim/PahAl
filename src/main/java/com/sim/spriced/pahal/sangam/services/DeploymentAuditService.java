package com.sim.spriced.pahal.sangam.services;

import com.sim.spriced.pahal.sangam.models.DeploymentTransaction;
import com.sim.spriced.pahal.sangam.repositories.DeploymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DeploymentAuditService {

    private static final String CLASSNAME = "DeploymentAuditService";
    private static final Logger logger = LoggerFactory.getLogger(DeploymentAuditService.class);
    private static final String DEFAULT_REQUESTOR = "System";

    @Autowired
    private DeploymentRepository deploymentRepository;

    @Autowired
    private GoogleSheetsLoggingService googleSheetsService;

    // Fixed: Now matches the 9 arguments being passed from BuildService
    public synchronized void logDeployment(String buildId, String repo, String branch, String server, 
                          String user, String cloneStatus, String buildStatus, 
                          String deployStatus, String startupStatus) {
        
        logger.info("{} : Entering method logDeployment", CLASSNAME);
        logger.info("[AUDIT-START] Beginning audit logging for Build ID: {}", buildId);
        
        try {
            // FIX: Changed 'requestedBy' to 'user' to match the parameter name
            String finalUser = Optional.ofNullable(user).orElse(DEFAULT_REQUESTOR);

            // FIX: Passing 'deployStatus' (the variable name in signature) instead of 'deploymentStatus'
            processPostgresAudit(buildId, repo, branch, server, finalUser, cloneStatus, buildStatus, deployStatus, startupStatus);
            processGoogleSheetsSync(buildId, repo, branch, server, finalUser, cloneStatus, buildStatus, deployStatus, startupStatus);

        } catch (Exception e) {
            logger.error("Unexpected error in ClassName : {}, method logDeployment, error: {} ", CLASSNAME, e.getMessage(), e);
        }
        
        logger.info("[AUDIT-COMPLETE] Finalized audit logs for Build ID: {}", buildId);
        logger.info("{} : Exiting method logDeployment", CLASSNAME);
    }

    private void processPostgresAudit(String buildId, String repo, String branch, String server, 
                                      String user, String cloneStatus, String buildStatus, 
                                      String deployStatus, String startupStatus) {
        logger.info("{} : Entering method processPostgresAudit", CLASSNAME);
        
        // buildTransactionEntity remains untouched (using 7 params for the DB table)
        DeploymentTransaction transaction = buildTransactionEntity(buildId, repo, branch, server, user, buildStatus, deployStatus);
        
        logger.info("[AUDIT-DB] Persisting transaction to PostgreSQL...");
        deploymentRepository.save(transaction);
        
        logger.info("Transaction saved to DB. Build: {}, Deploy: {}, User: {}", buildStatus, deployStatus, user);
        logger.info("{} : Exiting method processPostgresAudit", CLASSNAME);
    }

    private DeploymentTransaction buildTransactionEntity(String buildId, String repo, String branch, String server, 
                                                           String user, String buildStatus, String deploymentStatus) {
        
        DeploymentTransaction transaction = new DeploymentTransaction();
        transaction.setBuildId(buildId);
        transaction.setRepoName(repo);
        transaction.setBranchName(branch);
        transaction.setServerName(server);
        transaction.setRequestedBy(user);
        transaction.setBuildStatus(buildStatus);
        transaction.setDeploymentStatus(deploymentStatus);
        
        return transaction;
    }

    // Fixed: Signature now accepts 9 parameters to match the call from logDeployment
    private void processGoogleSheetsSync(String buildId, String repo, String branch, String server, 
                                         String user, String cloneStatus, String buildStatus, 
                                         String deployStatus, String startupStatus) {
        
        logger.info("{} : Entering method processGoogleSheetsSync", CLASSNAME);
        logger.info("[AUDIT-GS] Initiating background sync to Google Sheets...");
        
        // FIX: Passing the new status variables to the logging service
        googleSheetsService.logToSheet(buildId, repo, branch, server, user, 
                                          cloneStatus, buildStatus, deployStatus, startupStatus);
        
        logger.info("{} : Exiting method processGoogleSheetsSync", CLASSNAME);
    }
}