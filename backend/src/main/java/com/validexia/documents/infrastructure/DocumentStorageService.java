package com.validexia.documents.infrastructure;

import com.validexia.documents.application.DocumentData.UploadCommand;
import com.validexia.documents.application.DocumentStorageProperties;
import com.validexia.documents.application.DocumentUploadException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes uploaded documents to a configurable, tenant-scoped directory tree: {@code
 * <base>/<tenant_id>/<document_id>/<filename>}. The base path comes from {@code
 * validexia.documents.storage-base-path} and must never be a web-served location.
 *
 * <p>Validation is enforced here, at the boundary where bytes meet the filesystem: PDF only (by
 * extension and by the {@code %PDF-} magic header, not by a client-asserted content type) and at
 * most {@code validexia.documents.max-file-size-bytes} bytes. File names are reduced to a single
 * sanitized path segment, so a client can never choose a directory or escape the document
 * directory.
 */
@Component
public class DocumentStorageService {

  /** The PDF header may be preceded by a little junk; scan the first kilobyte for it. */
  private static final int MAX_PDF_MAGIC_SCAN = 1024;

  private final DocumentStorageProperties properties;

  public DocumentStorageService(DocumentStorageProperties properties) {
    this.properties = properties;
  }

  /** Validates and stores the upload, returning its tenant-scoped storage key. */
  public String store(UUID tenantId, UUID documentId, UploadCommand upload) {
    validate(upload);
    Path base = baseDirectory();
    Path target = resolve(base, tenantId, documentId, upload.fileName());
    try {
      Files.createDirectories(target.getParent());
      Files.write(
          target, upload.content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new DocumentUploadException("the file could not be stored");
    }
    return storageKey(tenantId, documentId, target.getFileName().toString());
  }

  /** Best-effort removal, used when the metadata row could not be created. */
  public void delete(UUID tenantId, UUID documentId) {
    Path directory = baseDirectory().resolve(tenantId.toString()).resolve(documentId.toString());
    if (!Files.exists(directory)) {
      return;
    }
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      // Best effort only: the request has already failed, and a leftover file is an
      // operational cleanup matter, never something to report to the caller.
    }
  }

  private Path baseDirectory() {
    return Path.of(properties.storageBasePath()).toAbsolutePath().normalize();
  }

  private void validate(UploadCommand upload) {
    if (upload.content() == null || upload.content().length == 0) {
      throw new DocumentUploadException("a PDF file is required");
    }
    if (upload.content().length > properties.maxFileSizeBytes()) {
      throw new DocumentUploadException(
          "file exceeds the maximum size of " + properties.maxFileSizeBytes() + " bytes");
    }
    String fileName = upload.fileName() == null ? "" : upload.fileName().trim();
    if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      throw new DocumentUploadException("only PDF files are accepted");
    }
    if (!hasPdfMagic(upload.content())) {
      throw new DocumentUploadException("only PDF files are accepted");
    }
  }

  private static boolean hasPdfMagic(byte[] content) {
    int limit = Math.min(content.length, MAX_PDF_MAGIC_SCAN);
    for (int i = 0; i + 5 <= limit; i++) {
      if (content[i] == '%'
          && content[i + 1] == 'P'
          && content[i + 2] == 'D'
          && content[i + 3] == 'F'
          && content[i + 4] == '-') {
        return true;
      }
    }
    return false;
  }

  private static Path resolve(Path base, UUID tenantId, UUID documentId, String rawFileName) {
    Path target =
        base.resolve(tenantId.toString())
            .resolve(documentId.toString())
            .resolve(sanitizeFileName(rawFileName))
            .normalize();
    if (!target.startsWith(base)) {
      throw new DocumentUploadException("the file name is not allowed");
    }
    return target;
  }

  /**
   * Reduces a client-supplied name to a single path segment. A client can never choose a directory:
   * separators, parent references and control characters are removed before the path is resolved
   * under the tenant/document directory.
   */
  private static String sanitizeFileName(String rawFileName) {
    String name = rawFileName == null ? "" : rawFileName.trim().replace('\\', '/');
    int slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    name = name.replaceAll("[\\p{Cntrl}<>\":|?*]", "_");
    if (name.isEmpty() || name.equals(".") || name.equals("..")) {
      throw new DocumentUploadException("a file name is required");
    }
    return name;
  }

  private static String storageKey(UUID tenantId, UUID documentId, String fileName) {
    return tenantId.toString() + "/" + documentId.toString() + "/" + fileName;
  }
}
