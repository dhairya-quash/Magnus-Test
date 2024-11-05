package com.quashbugs.magnus.repository;

import com.quashbugs.magnus.model.GitlabRepo;
import com.quashbugs.magnus.model.Organisation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GitlabRepoRepository extends MongoRepository<GitlabRepo, String> {
    Optional<GitlabRepo> findByProjectId(String projectId);
    List<GitlabRepo> findByOrganisation(Organisation organisation);
}
