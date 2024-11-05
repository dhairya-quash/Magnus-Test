package com.quashbugs.magnus.dto;

public class PlatformScoreDTO implements Comparable<PlatformScoreDTO> {
    private final String platform;
    private final int score;

    public PlatformScoreDTO(String platform, int score) {
        this.platform = platform;
        this.score = score;
    }

    public String getPlatform() { return platform; }
    public int getScore() { return score; }

    @Override
    public int compareTo(PlatformScoreDTO other) {
        return Integer.compare(this.score, other.score);
    }
}
