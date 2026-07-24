package com.sim.spriced.pahal.repository;

import com.sim.spriced.pahal.model.Activity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, String> {

    List<Activity> findByWorkspaceIdOrderByTimestampDesc(String workspaceId, Pageable pageable);

    List<Activity> findAllByOrderByTimestampDesc(Pageable pageable);

    void deleteByWorkspaceId(String workspaceId);
}