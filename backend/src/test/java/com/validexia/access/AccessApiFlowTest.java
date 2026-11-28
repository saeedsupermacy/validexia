package com.validexia.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import com.validexia.support.TestTokens;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end M1 access flows through the real filter chain (U1 freeze v1.4.1 §5): all 18 endpoints,
 * the frozen error mapping (§5.3), the role gate on confirm/reject, the terminated-vendor reinstate
 * message, the evidence check order, and cross-tenant 404s.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test"
    })
@AutoConfigureMockMvc
@Import(TestClerkConfig.class)
class AccessApiFlowTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired MockMvc mvc;
  @Autowired DirectoryRepository directory;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();
  private final ObjectMapper json = new ObjectMapper();

  private String token(String subject) throws Exception {
    return TestTokens.mint(
        TestClerkConfig.key(), Instant.now(), 60, Map.of("sub", subject, "sid", "s-" + subject));
  }

  /** Creates a tenant and a provisioned member with the given role; returns the tenant id. */
  private UUID tenantWithMember(String subject, String role) throws Exception {
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Access Tenant', ?)",
        tenantId,
        "t-" + tenantId);
    String token = token(subject);
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    UUID userId = directory.findUser(subject).orElseThrow().userId();
    admin.update(
        "INSERT INTO public.memberships (user_id, tenant_id, role) VALUES (?, ?, ?)",
        userId,
        tenantId,
        role);
    return tenantId;
  }

  private UUID seedVendor(UUID tenantId) {
    UUID vendor = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.vendors (id, tenant_id, name, code) VALUES (?, ?, 'V', ?)",
        vendor,
        tenantId,
        "V-" + vendor);
    return vendor;
  }

  private UUID seedDocument(UUID tenantId, UUID vendorId, String processingStatus) {
    UUID document = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.documents (id, tenant_id, vendor_id, file_name, file_type, file_size,"
            + " sha256, storage_key, processing_status)"
            + " VALUES (?, ?, ?, 'f.pdf', 'pdf', 1, ?, ?, ?)",
        document,
        tenantId,
        vendorId,
        "h-" + document,
        "k-" + document,
        processingStatus);
    return document;
  }

  private JsonNode createSystem(String token, UUID tenantId, String name) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/internal-systems")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\",\"description\":\"d\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.status").value("Active"))
            .andExpect(jsonPath("$.activeGrantCount").value(0))
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode createGrant(String token, UUID tenantId, UUID vendorId, UUID systemId)
      throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/access-grants")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"vendorId\":\""
                            + vendorId
                            + "\",\"internalSystemId\":\""
                            + systemId
                            + "\",\"privilegeLevel\":\"Read\",\"accessMethod\":\"API\","
                            + "\"sourceKind\":\"Customer Entered\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.lifecycleStatus").value("Active"))
            .andExpect(jsonPath("$.verificationStatus").value("Unverified"))
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  @Test
  void systemEndpointsEndToEnd() throws Exception {
    String subject = "access-systems-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject, "admin");
    UUID vendor = seedVendor(tenantId);
    String systemId = createSystem(token, tenantId, "CRM").get("id").asText();

    // Duplicate Active name -> 409 with the frozen code.
    mvc.perform(
            post("/api/v1/internal-systems")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"CRM\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("internal_system_name_in_use"));

    // Blank name -> 400 VALIDATION_FAILED.
    mvc.perform(
            post("/api/v1/internal-systems")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    // PUT is a full replace: omitting description clears it.
    mvc.perform(
            put("/api/v1/internal-systems/" + systemId)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"CRM Renamed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("CRM Renamed"))
        .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()));

    // A grant makes the retire response carry activeGrantCount = 1.
    createGrant(token, tenantId, vendor, UUID.fromString(systemId));
    mvc.perform(
            post("/api/v1/internal-systems/" + systemId + "/retire")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("Retired"))
        .andExpect(jsonPath("$.activeGrantCount").value(1));

    // Retiring again is a refused transition: 400 with the captured current status.
    mvc.perform(
            post("/api/v1/internal-systems/" + systemId + "/retire")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("transition_not_allowed"))
        .andExpect(jsonPath("$.message").value("Cannot retire: internal system is Retired"));

    // PUT on a Retired system is refused the same way (S2's zero-rows outcome).
    mvc.perform(
            put("/api/v1/internal-systems/" + systemId)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nope\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("transition_not_allowed"));

    mvc.perform(
            post("/api/v1/internal-systems/" + systemId + "/restore")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("Active"))
        .andExpect(jsonPath("$.retiredAt").value(org.hamcrest.Matchers.nullValue()));

    mvc.perform(
            get("/api/v1/internal-systems")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].activeGrantCount").value(1));
  }

  @Test
  void ownerMustBeATenantMemberOfAnyStatus() throws Exception {
    String subject = "access-owner-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject, "admin");

    mvc.perform(
            post("/api/v1/internal-systems")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"ownerUserId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("owner_not_tenant_member"));
  }

  @Test
  void grantEndpointsEndToEnd() throws Exception {
    String adminSubject = "access-grants-admin-" + UUID.randomUUID();
    String adminToken = token(adminSubject);
    UUID tenantId = tenantWithMember(adminSubject, "admin");
    String memberSubject = "access-grants-member-" + UUID.randomUUID();
    String memberToken = token(memberSubject);
    // Provision the member user in the same tenant.
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + memberToken))
        .andExpect(status().isOk());
    UUID memberId = directory.findUser(memberSubject).orElseThrow().userId();
    admin.update(
        "INSERT INTO public.memberships (user_id, tenant_id, role) VALUES (?, ?, 'member')",
        memberId,
        tenantId);

    UUID vendor = seedVendor(tenantId);
    String systemId = createSystem(adminToken, tenantId, "CRM").get("id").asText();
    String grantId =
        createGrant(adminToken, tenantId, vendor, UUID.fromString(systemId)).get("id").asText();

    // Invalid enum -> 400 VALIDATION_FAILED from controller validation.
    mvc.perform(
            post("/api/v1/access-grants")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"vendorId\":\""
                        + vendor
                        + "\",\"internalSystemId\":\""
                        + systemId
                        + "\",\"privilegeLevel\":\"Superuser\",\"accessMethod\":\"API\","
                        + "\"sourceKind\":\"Unknown\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    // Unknown vendor -> 404 vendor_not_found.
    mvc.perform(
            post("/api/v1/access-grants")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"vendorId\":\""
                        + UUID.randomUUID()
                        + "\",\"internalSystemId\":\""
                        + systemId
                        + "\",\"privilegeLevel\":\"Read\",\"accessMethod\":\"API\","
                        + "\"sourceKind\":\"Unknown\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("vendor_not_found"));

    // A plain member cannot confirm (role gate): 403 FORBIDDEN.
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/confirm")
                .header("Authorization", "Bearer " + memberToken)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/confirm")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationStatus").value("Confirmed"));

    // Confirming again is refused with the captured verification status.
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/confirm")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("transition_not_allowed"))
        .andExpect(jsonPath("$.message").value("Cannot confirm: access grant is Confirmed"));

    // Blank rejection reason -> 400; a real reject stores the reason.
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/reject")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/reject")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"weak proof\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationStatus").value("Rejected"))
        .andExpect(jsonPath("$.rejectionReason").value("weak proof"));

    // Suspend -> reinstate -> suspend -> revoke; revocation is terminal.
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/suspend")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"incident\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lifecycleStatus").value("Suspended"));
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/reinstate")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lifecycleStatus").value("Active"));
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/revoke")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"access removed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lifecycleStatus").value("Revoked"))
        .andExpect(jsonPath("$.revocationReason").value("access removed"))
        .andExpect(jsonPath("$.discoveryNote").value(org.hamcrest.Matchers.nullValue()));
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/reinstate")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Cannot reinstate: access grant is Revoked"));

    // List filters apply over G9's result.
    mvc.perform(
            get("/api/v1/access-grants?lifecycleStatus=Revoked")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(
            get("/api/v1/access-grants?lifecycleStatus=Active")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(
            get("/api/v1/access-grants?lifecycleStatus=Banana")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void reinstateAgainstTerminatedVendorUsesTheFrozenMessage() throws Exception {
    String subject = "access-termvendor-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject, "admin");
    UUID vendor = seedVendor(tenantId);
    String systemId = createSystem(token, tenantId, "CRM").get("id").asText();
    String grantId =
        createGrant(token, tenantId, vendor, UUID.fromString(systemId)).get("id").asText();

    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/suspend")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"hold\"}"))
        .andExpect(status().isOk());
    admin.update("UPDATE public.vendors SET status = 'Terminated' WHERE id = ?", vendor);

    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/reinstate")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("transition_not_allowed"))
        .andExpect(jsonPath("$.message").value("Cannot reinstate: vendor is terminated"));

    // Creating against the terminated vendor and the retired system map to their own codes.
    mvc.perform(
            post("/api/v1/access-grants")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"vendorId\":\""
                        + vendor
                        + "\",\"internalSystemId\":\""
                        + systemId
                        + "\",\"privilegeLevel\":\"Read\",\"accessMethod\":\"API\","
                        + "\"sourceKind\":\"Unknown\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("vendor_terminated"));
  }

  @Test
  void discoveryEndpointCreatesABornRevokedGrant() throws Exception {
    String subject = "access-discovery-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject, "admin");
    UUID vendor = seedVendor(tenantId);
    String systemId = createSystem(token, tenantId, "CRM").get("id").asText();

    mvc.perform(
            post("/api/v1/access-grants/discoveries")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"vendorId\":\""
                        + vendor
                        + "\",\"internalSystemId\":\""
                        + systemId
                        + "\",\"privilegeLevel\":\"Admin\",\"accessMethod\":\"Direct Login\","
                        + "\"sourceKind\":\"Unknown\",\"discoveryNote\":\"found in export\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.lifecycleStatus").value("Revoked"))
        .andExpect(jsonPath("$.discoveryNote").value("found in export"))
        .andExpect(jsonPath("$.revocationReason").value(org.hamcrest.Matchers.nullValue()));

    mvc.perform(
            post("/api/v1/access-grants/discoveries")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"vendorId\":\""
                        + vendor
                        + "\",\"internalSystemId\":\""
                        + systemId
                        + "\",\"privilegeLevel\":\"Admin\",\"accessMethod\":\"Direct Login\","
                        + "\"sourceKind\":\"Unknown\",\"discoveryNote\":\" \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void evidenceEndpointsEndToEnd() throws Exception {
    String subject = "access-evidence-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject, "admin");
    UUID vendor = seedVendor(tenantId);
    UUID otherVendor = seedVendor(tenantId);
    String systemId = createSystem(token, tenantId, "CRM").get("id").asText();
    String grantId =
        createGrant(token, tenantId, vendor, UUID.fromString(systemId)).get("id").asText();
    UUID ready = seedDocument(tenantId, vendor, "Ready");
    UUID pending = seedDocument(tenantId, vendor, "Pending");
    UUID otherVendorDoc = seedDocument(tenantId, otherVendor, "Ready");

    // Endpoint 18's frozen distinction: no evidence yet -> 200 []; no grant -> 404.
    mvc.perform(
            get("/api/v1/access-grants/" + grantId + "/evidence")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(
            get("/api/v1/access-grants/" + UUID.randomUUID() + "/evidence")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("access_grant_not_found"));

    MvcResult linked =
        mvc.perform(
                post("/api/v1/access-grants/" + grantId + "/evidence")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"documentId\":\"" + ready + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.documentId").value(ready.toString()))
            .andReturn();
    String linkId = json.readTree(linked.getResponse().getContentAsString()).get("id").asText();

    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/evidence")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + ready + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("evidence_already_linked"));
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/evidence")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + pending + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("document_not_ready"));
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/evidence")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + otherVendorDoc + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("document_vendor_mismatch"));
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/evidence")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("document_not_found"));

    // Unlink under a different grant is a 404; under its own grant it succeeds once.
    String otherGrantId =
        createGrant(token, tenantId, otherVendor, UUID.fromString(systemId)).get("id").asText();
    mvc.perform(
            post("/api/v1/access-grants/" + otherGrantId + "/evidence/" + linkId + "/unlink")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("evidence_link_not_found"));
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/evidence/" + linkId + "/unlink")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/evidence/" + linkId + "/unlink")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("transition_not_allowed"))
        .andExpect(jsonPath("$.message").value("Cannot unlink: evidence link is already unlinked"));
  }

  @Test
  void crossTenantAccessIsNotFound() throws Exception {
    String subjectA = "access-iso-a-" + UUID.randomUUID();
    String tokenA = token(subjectA);
    UUID tenantA = tenantWithMember(subjectA, "admin");
    UUID vendor = seedVendor(tenantA);
    String systemId = createSystem(tokenA, tenantA, "CRM").get("id").asText();
    String grantId =
        createGrant(tokenA, tenantA, vendor, UUID.fromString(systemId)).get("id").asText();

    String subjectB = "access-iso-b-" + UUID.randomUUID();
    String tokenB = token(subjectB);
    UUID tenantB = tenantWithMember(subjectB, "admin");

    mvc.perform(
            get("/api/v1/access-grants/" + grantId)
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("access_grant_not_found"));
    mvc.perform(
            get("/api/v1/internal-systems/" + systemId)
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("internal_system_not_found"));
  }
}
