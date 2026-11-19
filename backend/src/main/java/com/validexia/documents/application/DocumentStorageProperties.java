package com.validexia.documents.application;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Document storage configuration (the V13 documents slice). Follows the {@code AiProperties} /
 * {@code ClerkProperties} pattern: a compact-constructor record bound from {@code
 * validexia.documents.*} and registered through {@code @EnableConfigurationProperties}.
 *
 * <p>{@code storage-base-path} MUST point outside any web-served directory. The default is a
 * local-development fallback ({@code ~/.validexia/documents}); a production deployment sets {@code
 * VALIDEXIA_DOCUMENTS_STORAGE_BASE_PATH} to a restricted path such as {@code
 * /var/lib/validexia/documents}. Bytes are written under {@code
 * <base>/<tenant_id>/<document_id>/<filename>} and are never served statically.
 *
 * <p>{@code max-file-size-bytes} bounds the upload in bytes; {@code max-page-count} bounds the work
 * extraction is allowed to do. The byte ceiling alone does not bound work — a small PDF can declare
 * a very large page tree — so the page ceiling is enforced separately, in {@code PdfPageExtractor},
 * before any per-page allocation or text extraction begins.
 */
@ConfigurationProperties(prefix = "validexia.documents")
public record DocumentStorageProperties(
    @Nullable String storageBasePath, long maxFileSizeBytes, int maxPageCount) {

  /** Default upload ceiling: 20 MB. */
  public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;

  /**
   * Default extraction ceiling: 2,000 pages. Far above a real security questionnaire or SOC 2
   * report, far below the counts at which per-page extraction stops being a bounded operation.
   */
  public static final int DEFAULT_MAX_PAGE_COUNT = 2_000;

  public DocumentStorageProperties {
    if (storageBasePath == null || storageBasePath.isBlank()) {
      storageBasePath =
          Path.of(System.getProperty("user.home"), ".validexia", "documents")
              .toAbsolutePath()
              .toString();
    }
    if (maxFileSizeBytes <= 0) {
      maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE_BYTES;
    }
    if (maxPageCount <= 0) {
      maxPageCount = DEFAULT_MAX_PAGE_COUNT;
    }
  }
}
