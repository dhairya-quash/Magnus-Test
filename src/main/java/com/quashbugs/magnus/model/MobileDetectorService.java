package com.quashbugs.magnus.model;

import com.quashbugs.magnus.dto.MobileProjectInfoDTO;
import com.quashbugs.magnus.dto.PlatformScoreDTO;
import com.quashbugs.magnus.dto.RepoFile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Service
public class MobileDetectorService {

    private static final int MOBILE_THRESHOLD = 60;

    public MobileProjectInfoDTO analyzeMobileProject(List<RepoFile> files) {
        PlatformScoreDTO androidScore = calculateAndroidScore(files);
        PlatformScoreDTO iOSScore = calculateiOSScore(files);
        PlatformScoreDTO reactNativeScore = calculateReactNativeScore(files);
        PlatformScoreDTO flutterScore = calculateFlutterScore(files);

        PlatformScoreDTO maxScore = Stream.of(androidScore, iOSScore, reactNativeScore, flutterScore)
                .max(PlatformScoreDTO::compareTo)
                .orElse(new PlatformScoreDTO("Unknown", 0));

        boolean isMobile = maxScore.getScore() > MOBILE_THRESHOLD;
        String platform = isMobile ? maxScore.getPlatform() : "";

        return new MobileProjectInfoDTO(isMobile, platform);
    }

    private PlatformScoreDTO calculateAndroidScore(List<RepoFile> files) {
        int score = 0;
        score += scoreForFile(files, f -> f.getName().equals("AndroidManifest.xml"), 40);
        score += scoreForFile(files, f -> f.getPath().contains("/src/main/java/") || f.getPath().contains("/src/main/kotlin/"), 30);
        score += scoreForFile(files, f -> f.getName().equals("build.gradle") || f.getName().equals("build.gradle.kts"), 20);
        score += scoreForFile(files, f -> f.getName().equals("gradlew") || f.getName().equals("gradlew.bat"), 10);
        score += scoreForFile(files, f -> f.getPath().contains("/src/main/res/"), 20);
        score += scoreForFileCount(files, f -> f.getName().endsWith(".java") || f.getName().endsWith(".kt"), 15, 5);
        score += scoreForFile(files, f -> f.getPath().contains("/res/layout/") && f.getName().endsWith(".xml"), 15);
        score -= scoreForFile(files, f -> f.getName().equals("pubspec.yaml") || f.getPath().contains("/lib/main.dart"), 50);
        return new PlatformScoreDTO("Android", Math.max(score, 0));
    }

    private PlatformScoreDTO calculateiOSScore(List<RepoFile> files) {
        int score = 0;
        score += scoreForFile(files, f -> f.getName().endsWith(".xcodeproj") || f.getName().endsWith(".xcworkspace"), 30);
        score += scoreForFile(files, f -> f.getName().equals("Info.plist"), 20);
        score += scoreForFile(files, f -> f.getName().equals("AppDelegate.swift") || f.getName().equals("App.swift"), 20);
        score += scoreForFile(files, f -> f.getName().equals("Package.swift"), 20);
        score += scoreForFile(files, f -> f.getPath().contains("/Views/") || f.getPath().contains("/Models/") || f.getPath().contains("/ViewModels/"), 15);
        score += scoreForFileCount(files, f -> f.getName().endsWith(".swift"), 15, 5);
        score += scoreForFile(files, f -> f.getName().endsWith(".storyboard") || f.getName().endsWith(".xib"), 10);
        return new PlatformScoreDTO("iOS", score);
    }

    private PlatformScoreDTO calculateReactNativeScore(List<RepoFile> files) {
        int score = 0;
        score += scoreForFile(files, f -> f.getName().equals("package.json"), 20);
        score += scoreForFile(files, f -> f.getName().equals("App.js") || f.getName().equals("App.tsx"), 20);
        score += scoreForFile(files, f -> f.getPath().startsWith("android/") && files.stream().anyMatch(i -> i.getPath().startsWith("ios/")), 30);
        score += scoreForFileCount(files, f -> f.getName().endsWith(".js") || f.getName().endsWith(".tsx"), 15, 5);
        score += scoreForFile(files, f -> f.getPath().contains("/components/") || f.getPath().contains("/screens/"), 15);
        return new PlatformScoreDTO("React Native", score);
    }

    private PlatformScoreDTO calculateFlutterScore(List<RepoFile> files) {
        int score = 0;
        score += scoreForFile(files, f -> f.getName().equals("pubspec.yaml"), 40);
        score += scoreForFile(files, f -> f.getPath().contains("/lib/main.dart"), 40);
        score += scoreForFile(files, f -> f.getName().equals("flutter.gradle"), 30);
        score += scoreForFileCount(files, f -> f.getName().endsWith(".dart"), 20, 5);
        score += scoreForFile(files, f -> f.getPath().contains("/lib/widgets/") || f.getPath().contains("/lib/screens/"), 20);
        score += scoreForFile(files, f -> f.getPath().contains("/test/"), 10);
        score += scoreForFile(files, f -> f.getName().equals(".metadata"), 10);
        score += scoreForFile(files, f -> f.getName().equals("analysis_options.yaml"), 10);
        score += scoreForFile(files, f -> f.getPath().startsWith("android/") && files.stream().anyMatch(i -> i.getPath().startsWith("ios/")), 20);
        return new PlatformScoreDTO("Flutter", score);
    }

    private int scoreForFile(List<RepoFile> files, Predicate<RepoFile> predicate, int score) {
        return files.stream().anyMatch(predicate) ? score : 0;
    }

    private int scoreForFileCount(List<RepoFile> files, Predicate<RepoFile> predicate, int score, int threshold) {
        return files.stream().filter(predicate).count() > threshold ? score : 0;
    }
}