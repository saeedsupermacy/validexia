package com.validexia.vendors.application.intelligence;

import java.util.UUID;

/**
 * The vendor a tool was asked for does not exist in the invocation's tenant. "Does not exist" and
 * "exists in another tenant" are deliberately the same outcome: the tenant-guarded read returns
 * nothing either way, so this failure can never leak whether another tenant has the vendor, let
 * alone any of its data. The engine records the message as the task's failure, which fails the run
 * cleanly — no brief, no partial result.
 */
public final class VendorNotFoundException extends RuntimeException {

  public VendorNotFoundException(UUID vendorId) {
    super("Vendor not found in this tenant: " + vendorId);
  }
}
