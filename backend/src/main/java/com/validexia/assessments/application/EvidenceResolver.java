package com.validexia.assessments.application;

import com.validexia.assessments.application.AssessmentData.Assessment;
import com.validexia.assessments.application.AssessmentData.CitationRequest;
import com.validexia.assessments.application.AssessmentData.EvidenceRef;
import com.validexia.documents.application.DocumentData;
import com.validexia.documents.application.DocumentService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a caller's document reference into stored, verifiable evidence.
 *
 * <p>This is the trust boundary of the assessments slice. A caller — human or model — may name a
 * document and a page number, and may propose a quotation. It may not supply text or a hash. Every
 * method here resolves through the tenant-scoped documents module, so a reference can only ever
 * produce evidence the documents slice actually stored for this tenant.
 *
 * <p>Resolution rules, in order:
 *
 * <ol>
 *   <li>the document must exist and belong to the caller's tenant ({@code requireDocument} → 404);
 *   <li>the document must be {@code Ready};
 *   <li>the document must have a vendor assigned;
 *   <li>that vendor must be the assessment's vendor;
 *   <li>the cited page number must exist in the stored page set.
 * </ol>
 *
 * <p>Only then is the stored text and hash read. The quotation is verified by {@link
 * CitationVerifier} against that stored text, and the evidence record is resolved or created
 * through {@link DocumentService#createEvidence} — the same tenant-scoped path a human uses.
 */
@Component
final class EvidenceResolver {

  private final DocumentService documents;

  EvidenceResolver(DocumentService documents) {
    this.documents = documents;
  }

  /**
   * Resolves one referenced page. Throws {@link IllegalArgumentException} for a domain rule
   * violation (not Ready, no vendor, vendor mismatch, unknown page); a missing or foreign document
   * surfaces as the documents module's own 404.
   */
  ResolvedPage resolve(
      UUID tenantId, UUID userId, Assessment assessment, UUID documentId, int pageNumber) {
    if (documentId == null) {
      throw new IllegalArgumentException("evidence documentId is required");
    }
    if (pageNumber <= 0) {
      throw new IllegalArgumentException("evidence pageNumber must be positive");
    }
    DocumentData document = documents.requireDocument(tenantId, userId, documentId);
    if (!"Ready".equals(document.processingStatus())) {
      throw new IllegalArgumentException(
          "evidence document " + documentId + " is not Ready; its text was never extracted");
    }
    if (document.vendorId() == null) {
      throw new IllegalArgumentException(
          "evidence document " + documentId + " has no vendor assigned");
    }
    if (!document.vendorId().equals(assessment.vendorId())) {
      throw new IllegalArgumentException(
          "evidence document "
              + documentId
              + " belongs to vendor "
              + document.vendorId()
              + ", not to the assessment's vendor "
              + assessment.vendorId());
    }
    DocumentData.Page page =
        documents.listPages(tenantId, userId, documentId).stream()
            .filter(candidate -> candidate.pageNumber() == pageNumber)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "page " + pageNumber + " is not a page of document " + documentId));
    return new ResolvedPage(
        documentId, page.id(), page.pageNumber(), page.textContent(), page.textSha256());
  }

  /**
   * Returns the evidence record for a verified quotation, reusing an identical existing citation
   * for the same page rather than appending a duplicate.
   *
   * <p>The record is created through {@link DocumentService#createEvidence}, which re-checks the
   * tenant, the {@code Ready} status and the page anchor before writing.
   */
  EvidenceRef recordFor(
      UUID tenantId, UUID userId, ResolvedPage page, String quote, String quoteSha256) {
    List<DocumentData.Evidence> existing =
        documents.listEvidence(tenantId, userId, page.documentId());
    for (DocumentData.Evidence candidate : existing) {
      if (candidate.pageId() != null
          && candidate.pageId().equals(page.pageId())
          && candidate.quoteSha256() != null
          && candidate.quoteSha256().equalsIgnoreCase(quoteSha256)) {
        return new EvidenceRef(candidate.id(), page.pageNumber(), quote, candidate.quoteSha256());
      }
    }
    DocumentData.Evidence created =
        documents.createEvidence(
            tenantId, userId, page.documentId(), page.pageId(), page.pageNumber(), quote);
    return new EvidenceRef(created.id(), page.pageNumber(), quote, quoteSha256);
  }

  /** Verifies a proposed quotation against an already-resolved page. */
  CitationVerifier.Result verifyCitation(ResolvedPage page, String quote) {
    return CitationVerifier.verify(
        new CitationVerifier.Citation(page.pageNumber(), quote), List.of(page.storedPage()));
  }

  /** Convenience for the manual finding path: resolve, then verify. */
  CitationVerifier.Result resolveAndVerify(
      UUID tenantId, UUID userId, Assessment assessment, CitationRequest request) {
    return verifyCitation(
        resolve(tenantId, userId, assessment, request.documentId(), request.pageNumber()),
        request.quote());
  }
}
