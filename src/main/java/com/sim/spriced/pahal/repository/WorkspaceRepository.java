package com.sim.spriced.pahal.repository;

import com.sim.spriced.pahal.model.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    Optional<Workspace> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    @Query("SELECT w FROM Workspace w LEFT JOIN FETCH w.files WHERE w.id = :id")
    Optional<Workspace> findByIdWithFiles(@Param("id") String id);

    @Query("SELECT w FROM Workspace w ORDER BY w.updatedAt DESC")
    List<Workspace> findAllOrderByUpdatedAt();

    long count();
}
