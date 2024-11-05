package com.quashbugs.magnus.repository;

import com.quashbugs.magnus.model.Member;
import com.quashbugs.magnus.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MemberRepository extends MongoRepository<Member, String> {
    Optional<Member> findByUser(User user);
}