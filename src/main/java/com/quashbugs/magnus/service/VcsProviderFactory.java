package com.quashbugs.magnus.service;

import com.quashbugs.magnus.adapter.VcsAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class VcsProviderFactory {

    @Autowired
    private GithubService githubService;

    @Autowired
    private GitlabService gitlabService;

    @Autowired
    private BitbucketService bitbucketService;

    public VcsAdapter getVcsProvider(String vcsProvider) {
        switch (vcsProvider.toLowerCase()) {
            case "github":
                return githubService;
            case "gitlab":
                return gitlabService;
            case "bitbucket":
                return bitbucketService;
            default:
                throw new IllegalArgumentException("Unsupported VCS provider: " + vcsProvider);
        }
    }
}