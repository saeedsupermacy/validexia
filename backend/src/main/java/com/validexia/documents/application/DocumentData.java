package com.validexia.documents.application;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Application-layer data for the documents slice (migration V13). A document is a stored,
 * tenant-scoped PDF; its pages carry the extracted text; evidence records are the citations drawn
 * from those pages. Every field is data, never instructions (D8).
 */
public record DocumentData(
    UUID id,
    UUID tenantId,
    @Nullable UUID vendorId,
    String fileName,
    String fileType,
    long fileSize,
    String sha256,
    String storageKey,
    String processingStatus,
    int pageCount,
    @Nullable UUID uploadedByUserId,
    Instant createdAt,
    Instant updatedAt) {

  /** One extracted PDF page: its 1-based number and its text (blank pages extract to ""). */
  public record ExtractedPage(int pageNumber, String text) {}

  /** A persisted page row. */
  public record Page(UUID id, int pageNumber, String textContent, String textSha256) {}

  /** An evidence citation drawn from a document, optionally anchored to one page. */
  public record Evidence(
      UUID id,
      UUID documentId,
      @Nullable UUID pageId,
      @Nullable Integer pageNumber,
      String quoteText,
      String quoteSha256,
      Instant createdAt) {}

  /** A client upload, already freed of servlet types so the application layer stays web-free. */
  public record UploadCommand(
      String fileName, @Nullable String contentType, long size, byte[] content) {}
}
