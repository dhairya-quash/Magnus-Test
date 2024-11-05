package com.quashbugs.magnus.repository;

import com.quashbugs.magnus.model.PullRequest;
import com.quashbugs.magnus.model.Repo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PullRequestRepository extends MongoRepository<PullRequest, String> {

    Optional<PullRequest> findByPullRequestNumberAndRepo(String pullRequestNumber, Repo repo);

}
