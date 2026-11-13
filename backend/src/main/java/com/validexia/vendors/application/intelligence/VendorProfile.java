package com.validexia.vendors.application.intelligence;

import com.validexia.vendors.application.VendorData;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The canonical vendor profile a {@code vendor.profile} tool call returns: vendor identity and
 * lifecycle fields plus the dependent systems, rendered as one JSON object with a fixed field order
 * and explicit JSON nulls. This JSON is the record every downstream brief is generated from and
 * cited against, so its shape is a contract: field names here are the citation paths a brief's key
 * facts must use ({@code legalName}, {@code dependentSystems[0].dataHandled}, …), and a field the
 * customer never recorded stays present-but-null rather than disappearing or being filled in.
 *
 * <p>Audit timestamps ({@code createdAt}/{@code updatedAt}) are deliberately excluded: the brief
 * reasons over business facts, not bookkeeping.
 */
public record VendorProfile(
    UUID id,
    String name,
    @Nullable String legalName,
    @Nullable String lei,
    @Nullable String jurisdiction,
    String code,
    @Nullable String category,
    @Nullable String domain,
    @Nullable String website,
    @Nullable String description,
    String criticality,
    @Nullable UUID ownerUserId,
    String status,
    @Nullable LocalDate contractStart,
    @Nullable LocalDate contractEnd,
    boolean tieringComplete,
    List<DependentSystem> dependentSystems) {

  /** One dependent system as the brief sees it (its database id is bookkeeping, not a fact). */
  public record DependentSystem(
      String name, @Nullable String dataHandled, @Nullable String residency) {}

  public VendorProfile {
    dependentSystems = List.copyOf(dependentSystems);
  }

  public static VendorProfile from(
      VendorData vendor, List<VendorData.DependentSystem> dependentSystems) {
    return new VendorProfile(
        vendor.id(),
        vendor.name(),
        vendor.legalName(),
        vendor.lei(),
        vendor.jurisdiction(),
        vendor.code(),
        vendor.category(),
        vendor.domain(),
        vendor.website(),
        vendor.description(),
        vendor.criticality(),
        vendor.ownerUserId(),
        vendor.status(),
        vendor.contractStart(),
        vendor.contractEnd(),
        vendor.tieringComplete(),
        dependentSystems.stream()
            .map(s -> new DependentSystem(s.name(), s.dataHandled(), s.residency()))
            .toList());
  }

  /** Renders the canonical profile JSON. Nulls are emitted as JSON null, never omitted. */
  public String toJson(ObjectMapper mapper) {
    ObjectNode root = mapper.createObjectNode();
    root.put("id", id.toString());
    root.put("name", name);
    putText(root, "legalName", legalName);
    putText(root, "lei", lei);
    putText(root, "jurisdiction", jurisdiction);
    root.put("code", code);
    putText(root, "category", category);
    putText(root, "domain", domain);
    putText(root, "website", website);
    putText(root, "description", description);
    root.put("criticality", criticality);
    if (ownerUserId == null) {
      root.putNull("ownerUserId");
    } else {
      root.put("ownerUserId", ownerUserId.toString());
    }
    root.put("status", status);
    putText(root, "contractStart", contractStart == null ? null : contractStart.toString());
    putText(root, "contractEnd", contractEnd == null ? null : contractEnd.toString());
    root.put("tieringComplete", tieringComplete);
    ArrayNode systems = root.putArray("dependentSystems");
    for (DependentSystem system : dependentSystems) {
      ObjectNode node = systems.addObject();
      node.put("name", system.name());
      putText(node, "dataHandled", system.dataHandled());
      putText(node, "residency", system.residency());
    }
    return mapper.writeValueAsString(root);
  }

  private static void putText(ObjectNode node, String field, @Nullable String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }
}
