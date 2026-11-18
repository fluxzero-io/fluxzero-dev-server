/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.devserver;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Explicit, process-local design fixture. No network, credentials, files or production adapters. */
final class CloudDemo {
    record Scenario(String id, String label, String group) {}
    record Organization(String id, String name, String plan, int applications, int limit, String role) {}
    record DnsRecord(String type, String name, String value) {}
    record Domain(String kind, String hostname, String status, List<DnsRecord> records) {}
    record Step(String name, String state) {}
    record Deployment(String id, String state, String commit, String artifact, String url, List<Step> steps) {}
    record Proposal(String id, long revision, long expiresAt, String organization, String team, String plan, String cluster,
                    String application, String environment, String repository, String branch, String commit,
                    String change, String planImpact) {}
    record Snapshot(int version, String mode, long revision, String scenario, List<Scenario> scenarios,
                    String account, String accountName, String availability, List<Organization> organizations,
                    String organizationId, String team, String plannedPlan, boolean canDeploy, boolean capacityAvailable, boolean githubConnected,
                    List<String> repositories, String repository, String branch, String integration,
                    List<String> availableClusters, String cluster, String application, String environment, Proposal proposal, Deployment deployment,
                    List<Domain> domains, Map<String, String> emailPreview, boolean authTested, String message) {}

    static final List<Scenario> SCENARIOS = List.of(
            new Scenario("local", "Local project, signed out", "Start"),
            new Scenario("first-organization", "First organization", "Start"),
            new Scenario("choose-organization", "Choose an organization", "Start"),
            new Scenario("github-required", "Connect GitHub", "Delivery"),
            new Scenario("ready", "Ready to review", "Delivery"),
            new Scenario("deploying", "Deployment in progress", "Delivery"),
            new Scenario("live", "Live application", "Delivery"),
            new Scenario("dns-pending", "Waiting for DNS", "Domains"),
            new Scenario("email-pending", "Email verification pending", "Domains"),
            new Scenario("capacity", "Plan capacity reached", "Recovery"),
            new Scenario("github-cancelled", "GitHub authorization cancelled", "Recovery"),
            new Scenario("build-failed", "Build failed", "Recovery"),
            new Scenario("rollout-failed", "Rollout failed", "Recovery"),
            new Scenario("offline", "Cloud unavailable", "Recovery"),
            new Scenario("forbidden", "Organization access removed", "Recovery"),
            new Scenario("read-only", "Viewer access", "Recovery"),
            new Scenario("expired", "Sign-in expired", "Recovery"),
            new Scenario("locked", "Secure storage locked", "Recovery"));
    private static final List<String> REPOSITORIES = List.of("acme/parcel-service", "acme/customer-portal");
    private static final List<String> STEPS = List.of("Build", "Publish artifact", "Accept deployment", "Roll out", "Check application");
    private final Supplier<Instant> clock;
    private long revision;
    private String scenario, account, organizationId, repository, branch, cluster, application, environment, fault, message;
    private List<Organization> organizations;
    private boolean githubConnected, authTested, planChanged;
    private Proposal proposal;
    private String acceptedProposal, operation, deployedApplication;
    private Instant started;
    private List<Domain> domains;
    private Map<String, String> emailPreview;
    private final Map<String, String> clusters = new java.util.HashMap<>();

    CloudDemo() {this(Instant::now);}
    CloudDemo(Supplier<Instant> clock) {this.clock = clock; reset("local");}
    static boolean enabled() {return "true".equals(System.getProperty("fluxzero.cloud.demo", System.getenv("FLUXZERO_CLOUD_DEMO")));}

