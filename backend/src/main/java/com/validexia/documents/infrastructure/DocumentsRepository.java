package com.validexia.documents.infrastructure;

import com.validexia.documents.application.DocumentData;
import com.validexia.documents.application.DocumentData.Evidence;
import com.validexia.documents.application.DocumentData.Page;
import com.validexia.platform.tenancy.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The documents module's only door to its tables. Every method calls a SECURITY DEFINER function
 * from migration V13; the application role holds no direct grants on the document tables. Each
 * call runs in its own transaction opened through the tenant-aware transaction manager, with the
 * tenant context set first — the same pattern {@code VendorsRepository} and {@code AgentsRepository}
 * use, so {@code app.tenant_id} is the transaction's first statement and the functions'
 * fail-closed tenant guard sees it.
 */
@Repository
public class DocumentsRepository {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public DocumentsRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public UUID createDocument(
      UUID tenantId,
      UUID userId,
      UUID documentId,
      @Nullable UUID vendorId,
      String fileName,
      String fileType,
      long fileSize,
      String sha256,
      String storageKey,
      String processingStatus,
      int pageCount,
      @Nullable UUID uploadedByUserId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.queryForObject(
                "SELECT public.create_document(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.class,
                tenantId,
                documentId,
                vendorId,
                fileName,
                fileType,
                fileSize,
                sha256,
                storageKey,
                processingStatus,
                pageCount,
                uploadedByUserId));
  }

  public Optional<DocumentData> getDocument(UUID tenantId, UUID userId, UUID documentId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc
                .query(
                    "SELECT * FROM public.get_document(?, ?)",
                    DocumentsRepository::mapDocument,
                    tenantId,
                    documentId)
                .stream()
                .findFirst());
  }

  public List<DocumentData> listDocuments(UUID tenantId, UUID userId, @Nullable UUID vendorId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT * FROM public.list_documents(?, ?)",
                DocumentsRepository::mapDocument,
                tenantId,
                vendorId));
  }

  public boolean setDocumentStatus(
      UUID tenantId, UUID userId, UUID documentId, String processingStatus) {
    return inTenant(
        tenantId,
        userId,
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT public.set_document_status(?, ?, ?)",
                    Boolean.class,
                    tenantId,
                    documentId,
                    processingStatus)));
  }

  /**
   * Replaces the document's page set atomically: the delete and the insert run inside this
   * method's single transaction, so a partial replacement is never observable. Returns false when
   * the document does not belong to the tenant.
   */
  public boolean replaceDocumentPages(
      UUID tenantId, UUID userId, UUID documentId, String pagesJson) {
    return inTenant(
        tenantId,
        userId,
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT public.replace_document_pages(?, ?, CAST(? AS jsonb))",
                    Boolean.class,
                    tenantId,
                    documentId,
                    pagesJson)));
  }

  public List<Page> listDocumentPages(UUID tenantId, UUID userId, UUID documentId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT * FROM public.list_document_pages(?, ?)",
                (rs, row) ->
                    new Page(
                        rs.getObject("id", UUID.class),
                        rs.getInt("page_number"),
                        rs.getString("text_content"),
                        rs.getString("text_sha256")),
                tenantId,
                documentId));
  }

  /**
   * Creates an evidence record. Returns null when the document does not belong to the tenant; the
   * service maps that to a 404 rather than exposing a null to the caller.
   */
  public @Nullable UUID createEvidence(
      UUID tenantId,
      UUID userId,
      UUID documentId,
      @Nullable UUID pageId,
      @Nullable Integer pageNumber,
      String quoteText,
      String quoteSha256) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.queryForObject(
                "SELECT public.create_evidence(?, ?, ?, ?, ?, ?)",
                UUID.class,
                tenantId,
                documentId,
                pageId,
                pageNumber,
                quoteText,
                quoteSha256));
  }

  public List<Evidence> listEvidence(UUID tenantId, UUID userId, UUID documentId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT * FROM public.list_evidence(?, ?)",
                (rs, row) ->
                    new Evidence(
                        rs.getObject("id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("page_id", UUID.class),
                        rs.getObject("page_number", Integer.class),
                        rs.getString("quote_text"),
                        rs.getString("quote_sha256"),
                        toInstant(rs.getObject("created_at", OffsetDateTime.class))),
                tenantId,
                documentId));
  }

  private static DocumentData mapDocument(ResultSet rs, int row) throws SQLException {
    return new DocumentData(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("vendor_id", UUID.class),
        rs.getString("file_name"),
        rs.getString("file_type"),
        rs.getLong("file_size"),
        rs.getString("sha256"),
        rs.getString("storage_key"),
        rs.getString("processing_status"),
        rs.getInt("page_count"),
        rs.getObject("uploaded_by_user_id", UUID.class),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
  }

  private <T> T inTenant(UUID tenantId, @Nullable UUID userId, Supplier<T> work) {
    TenantContext.set(tenantId.toString(), userId == null ? null : userId.toString());
    try {
      return transactions.execute(tx -> work.get());
    } finally {
      TenantContext.clear();
    }
  }

  private static Instant toInstant(@Nullable OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
