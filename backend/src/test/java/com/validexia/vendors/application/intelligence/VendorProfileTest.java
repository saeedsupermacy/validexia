package com.validexia.vendors.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.vendors.application.VendorData;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The canonical profile JSON: exact fields, explicit nulls, dependent systems, no bookkeeping. */
class VendorProfileTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private VendorData vendor() {
    return new VendorData(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        "Acme Cloud",
        "Acme Cloud Services Ltd",
        null,
        "Ireland",
        "VND-001",
        "SaaS",
        null,
        "https://acme.example",
        "Hosts the payroll platform.",
        "Tier 1",
        null,
        "Active",
        LocalDate.of(2025, 1, 15),
        null,
        true,
        Instant.parse("2025-01-15T00:00:00Z"),
        Instant.parse("2025-06-01T00:00:00Z"));
  }

  @Test
  void rendersEveryBriefFieldWithExplicitNulls() throws Exception {
    VendorProfile profile =
        VendorProfile.from(
            vendor(),
            List.of(
                new VendorData.DependentSystem(
                    UUID.randomUUID(), "Payroll processing", "Personal data (payroll)", "EU")));
    JsonNode json = mapper.readTree(profile.toJson(mapper));

    assertEquals("11111111-1111-1111-1111-111111111111", json.path("id").asText());
    assertEquals("Acme Cloud", json.path("name").asText());
    assertEquals("Acme Cloud Services Ltd", json.path("legalName").asText());
    assertTrue(
        json.has("lei") && json.get("lei").isNull(), "unrecorded LEI stays an explicit null");
    assertEquals("Ireland", json.path("jurisdiction").asText());
    assertEquals("VND-001", json.path("code").asText());
    assertTrue(json.has("domain") && json.get("domain").isNull());
    assertEquals("Tier 1", json.path("criticality").asText());
    assertTrue(json.has("ownerUserId") && json.get("ownerUserId").isNull());
    assertEquals("Active", json.path("status").asText());
    assertEquals("2025-01-15", json.path("contractStart").asText());
    assertTrue(json.has("contractEnd") && json.get("contractEnd").isNull());
    assertTrue(json.path("tieringComplete").asBoolean());

    JsonNode systems = json.path("dependentSystems");
    assertEquals(1, systems.size());
    assertEquals("Payroll processing", systems.get(0).path("name").asText());
    assertEquals("Personal data (payroll)", systems.get(0).path("dataHandled").asText());
    assertEquals("EU", systems.get(0).path("residency").asText());
    assertFalse(systems.get(0).has("id"), "dependent-system database ids are not brief facts");
  }

  @Test
  void carriesNoBookkeepingOrScoringFields() throws Exception {
    JsonNode json = mapper.readTree(VendorProfile.from(vendor(), List.of()).toJson(mapper));
    assertFalse(json.has("createdAt"));
    assertFalse(json.has("updatedAt"));
    assertFalse(json.has("riskScore"));
    assertFalse(json.has("riskLevel"));
    assertEquals(0, json.path("dependentSystems").size());
  }
}
