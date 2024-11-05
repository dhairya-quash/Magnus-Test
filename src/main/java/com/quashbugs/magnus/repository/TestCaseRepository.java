package com.quashbugs.magnus.repository;

import com.quashbugs.magnus.model.TestCase;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TestCaseRepository extends MongoRepository<TestCase, String> {

}
