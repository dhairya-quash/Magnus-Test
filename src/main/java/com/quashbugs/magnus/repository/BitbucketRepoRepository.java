package com.quashbugs.magnus.repository;

import com.quashbugs.magnus.model.BitbucketRepo;
import com.quashbugs.magnus.model.Organisation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BitbucketRepoRepository extends MongoRepository<BitbucketRepo, String> {
    Optional<BitbucketRepo> findBySlug(String slug);
    List<BitbucketRepo> findByOrganisation(Organisation organisation);
}
