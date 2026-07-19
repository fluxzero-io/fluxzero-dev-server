/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

record CompilePlan(String mode, List<String> goals, boolean appReload, String reason, Set<Path> fastSources) {

    static CompilePlan from(Path projectDirectory, MavenBuildIntrospection build,
                            boolean fastCompilerEnabled, boolean testApplication, Set<Path> changedFiles) {
        ChangeProfile profile = ChangeProfile.from(projectDirectory, changedFiles, testApplication);
        if (!profile.appAffecting()) {
            return new CompilePlan("skip", List.of(), false, "no app-affecting changes", Set.of());
        }
        if (profile.pomChanged()) {
            return full("pom.xml changed");
        }
        if (!build.runtimeMetadataAvailable()) {
            return full("runtime classpath metadata missing");
        }
        if (fastCompilerEnabled && !build.fastMetadataAvailable()) {
            return full("fast compiler metadata missing");
        }
        if (fastCompilerEnabled && !testApplication && profile.fastEligible()
            && !MavenReactor.load(projectDirectory).multiModule()) {
            return new CompilePlan("javac-fast", List.of(), true, "Java source changes", profile.fastSources());
        }
        return new CompilePlan("maven-compile", List.of(testApplication ? "test-compile" : "compile"), true,
                               profile.changeReason(testApplication), Set.of());
    }

    static CompilePlan fromGradle(Path projectDirectory, boolean testApplication, Set<Path> changedFiles) {
        ChangeProfile profile = ChangeProfile.from(projectDirectory, changedFiles, testApplication);
        if (!profile.appAffecting()) {
            return new CompilePlan("skip", List.of(), false, "no app-affecting changes", Set.of());
        }
        String reason = profile.buildChanged() ? "Gradle build changed" : profile.changeReason(testApplication);
        return new CompilePlan("gradle-compile", List.of("fluxzeroDevMetadata"), true, reason, Set.of());
    }

    private static CompilePlan full(String reason) {
        return new CompilePlan("maven-full",
                               List.of("test-compile", "dependency:build-classpath",
                                       "-DincludeScope=runtime"),
                               true, reason, Set.of());
    }

    boolean fastCompile() {
        return "javac-fast".equals(mode);
    }

    private record ChangeProfile(boolean pomChanged, boolean buildChanged, boolean appAffecting, boolean fastEligible,
                                 boolean mainChanged, boolean testChanged, Set<Path> fastSources) {

        static ChangeProfile from(Path projectDirectory, Set<Path> changedFiles, boolean testApplication) {
            boolean pomChanged = false;
            boolean buildChanged = false;
            boolean appAffecting = false;
            boolean fastEligible = !changedFiles.isEmpty();
            boolean mainChanged = false;
            boolean testChanged = false;
            java.util.LinkedHashSet<Path> fastSources = new java.util.LinkedHashSet<>();
            for (Path changedFile : changedFiles) {
                String path = relativePath(projectDirectory, changedFile);
                if ("pom.xml".equals(path) || path.endsWith("/pom.xml")) {
                    pomChanged = true;
                    buildChanged = true;
                    appAffecting = true;
                    fastEligible = false;
                } else if (gradleBuildPath(path)) {
                    buildChanged = true;
                    appAffecting = true;
                    fastEligible = false;
                } else if (mainJavaPath(path)) {
                    mainChanged = true;
                    Path source = absolute(projectDirectory, changedFile);
                    if (path.endsWith(".java")) {
                        appAffecting = true;
                        if (Files.isRegularFile(source)) {
                            fastSources.add(source);
                        } else {
                            fastEligible = false;
                        }
                    } else if (!Files.isDirectory(source)) {
                        appAffecting = true;
                        fastEligible = false;
                    }
                } else if (mainPath(path)) {
                    mainChanged = true;
                    appAffecting = true;
                    fastEligible = false;
                } else if (testApplication && testPath(path)) {
                    testChanged = true;
                    appAffecting = true;
                    fastEligible = false;
                }
            }
            if (fastSources.isEmpty()) {
                fastEligible = false;
            }
            return new ChangeProfile(pomChanged, buildChanged, appAffecting, fastEligible,
                                     mainChanged, testChanged, Set.copyOf(fastSources));
        }

        private String changeReason(boolean testApplication) {
            if (mainChanged && testChanged) {
                return "production and test application sources/resources changed";
            }
            if (mainChanged) {
                return "production sources/resources changed";
            }
            if (testChanged) {
                return "test application sources/resources changed";
            }
            return testApplication ? "test application sources/resources changed"
                    : "application sources/resources changed";
        }

        private static String relativePath(Path projectDirectory, Path changedFile) {
            Path absolute = absolute(projectDirectory, changedFile);
            try {
                return projectDirectory.toAbsolutePath().normalize().relativize(absolute).toString()
                        .replace('\\', '/');
            } catch (IllegalArgumentException e) {
                return absolute.toString().replace('\\', '/');
            }
        }

        private static Path absolute(Path projectDirectory, Path changedFile) {
            return changedFile.isAbsolute()
                    ? changedFile.toAbsolutePath().normalize()
                    : projectDirectory.resolve(changedFile).toAbsolutePath().normalize();
        }

        private static boolean mainJavaPath(String path) {
            return path.startsWith("src/main/java/") || path.contains("/src/main/java/");
        }

        private static boolean mainPath(String path) {
            return path.startsWith("src/main/") || path.contains("/src/main/");
        }

        private static boolean testPath(String path) {
            return path.startsWith("src/test/") || path.contains("/src/test/");
        }

        private static boolean gradleBuildPath(String path) {
            String name = Path.of(path).getFileName().toString();
            return name.equals("build.gradle") || name.equals("build.gradle.kts")
                   || name.equals("settings.gradle") || name.equals("settings.gradle.kts")
                   || name.equals("gradle.properties") || name.equals("libs.versions.toml");
        }
    }
}
