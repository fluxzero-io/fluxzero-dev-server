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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CloudDemoTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-18T10:00:00Z"));
    private final CloudDemo demo = new CloudDemo(now::get);
    private CloudDemo.Snapshot call(String json) throws Exception {return demo.call(mapper.readTree(json));}

    @Test void completesTheLocalJourneyWithoutExternalProviders() throws Exception {
        assertEquals("signed_out", call("{}").account());
        call("{\"action\":\"sign-in\"}");
        call("{\"action\":\"create-organization\",\"name\":\"My studio\"}");
        call("{\"action\":\"connect-github\"}");
        call("{\"action\":\"select-source\",\"repository\":\"acme/parcel-service\",\"branch\":\"release\"}");
        assertThrows(IllegalArgumentException.class, () -> call("{\"action\":\"configure\",\"application\":\"parcels\",\"cluster\":\"existing\"}"));
        call("{\"action\":\"configure\",\"application\":\"parcels\",\"cluster\":\"new\"}");
        var proposal = call("{\"action\":\"review\"}").proposal();
        assertEquals("release", proposal.branch());
        assertEquals("My studio", proposal.organization());
        assertEquals("Starter", proposal.plan());
        assertEquals("default", proposal.team());
        assertEquals("parcels-01", proposal.cluster());
        assertEquals("Create cluster and application", proposal.change());
        var deployment = call("{\"action\":\"deploy\",\"proposalId\":\"" + proposal.id() + "\"}").deployment();
        assertEquals("running", deployment.state());
        assertEquals(proposal.cluster(), call("{}").cluster());
        assertEquals(1, call("{}").organizations().getFirst().applications());
        assertEquals(deployment.id(), call("{\"action\":\"deploy\",\"proposalId\":\"" + proposal.id() + "\"}").deployment().id());
        now.set(now.get().plusSeconds(12));
        assertEquals("healthy", call("{}").deployment().state());
        call("{\"action\":\"configure-domains\"}");
        for (String kind : new String[]{"application", "auth", "email"}) call("{\"action\":\"check-domain\",\"kind\":\"" + kind + "\"}");
        assertTrue(call("{\"action\":\"test-auth\"}").authTested());
        var email = call("{\"action\":\"test-email\"}").emailPreview();
        assertEquals("demo@recipient.example", email.get("to"));
        assertEquals("Captured locally · no email sent", email.get("status"));
    }

    @Test void preparesDeliveryWithoutProvisioningOrStartingADeployment() throws Exception {
        call("{\"action\":\"sign-in\"}");
        call("{\"action\":\"create-organization\",\"name\":\"Parcel Studio\"}");
        assertThrows(IllegalArgumentException.class, () -> call("{\"action\":\"configure-pipeline\"}"));
        call("{\"action\":\"configure-application\",\"application\":\"parcels\",\"size\":\"Starter\",\"cluster\":\"automatic\"}");
        call("{\"action\":\"connect-github\"}");
        call("{\"action\":\"select-source\",\"repository\":\"acme/parcel-service\",\"branch\":\"main\"}");
        var ready = call("{\"action\":\"configure-pipeline\"}");
        assertTrue(ready.setup().applicationConfigured());
        assertTrue(ready.setup().pipelineConfigured());
        assertEquals("not_checked", ready.setup().workflowStatus());
        assertEquals("parcels-01", ready.cluster());
        assertTrue(ready.availableClusters().isEmpty());
        assertEquals(0, ready.organizations().getFirst().applications());
        assertNull(ready.proposal());
        assertNull(ready.deployment());
        now.set(now.get().plusSeconds(3600));
        assertNull(call("{}").deployment());
        assertFalse(call("{\"action\":\"select-source\",\"repository\":\"acme/parcel-service\",\"branch\":\"release\"}").setup().pipelineConfigured());
    }

    @Test void doesNotTreatAnExistingReleaseAsANewApplicationsDeployment() throws Exception {
        call("{\"action\":\"scenario\",\"scenario\":\"live\"}");
        var existing = call("{}").deployment();
        assertNotNull(existing);
        var draft = call("{\"action\":\"configure-application\",\"application\":\"another-app\",\"cluster\":\"existing\"}");
        assertNull(draft.deployment());
        assertEquals(1, draft.organizations().getFirst().applications());
        assertFalse(draft.setup().pipelineConfigured());
        var restored = call("{\"action\":\"configure-application\",\"application\":\"parcel-service\",\"cluster\":\"existing\"}");
        assertEquals(existing.id(), restored.deployment().id());
        var planned = call("{\"action\":\"configure-application\",\"application\":\"parcel-service\",\"cluster\":\"automatic\"}");
        assertNull(planned.deployment());
    }

    @Test void bindsApplyToTheReviewedRevisionAndExpiry() throws Exception {
        call("{\"action\":\"scenario\",\"scenario\":\"ready\"}");
        var proposal = call("{\"action\":\"review\"}").proposal();
        now.set(now.get().plusSeconds(301));
        assertThrows(IllegalArgumentException.class, () -> call("{\"action\":\"deploy\",\"proposalId\":\"" + proposal.id() + "\"}"));
        var fresh = call("{\"action\":\"review\"}").proposal();
        call("{\"action\":\"select-organization\",\"organizationId\":\"personal\"}");
        assertThrows(IllegalArgumentException.class, () -> call("{\"action\":\"deploy\",\"proposalId\":\"" + fresh.id() + "\"}"));
        assertNull(call("{}").deployment());
    }

    @Test void enforcesCapacityAndPermissionsAndRejectsUnrelatedInputs() throws Exception {
        call("{\"action\":\"scenario\",\"scenario\":\"capacity\"}");
        assertThrows(IllegalArgumentException.class, () -> call("{\"action\":\"review\"}"));
        var confirmed = call("{\"action\":\"confirm-plan\"}");
        assertEquals("Pro", confirmed.organizations().getFirst().plan());
        assertEquals("", confirmed.plannedPlan());
        assertNotNull(call("{\"action\":\"review\"}").proposal());
        call("{\"action\":\"scenario\",\"scenario\":\"read-only\"}");
        assertFalse(call("{}").canDeploy());
        assertThrows(IllegalArgumentException.class, () -> call("{\"action\":\"review\"}"));
        assertThrows(IllegalArgumentException.class, () -> call("{\"action\":\"configure\",\"projectDirectory\":\"/private/example\"}"));
        assertThrows(IllegalArgumentException.class, () -> call("{\"action\":\"scenario\",\"scenario\":\"missing\"}"));
    }
}