    synchronized Snapshot call(JsonNode request) {
        if (request == null || !request.isObject() || request.size() > 8) throw invalid("Choose a supported demo action.");
        String action = text(request, "action", "status");
        Set<String> options = switch (action) {
            case "scenario" -> Set.of("action", "scenario");
            case "create-organization" -> Set.of("action", "name");
            case "select-organization" -> Set.of("action", "organizationId");
            case "select-source" -> Set.of("action", "repository", "branch");
            case "configure" -> Set.of("action", "application", "environment", "cluster");
            case "deploy" -> Set.of("action", "proposalId");
            case "configure-domains" -> Set.of("action", "applicationDomain", "authDomain", "emailDomain");
            case "check-domain" -> Set.of("action", "kind");
            default -> Set.of("action");
        };
        request.fieldNames().forEachRemaining(key -> {if (!options.contains(key)) throw invalid("Use only the displayed demo options.");});
        if (action.equals("status")) return snapshot();
        if (action.equals("scenario")) {reset(text(request, "scenario", "local")); return snapshot();}
        message = "";
        if (action.equals("recover")) {fault = ""; if (!account.equals("signed_out")) account = "signed_in"; revision++; return snapshot();}
        if (action.equals("sign-in")) {account = "signed_in"; fault = ""; revision++; return snapshot();}
        if (action.equals("sign-out")) {account = "signed_out"; proposal = null; revision++; return snapshot();}
        if (action.equals("unlock")) {account = "signed_in"; fault = ""; revision++; return snapshot();}
        require(account.equals("signed_in"), "Sign in to continue in Cloud.");
        require(!fault.equals("offline"), "Cloud is unavailable. Your local work is still available.");
        if (action.equals("select-organization")) {
            String id = text(request, "organizationId", "");
            require(organizations.stream().anyMatch(o -> o.id().equals(id)), "Choose an available organization.");
            organizationId = id; if (fault.equals("forbidden")) fault = ""; clearDestination();
        } else if (action.equals("create-organization")) {
            String name = text(request, "name", "My team").strip();
            require(!name.isBlank() && name.length() <= 64, "Enter an organization name of up to 64 characters.");
            organizationId = UUID.randomUUID().toString();
            var copy = new ArrayList<>(organizations);
            copy.add(new Organization(organizationId, name, "Starter", 0, 1, "Owner")); organizations = List.copyOf(copy);
            clearDestination();
        } else if (action.equals("join-organization")) {
            organizationId = "invited-acme";
            var copy = new ArrayList<>(organizations);
            if (copy.stream().noneMatch(o -> o.id().equals(organizationId)))
                copy.add(new Organization(organizationId, "Acme Studio", "Pro", 1, 3, "Developer"));
            organizations = List.copyOf(copy); clearDestination();
        } else {
            require(!fault.equals("forbidden"), "Your access to this organization has changed. Choose another organization.");
            require(organization() != null, "Choose an organization first.");
            require(!organization().role().equals("Viewer"), "You can view this organization. Ask an administrator for deployment access.");
            switch (action) {
                case "confirm-plan" -> {
                    String selected = organizationId;
                    organizations = organizations.stream().map(o -> o.id().equals(selected)
                            ? new Organization(o.id(), o.name(), "Pro", o.applications(), 5, o.role()) : o).toList();
                    planChanged = false; fault = ""; message = "Organization plan updated in Dashboard.";
                }
                case "choose-plan" -> {
                    planChanged = true;
                }
                case "connect-github" -> {githubConnected = true; message = "GitHub connected for this demo.";}
                case "cancel-github" -> {githubConnected = false; repository = ""; message = "GitHub connection cancelled. You can try again.";}
                case "select-source" -> {
                    require(githubConnected, "Connect GitHub first.");
                    String repo = text(request, "repository", "");
                    require(REPOSITORIES.contains(repo), "Choose a repository you can access.");
                    String branch = text(request, "branch", "main");
                    require(Set.of("main", "release").contains(branch), "Choose main or release.");
                    repository = repo; this.branch = branch; environment = "Production";
                }
                case "configure" -> {
                    require(!repository.isEmpty(), "Choose a repository first.");
                    String name = text(request, "application", "parcel-service");
                    require(name.matches("[a-z][a-z0-9-]{1,47}"), "Use a short app name with lowercase letters, numbers and hyphens.");
                    String destination = text(request, "cluster", "existing");
                    require(Set.of("existing", "new").contains(destination), "Choose an existing or new cluster.");
                    require(!destination.equals("existing") || !availableClusters().isEmpty(), "This organization has no existing cluster. Include a new cluster in your proposal.");
                    String env = text(request, "environment", "Production");
                    require(Set.of("Production", "Staging").contains(env), "Choose Production or Staging.");
                    application = name; environment = env;
                    cluster = destination.equals("existing") ? availableClusters().getFirst() : application + "-01";
                }
                case "review" -> {
                    require(!repository.isEmpty() && !cluster.isEmpty(), "Choose a source and deployment destination first.");
                    require(capacity(), "Your plan has no application slots left. Review your plan first.");
                    revision++;
                    proposal = new Proposal(UUID.randomUUID().toString(), revision, clock.get().plusSeconds(300).getEpochSecond(),
                            organization().name(), "default", planChanged ? "Pro" : organization().plan(), cluster, application, environment, repository, branch, "4e72ab1",
                            !availableClusters().contains(cluster) ? "Create cluster and application" : "Deploy application to existing cluster",
                            planChanged ? "Pro plan · review pricing in Dashboard" : "Within the current plan · no plan change");
                    return snapshot();
                }
                case "deploy" -> {
                    String id = text(request, "proposalId", "");
                    if (id.equals(acceptedProposal) && operation != null) return snapshot();
                    require(proposal != null && proposal.id().equals(id) && proposal.revision() == revision
                            && proposal.expiresAt() > clock.get().getEpochSecond(), "This proposal has changed or expired. Review it again.");
                    require(capacity(), "Available plan capacity has changed. Review your plan again.");
                    if (planChanged) {
                        String selected = organizationId;
                        organizations = organizations.stream().map(o -> o.id().equals(selected)
                                ? new Organization(o.id(), o.name(), "Pro", o.applications(), 5, o.role()) : o).toList();
                        planChanged = false;
                    }
                    if (!application.equals(deployedApplication)) {
                        String selected = organizationId;
                        organizations = organizations.stream().map(o -> o.id().equals(selected)
                                ? new Organization(o.id(), o.name(), o.plan(), o.applications() + 1, o.limit(), o.role()) : o).toList();
                        deployedApplication = application;
                    }
                    clusters.put(organizationId, cluster);
                    acceptedProposal = id; operation = UUID.randomUUID().toString(); started = clock.get(); fault = ""; proposal = null;
                }
                case "configure-domains" -> {
                    require(live(), "Wait for the application check to finish before connecting domains.");
                    String app = hostname(request, "applicationDomain", "app.acme.example");
                    String auth = hostname(request, "authDomain", "auth.acme.example");
                    String email = hostname(request, "emailDomain", "mail.acme.example");
                    require(!app.equals(auth) && !app.equals(email) && !auth.equals(email), "Use a separate hostname for application, sign-in and email.");
                    domains = List.of(domain("application", app, "pending"), domain("auth", auth, "pending"), domain("email", email, "pending"));
                    authTested = false; emailPreview = Map.of();
                }
                case "check-domain" -> {
                    String kind = text(request, "kind", "");
                    require(domains.stream().anyMatch(d -> d.kind().equals(kind) && !d.status().equals("not_configured")), "Set up this domain first.");
                    if (fault.equals("dns-pending") || fault.equals("email-pending") && kind.equals("email")) {
                        message = "DNS records are not visible yet. Add the records below, then check again.";
                    } else domains = domains.stream().map(d -> d.kind().equals(kind) ? domain(kind, d.hostname(), "ready") : d).toList();
                }
                case "test-auth" -> {
                    require(ready("auth"), "Verify the sign-in domain first."); authTested = true; message = "Demo sign-in completed successfully.";
                }
                case "test-email" -> {
                    require(ready("email"), "Verify the email domain first.");
                    emailPreview = Map.of("from", "hello@" + domains.stream().filter(d -> d.kind().equals("email")).findFirst().orElseThrow().hostname(),
                            "to", "demo@recipient.example", "subject", "Your application is ready", "status", "Captured locally · no email sent");
                }
                default -> throw invalid("Choose a supported demo action.");
            }
        }
        if (!Set.of("check-domain", "test-auth", "test-email", "deploy").contains(action)) proposal = null;
        revision++;
        return snapshot();
    }

