package com.validexia.documents.api;

import com.validexia.documents.application.DocumentData;
import com.validexia.documents.application.DocumentService;
import com.validexia.platform.errors.ApiException;
import com.validexia.platform.security.RequestContext;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Documents endpoints (migration V13). Everything is scoped to the caller's per-request tenant —
 * the {@code X-Tenant-Id} header verified by the authentication filter — so there is no cross-tenant
 * view. Uploaded bytes are written outside any web-served directory by the storage service; this
 * controller moves metadata and extracted text only.
 *
 * <p>Document content is DATA, never instructions (D8): nothing here interprets a document, and no
 * document text is reflected into a prompt by this layer.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

  private final DocumentService documents;

  public DocumentController(DocumentService documents) {
    this.documents = documents;
  }

  /**
   * Stores a PDF, registers its metadata, extracts one page row per page and marks it {@code
   * Ready}. The file is validated by content (the {@code %PDF-} magic header), not by a
   * client-asserted content type.
   */
  @PostMapping(consumes = "multipart/form-data")
  public DocumentData upload(
      @RequestPart("file") @Nullable MultipartFile file,
      @RequestParam(value = "vendorId", required = false) @Nullable UUID vendorId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    if (file == null || file.isEmpty()) {
      throw ApiException.validation("a PDF file is required");
    }
    String fileName = file.getOriginalFilename();
    if (fileName == null || fileName.isBlank()) {
      throw ApiException.validation("a file name is required");
    }
    DocumentData.UploadCommand command;
    try {
      command =
          new DocumentData.UploadCommand(
              fileName, file.getContentType(), file.getSize(), file.getBytes());
    } catch (IOException e) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "UPLOAD_UNREADABLE", "the uploaded file could not be read");
    }
    return documents.upload(tenantId, context.userId(), vendorId, command);
  }

  /** Lists the tenant's documents, optionally filtered to one vendor. */
  @GetMapping
  public List<DocumentData> list(
      @RequestParam(value = "vendorId", required = false) @Nullable UUID vendorId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return documents.listDocuments(tenantId, context.userId(), vendorId);
  }

  /** Returns one document, or 404 when it does not belong to the caller's tenant. */
  @GetMapping("/{documentId}")
  public DocumentData get(@PathVariable UUID documentId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return documents.requireDocument(tenantId, context.userId(), documentId);
  }

  /** Returns the extracted page rows, so a caller can cite a page by number. */
  @GetMapping("/{documentId}/pages")
  public List<DocumentData.Page> pages(@PathVariable UUID documentId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    documents.requireDocument(tenantId, context.userId(), documentId);
    return documents.listPages(tenantId, context.userId(), documentId);
  }

  /** Returns the evidence citations drawn from one document. */
  @GetMapping("/{documentId}/evidence")
  public List<DocumentData.Evidence> evidence(@PathVariable UUID documentId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    documents.requireDocument(tenantId, context.userId(), documentId);
    return documents.listEvidence(tenantId, context.userId(), documentId);
  }

  /**
   * Records one evidence citation. The quote and its hash are stored as supplied; the deterministic
   * check that the quote actually appears on the cited page belongs to the assessment slice, which
   * owns findings.
   */
  @PostMapping("/{documentId}/evidence")
  public DocumentData.Evidence createEvidence(
      @PathVariable UUID documentId, @RequestBody EvidenceRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return documents.createEvidence(
        tenantId,
        context.userId(),
        documentId,
        request.pageId(),
        request.pageNumber(),
        request.quoteText());
  }

  /** Body for creating one evidence citation. */
  public record EvidenceRequest(
      @Nullable UUID pageId, @Nullable Integer pageNumber, String quoteText) {}

  private static UUID requireTenant(RequestContext.Context context) {
    if (context.tenantId() == null) {
      throw ApiException.validation("X-Tenant-Id header is required for document endpoints");
    }
    return context.tenantId();
  }
}
