package com.quashbugs.magnus.repository;

import com.quashbugs.magnus.model.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
@Repository
public interface OrganisationRepository extends MongoRepository<Organisation, String> {
    Optional<Organisation> findByOwnerAndType(User owner, OrganisationType type);

    Optional<Organisation> findByOwnerAndVcsProviderAndType(User owner,String vcsProvider ,OrganisationType type);

    Optional<Organisation> findByNameAndVcsProvider(String name, String vcsProvider);

    @Query("{ 'vcsProvider': ?0, '_class': 'gitlab_org', 'groupId': ?1 }")
    Optional<GitlabOrganisation> findByVcsProviderAndGroupId(String vcsProvider, String groupId);

    @Query("{ 'name': ?0, 'vcsProvider': ?1, '_class': 'github_org' }")
    Optional<GithubOrganisation> findGithubOrganisationByNameAndVcsProvider(String name, String vcsProvider);

    Optional<BitbucketOrganisation> findByVcsProviderAndSlug(String vcsProvider, String slug);
}