    private void reset(String id) {
        require(SCENARIOS.stream().anyMatch(s -> s.id().equals(id)), "Choose an available scenario.");
        scenario = id; revision++; fault = id; message = "";
        clusters.clear(); clusters.put("acme", "shared-01");
        account = id.equals("local") ? "signed_out" : id.equals("expired") ? "expired" : id.equals("locked") ? "locked" : "signed_in";
        organizations = Set.of("local", "first-organization").contains(id) ? List.of() : List.of(
                new Organization("acme", "Acme Studio", id.equals("capacity") ? "Starter" : "Pro", 1, id.equals("capacity") ? 1 : 3, id.equals("read-only") ? "Viewer" : "Owner"),
                new Organization("personal", "Personal projects", "Starter", 0, 1, "Owner"));
        organizationId = organizations.isEmpty() || id.equals("choose-organization") ? "" : "acme";
        githubConnected = !Set.of("local", "first-organization", "choose-organization", "github-required", "github-cancelled").contains(id);
        repository = githubConnected ? REPOSITORIES.getFirst() : "";
        cluster = repository.isEmpty() ? "" : "shared-01";
        application = "parcel-service"; environment = "Production"; branch = "main";
        proposal = null; acceptedProposal = null; operation = null; started = null; deployedApplication = "";
        planChanged = false; authTested = false; emailPreview = Map.of();
        domains = List.of(domain("application", "app.acme.example", "not_configured"), domain("auth", "auth.acme.example", "not_configured"), domain("email", "mail.acme.example", "not_configured"));
        if (Set.of("deploying", "live", "dns-pending", "email-pending", "build-failed", "rollout-failed").contains(id)) {
            operation = UUID.randomUUID().toString(); deployedApplication = application; started = clock.get().minusSeconds(id.equals("deploying") ? 0 : 30);
        }
        if (Set.of("live", "dns-pending", "email-pending").contains(id)) {
            domains = List.of(domain("application", "app.acme.example", id.equals("dns-pending") ? "pending" : "ready"),
                    domain("auth", "auth.acme.example", id.equals("dns-pending") ? "pending" : "ready"),
                    domain("email", "mail.acme.example", id.equals("live") ? "ready" : "pending"));
            authTested = id.equals("live");
        }
        if (id.equals("github-cancelled")) message = "GitHub connection cancelled. You can try again.";
    }

