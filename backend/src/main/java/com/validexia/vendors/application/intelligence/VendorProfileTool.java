package com.validexia.vendors.application.intelligence;

import com.validexia.agents.application.ToolHandler;
import com.validexia.agents.application.ToolInvocation;
import com.validexia.agents.domain.RiskClass;
import com.validexia.vendors.application.VendorService;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code vendor.profile} tool: the platform's first real agent tool, a READ over the vendors
 * data slice. It resolves one vendor through {@link VendorService} — which reaches the database
 * only through migration V12's tenant-guarded functions — scoped by the tenant and user the engine
 * put on the invocation, never by anything in the tool's input beyond the vendor id itself.
 *
 * <p>The output is the canonical {@link VendorProfile} JSON. That output is persisted by the engine
 * as this task's result: it is the retrieved-record snapshot the {@code vendor.intelligence} brief
 * is generated from, cited against, and (via its hash in the brief's envelope) later re-verifiable
 * against.
 */
@Component
public class VendorProfileTool implements ToolHandler {

  public static final String TOOL_KEY = "vendor.profile";

  private final VendorService vendors;
  private final ObjectMapper mapper;

  public VendorProfileTool(VendorService vendors, ObjectMapper mapper) {
    this.vendors = vendors;
    this.mapper = mapper;
  }

  @Override
  public String key() {
    return TOOL_KEY;
  }

  @Override
  public RiskClass riskClass() {
    return RiskClass.READ;
  }

  @Override
  public String execute(ToolInvocation invocation, String inputJson) {
    if (invocation.tenantId() == null || invocation.userId() == null) {
      throw new IllegalStateException(
          "vendor.profile invoked without the run's tenant scope; refusing to read unscoped");
    }
    UUID vendorId = parseVendorId(inputJson);
    VendorProfile profile =
        vendors
            .getVendor(invocation.tenantId(), invocation.userId(), vendorId)
            .map(
                vendor ->
                    VendorProfile.from(
                        vendor,
                        vendors.listDependentSystems(
                            invocation.tenantId(), invocation.userId(), vendorId)))
            .orElseThrow(() -> new VendorNotFoundException(vendorId));
    return profile.toJson(mapper);
  }

  @Override
  public String execute(String inputJson) {
    throw new IllegalStateException(
        "vendor.profile requires an invocation context (the run's tenant scope)");
  }

  private UUID parseVendorId(String inputJson) {
    JsonNode input;
    try {
      input = mapper.readTree(inputJson);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "vendor.profile input must be a JSON object with a vendorId", e);
    }
    String raw = input.path("vendorId").asText("");
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "vendor.profile input must carry a valid vendorId UUID, was: \"" + raw + "\"", e);
    }
  }
}
