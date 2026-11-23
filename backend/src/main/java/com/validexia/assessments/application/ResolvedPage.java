package com.validexia.assessments.application;

import java.util.UUID;

/**
 * One document page as stored by the documents slice, carrying the identifiers needed to cite it.
 *
 * <p>Produced only by {@link EvidenceResolver}, never by a caller: the text and hash are read from
 * the database, so a fabricated page cannot enter the assessment flow.
 *
 * @param documentId the tenant's document the page belongs to
 * @param pageId the {@code document_pages} row id, used to resolve the evidence record
 * @param pageNumber the 1-based page number, the durable human-readable citation coordinate
 * @param text the extracted page text, exactly as stored
 * @param textSha256 the SHA-256 the documents slice stored for that text
 */
record ResolvedPage(UUID documentId, UUID pageId, int pageNumber, String text, String textSha256) {

  /** The shape {@link CitationVerifier} checks: number, stored text, stored hash. */
  CitationVerifier.StoredPage storedPage() {
    return new CitationVerifier.StoredPage(pageNumber, text, textSha256);
  }
}