    private void clearDestination() {
        githubConnected = false; repository = ""; cluster = ""; proposal = null; operation = null; started = null;
        authTested = false; emailPreview = Map.of(); planChanged = false; deployedApplication = "";
        domains = domains.stream().map(d -> domain(d.kind(), d.hostname(), "not_configured")).toList();
    }
    private List<String> availableClusters() {return clusters.containsKey(organizationId) ? List.of(clusters.get(organizationId)) : List.of();}
    private Organization organization() {return organizations.stream().filter(o -> o.id().equals(organizationId)).findFirst().orElse(null);}
    private boolean capacity() {return organization() != null && (planChanged || application.equals(deployedApplication) || !fault.equals("capacity") && organization().applications() < organization().limit());}
    private boolean ready(String kind) {return domains.stream().anyMatch(d -> d.kind().equals(kind) && d.status().equals("ready"));}
    private boolean live() {return operation != null && !Set.of("build-failed", "rollout-failed").contains(fault) && clock.get().getEpochSecond() - started.getEpochSecond() >= 10;}
    private Snapshot snapshot() {
        return new Snapshot(1, "demo", revision, scenario, SCENARIOS, account, "Alex Morgan", fault.equals("offline") ? "offline" : fault.equals("forbidden") ? "forbidden" : "available",
                organizations, organizationId, "default", planChanged ? "Pro" : "", organization() != null && !organization().role().equals("Viewer") && !fault.equals("forbidden"), capacity(), githubConnected,
                REPOSITORIES, repository, branch, repository.isEmpty() ? "" : "GitHub Actions · existing Integration", availableClusters(), cluster, application, environment,
                proposal, deployment(), domains, emailPreview, authTested, message);
    }
    private Deployment deployment() {
        if (operation == null) return null;
        boolean failed = Set.of("build-failed", "rollout-failed").contains(fault);
        int index = failed ? fault.equals("build-failed") ? 0 : 3 : (int)Math.min(5, Math.max(0, (clock.get().getEpochSecond() - started.getEpochSecond()) / 2));
        var steps = new ArrayList<Step>();
        for (int i = 0; i < STEPS.size(); i++) steps.add(new Step(STEPS.get(i), i < index ? "done" : i == index ? failed ? "failed" : "running" : "pending"));
        return new Deployment(operation, failed ? "failed" : index == 5 ? "healthy" : "running", "4e72ab1", "sha256:" + "4e72ab10".repeat(8),
                "https://" + application + ".apps.fluxzero.example", List.copyOf(steps));
    }
    private static Domain domain(String kind, String hostname, String status) {
        List<DnsRecord> records = kind.equals("email") ? List.of(
                new DnsRecord("TXT", hostname, "v=spf1 include:mail.fluxzero.example ~all"),
                new DnsRecord("CNAME", "fz1._domainkey." + hostname, "dkim.fluxzero.example"),
                new DnsRecord("TXT", "_dmarc." + hostname, "v=DMARC1; p=none"))
                : List.of(new DnsRecord("CNAME", hostname, kind.equals("auth") ? "login.fluxzero.example" : "ingress.fluxzero.example"),
                new DnsRecord("TXT", "_fluxzero." + hostname, "fluxzero-verify=demo-only"));
        return new Domain(kind, hostname, status, records);
    }
    private static String hostname(JsonNode request, String key, String fallback) {
        String value = text(request, key, fallback).strip().toLowerCase(java.util.Locale.ROOT);
        require(value.length() <= 200 && value.matches("(?=.{3,200}$)([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z][a-z0-9-]*[a-z0-9]"), "Enter a hostname without a protocol or path.");
        return value;
    }
    private static String text(JsonNode request, String key, String fallback) {
        if (!request.has(key)) return fallback;
        require(request.get(key).isTextual() && request.get(key).textValue().length() <= 256, "Use a short text value for " + key + ".");
        return request.get(key).textValue();
    }
    private static void require(boolean condition, String message) {if (!condition) throw invalid(message);}
    private static IllegalArgumentException invalid(String message) {return new IllegalArgumentException(message);}
}
