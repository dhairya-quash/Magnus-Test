package com.quashbugs.magnus.repository;

import com.quashbugs.magnus.model.Organisation;
import com.quashbugs.magnus.model.Repo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepoRepository extends MongoRepository<Repo, String> {
    Optional<List<Repo>> findAllByOrganisation(Organisation organisation);

    Optional<Repo> findByNameAndOrganisation(String name, Organisation organisation);
}
