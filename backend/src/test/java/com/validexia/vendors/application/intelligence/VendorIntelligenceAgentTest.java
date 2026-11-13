package com.validexia.vendors.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.validexia.agents.application.AgentPlanner;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The specialist's plan: a fixed two-step READ plan over the context's vendor id. */
class VendorIntelligenceAgentTest {

  private final VendorIntelligenceAgent agent = new VendorIntelligenceAgent();

  @Test
  void plansProfileThenBriefForTheContextVendor() {
    List<AgentPlanner.PlannedTask> plan =
        agent.plan("Brief this vendor", "{\"vendorId\":\"11111111-1111-1111-1111-111111111111\"}");
    assertEquals(2, plan.size());
    assertEquals("vendor.profile", plan.get(0).toolKey());
    assertEquals("vendor.intelligence.brief", plan.get(1).toolKey());
    assertEquals(
        "{\"vendorId\":\"11111111-1111-1111-1111-111111111111\"}", plan.get(0).inputJson());
    assertEquals(plan.get(0).inputJson(), plan.get(1).inputJson());
  }

  @Test
  void rejectsAContextWithoutAVendorId() {
    assertThrows(IllegalArgumentException.class, () -> agent.plan("Brief", "{}"));
    assertThrows(IllegalArgumentException.class, () -> agent.plan("Brief", null));
  }

  @Test
  void rejectsAMalformedVendorId() {
    assertThrows(
        IllegalArgumentException.class, () -> agent.plan("Brief", "{\"vendorId\":\"not-a-uuid\"}"));
  }
}
