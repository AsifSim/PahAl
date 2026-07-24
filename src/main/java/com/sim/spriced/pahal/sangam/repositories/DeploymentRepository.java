package com.sim.spriced.pahal.sangam.repositories;

import com.sim.spriced.pahal.sangam.models.DeploymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface DeploymentRepository extends JpaRepository<DeploymentTransaction, Long> {

    Optional<DeploymentTransaction> findTopByRepoNameAndBranchNameOrderByCreatedAtDesc(String repoName, String branchName);

}
