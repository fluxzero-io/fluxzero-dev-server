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

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** A bounded, on-demand view of named tests; never included in the frequent console status broadcasts. */
final class TestCatalog {
    static final int PAGE_SIZE = 50;
    private static final Pattern SEGMENT = Pattern.compile("\\[([^:]+):([^\\]]*)]");
    record Case(String key, String project, String suite, String name, String state, String source) {}
    record Page(List<Case> items, int total, int offset, int pageSize, Map<String, Long> counts) {}

    static Case describe(String project, String scope, String id, String displayName, String state) {
        String decoded;
        try {decoded = java.net.URLDecoder.decode(id.replace("+", "%2B"), StandardCharsets.UTF_8);}
        catch (IllegalArgumentException e) {decoded = id;}
        String suite = "Tests", method = decoded, invocation = "";
        var segments = SEGMENT.matcher(decoded);
        while (segments.find()) {
            String kind = segments.group(1), value = segments.group(2);
            if (kind.equals("class")) suite = value;
            else if (kind.equals("nested-class")) suite += " · " + value;
            else if (kind.equals("method") || kind.equals("test-template") || kind.equals("test-factory")) method = value;
            else if (kind.equals("test-template-invocation") || kind.equals("dynamic-test")) invocation = value;
        }
        if (!decoded.startsWith("[") && decoded.contains("#")) {
            suite = decoded.substring(0, decoded.indexOf('#')); method = decoded.substring(decoded.indexOf('#') + 1);
        }
        String name = displayName == null || displayName.isBlank() ? method : displayName;
        String displaySuffix = "";
        if (name.startsWith(method + " · ")) {
            displaySuffix = name.substring(method.length());
            name = method;
        }
        if (name.equals(method) || name.matches("[A-Za-z_$][A-Za-z0-9_$]*\\(\\)")) {
            name = name.replaceFirst("\\(.*\\)$", "").replace('_', ' ');
            name = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z])([A-Z][a-z])", "$1 $2");
            name = Pattern.compile("\\b[A-Z][a-z]*\\b").matcher(name).replaceAll(match -> match.group().toLowerCase(Locale.ROOT));
            if (!name.isEmpty()) name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        name += displaySuffix;
        if (!invocation.isEmpty() && (displayName == null || displayName.equals(method))) name += " · " + invocation;
        String key;
        try {key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((project + "\0" + scope + "\0" + id).getBytes(StandardCharsets.UTF_8)));}
        catch (java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
        return new Case(key, clipped(project, 200), clipped(suite, 1000), clipped(name, 1000), state, clipped(decoded, 2000));
    }

    static Page page(List<Case> cases, String state, String query, String requestedOffset) {
        String filter = state == null ? "attention" : state;
        String search = clipped(query == null ? "" : query, 256).strip().toLowerCase(Locale.ROOT);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String kind : List.of("failed", "passed", "pending", "skipped")) counts.put(kind, 0L);
        cases.forEach(test -> counts.merge(test.state(), 1L, Long::sum));
        var matching = cases.stream().filter(test -> filter.equals("all") || filter.equals(test.state())
                        || filter.equals("attention") && (test.state().equals("failed") || test.state().equals("pending")))
                .filter(test -> search.isEmpty() || (test.name() + " " + test.suite() + " " + test.project() + " " + test.source())
                        .toLowerCase(Locale.ROOT).contains(search))
                .sorted(Comparator.comparingInt((Case test) -> switch(test.state()) {case "failed" -> 0; case "pending" -> 1; case "skipped" -> 2; default -> 3;})
                        .thenComparing(Case::suite).thenComparing(Case::name).thenComparing(Case::key)).toList();
        int offset = 0;
        try {offset = Math.max(0, Integer.parseInt(requestedOffset));} catch (NumberFormatException ignored) {}
        offset = Math.min(offset, Math.max(0, ((matching.size() - 1) / PAGE_SIZE) * PAGE_SIZE));
        return new Page(matching.subList(offset, Math.min(matching.size(), offset + PAGE_SIZE)), matching.size(), offset, PAGE_SIZE, counts);
    }
    private static String clipped(String value, int length) {return value.substring(0, Math.min(value.length(), length));}
}
