package com.validexia.documents.application;

import com.validexia.documents.application.DocumentData.Evidence;
import com.validexia.documents.application.DocumentData.ExtractedPage;
import com.validexia.documents.application.DocumentData.UploadCommand;
import com.validexia.documents.infrastructure.DocumentStorageService;
import com.validexia.documents.infrastructure.DocumentsRepository;
import com.validexia.documents.infrastructure.PdfPageExtractor;
import com.validexia.platform.errors.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Application layer for the documents slice: the API layer talks to this service, never to the
 * repository or the storage adapter directly (architecture rule: controllers do not use data
 * access). Upload is a single workflow — store the bytes, register the metadata, extract one row
 * per page, persist them, mark the document Ready — so a caller never observes a half-registered
 * document.
 */
@Service
public class DocumentService {

  private static final String PDF_FILE_TYPE = "application/pdf";

  private final DocumentsRepository documents;
  private final DocumentStorageService storage;
  private final PdfPageExtractor extractor;

  public DocumentService(
      DocumentsRepository documents, DocumentStorageService storage, PdfPageExtractor extractor) {
    this.documents = documents;
    this.storage = storage;
    this.extractor = extractor;
  }

  /**
   * Stores the upload, registers its metadata, extracts and persists one page row per PDF page, and
   * marks the document {@code Ready}.
   *
   * <p>A file that cannot be extracted is kept with status {@code Failed} — the bytes and the
   * failure are both audit-relevant — and the caller gets a 422. Only a failure to register the
   * metadata removes the stored bytes, because at that point no row references them.
   */
  public DocumentData upload(
      UUID tenantId, UUID userId, @Nullable UUID vendorId, UploadCommand upload) {
    UUID documentId = UUID.randomUUID();
    String storageKey;
    try {
      storageKey = storage.store(tenantId, documentId, upload);
    } catch (DocumentUploadException e) {
      throw ApiException.validation(e.getMessage());
    }

    UUID id;
    try {
      id =
          documents.createDocument(
              tenantId,
              userId,
              documentId,
              vendorId,
              upload.fileName(),
              PDF_FILE_TYPE,
              upload.content().length,
              sha256Hex(upload.content()),
              storageKey,
              "Processing",
              0,
              userId);
    } catch (RuntimeException e) {
      storage.delete(tenantId, documentId);
      throw e;
    }

    try {
      List<ExtractedPage> pages = extractor.extract(upload.content());
      documents.replaceDocumentPages(tenantId, userId, id, pagesJson(pages));
      documents.setDocumentStatus(tenantId, userId, id, "Ready");
    } catch (RuntimeException e) {
      try {
        documents.setDocumentStatus(tenantId, userId, id, "Failed");
      } catch (RuntimeException ignored) {
        // The original failure is the one that matters; the status flip is best effort.
      }
      if (e instanceof PdfPageExtractor.DocumentExtractionException) {
        throw new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "DOCUMENT_EXTRACTION_FAILED",
            "The PDF could not be processed: " + e.getMessage());
      }
      throw e;
    }
    return documents
        .getDocument(tenantId, userId, id)
        .orElseThrow(() -> new IllegalStateException("Document vanished after upload"));
  }

  public Optional<DocumentData> getDocument(UUID tenantId, UUID userId, UUID documentId) {
    return documents.getDocument(tenantId, userId, documentId);
  }

  public List<DocumentData> listDocuments(UUID tenantId, UUID userId, @Nullable UUID vendorId) {
    return documents.listDocuments(tenantId, userId, vendorId);
  }

  public List<DocumentData.Page> listPages(UUID tenantId, UUID userId, UUID documentId) {
    return documents.listDocumentPages(tenantId, userId, documentId);
  }

  public List<Evidence> listEvidence(UUID tenantId, UUID userId, UUID documentId) {
    return documents.listEvidence(tenantId, userId, documentId);
  }

  /** Returns the document or throws the 404 the API should surface for a missing document. */
  public DocumentData requireDocument(UUID tenantId, UUID userId, UUID documentId) {
    return documents
        .getDocument(tenantId, userId, documentId)
        .orElseThrow(() -> notFound(documentId));
  }

  /**
   * Records one evidence citation against a document the platform successfully read.
   *
   * <p>The document must already belong to the caller's tenant (enforced by {@link
   * #requireDocument}, which also supplies the 404 for a missing or foreign document) and must be
   * {@code Ready}. A document that failed extraction has no pages and no verified text, so a
   * citation against it would be an unverifiable claim stored indistinguishably from a real one.
   * Page anchors are then validated by {@link #validatePageReference}.
   */
  public Evidence createEvidence(
      UUID tenantId,
      UUID userId,
      UUID documentId,
      @Nullable UUID pageId,
      @Nullable Integer pageNumber,
      String quoteText) {
    DocumentData document = requireDocument(tenantId, userId, documentId);
    if (!"Ready".equals(document.processingStatus())) {
      throw ApiException.validation(
          "evidence can only be recorded against a document that is Ready");
    }
    if (quoteText == null || quoteText.isBlank()) {
      throw ApiException.validation("quoteText is required");
    }
    validatePageReference(tenantId, userId, documentId, pageId, pageNumber);
    UUID id =
        documents.createEvidence(
            tenantId,
            userId,
            documentId,
            pageId,
            pageNumber,
            quoteText,
            sha256Hex(quoteText.getBytes(StandardCharsets.UTF_8)));
    if (id == null) {
      throw notFound(documentId);
    }
    return documents.listEvidence(tenantId, userId, documentId).stream()
        .filter(evidence -> evidence.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Evidence vanished after creation"));
  }

  /**
   * Rejects an evidence citation whose page anchor does not resolve to a real page of this
   * document. Both fields stay optional — an unanchored citation is legal — but a supplied anchor
   * must exist, and when both are supplied they must name the same page.
   *
   * <p>The document itself was already tenant-checked by {@link #requireDocument}; this reads the
   * page set through the same tenant-scoped repository call, so a page from another tenant or
   * document can never validate.
   */
  private void validatePageReference(
      UUID tenantId,
      UUID userId,
      UUID documentId,
      @Nullable UUID pageId,
      @Nullable Integer pageNumber) {
    if (pageId == null && pageNumber == null) {
      return;
    }
    List<DocumentData.Page> pages = documents.listDocumentPages(tenantId, userId, documentId);
    DocumentData.Page byId = null;
    DocumentData.Page byNumber = null;
    if (pageId != null) {
      byId = pages.stream().filter(page -> page.id().equals(pageId)).findFirst().orElse(null);
      if (byId == null) {
        throw ApiException.validation("pageId does not belong to this document");
      }
    }
    if (pageNumber != null) {
      byNumber =
          pages.stream().filter(page -> page.pageNumber() == pageNumber).findFirst().orElse(null);
      if (byNumber == null) {
        throw ApiException.validation(
            "pageNumber " + pageNumber + " is not a page of this document");
      }
    }
    if (byId != null && byNumber != null && !byId.id().equals(byNumber.id())) {
      throw ApiException.validation("pageId and pageNumber refer to different pages");
    }
  }

  /**
   * Serializes the extracted pages for {@code replace_document_pages}. Keys are the snake_case
   * column names the function's {@code jsonb_to_recordset} expects, and the page hash is computed
   * here so the database needs no crypto extension.
   */
  private static String pagesJson(List<ExtractedPage> pages) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < pages.size(); i++) {
      ExtractedPage page = pages.get(i);
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"page_number\":")
          .append(page.pageNumber())
          .append(",\"text_content\":")
          .append(quoteJson(page.text()))
          .append(",\"text_sha256\":")
          .append(quoteJson(sha256Hex(page.text().getBytes(StandardCharsets.UTF_8))))
          .append('}');
    }
    return json.append(']').toString();
  }

  private static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /** Minimal JSON string quoting; page text is user content and must never break the payload. */
  private static String quoteJson(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  private static ApiException notFound(UUID documentId) {
    return new ApiException(
        HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "No document " + documentId);
  }
}
