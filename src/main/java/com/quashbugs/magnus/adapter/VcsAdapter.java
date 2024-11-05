package com.quashbugs.magnus.adapter;

import com.quashbugs.magnus.dto.ScanningResponseDTO;
import com.quashbugs.magnus.model.Organisation;
import com.quashbugs.magnus.model.Repo;
import com.quashbugs.magnus.model.User;

import java.util.List;
import java.util.Map;

public interface VcsAdapter {
    Organisation createPersonalOrganisation(User savedUser);

    List<Organisation> fetchAndUpdateUserOrganizations(User user);

    void createNewMember(User user, String orgId, Map<String, Object> data);

    void updateExistingMember(User user, Map<String, Object> data);

    List<Repo> fetchAndSaveRepositories(User user, String orgId);

    List<String> fetchRepoBranches(User user,String orgId,String repoId);

    ScanningResponseDTO startScanning(String repoId, User user);
}
