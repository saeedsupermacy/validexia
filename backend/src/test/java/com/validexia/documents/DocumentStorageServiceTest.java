package com.validexia.documents;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.documents.application.DocumentData.UploadCommand;
import com.validexia.documents.application.DocumentStorageProperties;
import com.validexia.documents.application.DocumentUploadException;
import com.validexia.documents.infrastructure.DocumentStorageService;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The storage boundary (M1's companion): byte ceiling, PDF signature, filename sanitisation and
 * path containment. Pure unit tests against a real temporary directory.
 */
class DocumentStorageServiceTest {

  private final UUID tenantId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();

  private static byte[] pdf() {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("could not build a test PDF", e);
    }
  }

  private DocumentStorageService service(Path base, long maxFileSizeBytes) {
    return new DocumentStorageService(
        new DocumentStorageProperties(base.toString(), maxFileSizeBytes, 2_000));
  }

  private UploadCommand upload(String fileName, byte[] content) {
    return new UploadCommand(fileName, "application/pdf", content.length, content);
  }

  @Test
  void aValidPdfIsStoredUnderItsTenantDirectory(@TempDir Path base) {
    String key = service(base, 20_000_000).store(tenantId, documentId, upload("q.pdf", pdf()));
    assertTrue(key.startsWith(tenantId + "/" + documentId + "/"));
    assertTrue(Files.exists(base.resolve(key)));
  }

  @Test
  void contentThatIsNotAPdfIsRejected(@TempDir Path base) {
    assertThrows(
        DocumentUploadException.class,
        () ->
            service(base, 20_000_000)
                .store(tenantId, documentId, upload("fake.pdf", "nope".getBytes())));
  }

  @Test
  void aFileAboveTheByteCeilingIsRejectedBeforeItIsWritten(@TempDir Path base) {
    byte[] content = pdf();
    assertThrows(
        DocumentUploadException.class,
        () ->
            service(base, content.length - 1)
                .store(tenantId, documentId, upload("big.pdf", content)));
    assertTrue(Files.notExists(base.resolve(tenantId.toString())));
  }

  @Test
  void anEmptyUploadIsRejected(@TempDir Path base) {
    assertThrows(
        DocumentUploadException.class,
        () ->
            service(base, 20_000_000)
                .store(tenantId, documentId, upload("empty.pdf", new byte[0])));
  }

  @Test
  void aNonPdfExtensionIsRejected(@TempDir Path base) {
    assertThrows(
        DocumentUploadException.class,
        () -> service(base, 20_000_000).store(tenantId, documentId, upload("q.txt", pdf())));
  }

  @Test
  void pathTraversalAndControlCharactersAreReducedToASingleSegment(@TempDir Path base) {
    String key =
        service(base, 20_000_000)
            .store(tenantId, documentId, upload("../../etc/passwd\u0000.pdf", pdf()));
    String fileName = key.substring(key.lastIndexOf('/') + 1);
    assertTrue(fileName.endsWith(".pdf"));
    assertTrue(!fileName.contains("/"));
    assertTrue(!fileName.contains(".."));
    assertTrue(Files.exists(base.resolve(key)));
    // Nothing escaped the configured base directory.
    assertTrue(base.resolve(key).normalize().startsWith(base.normalize()));
  }

  @Test
  void aBlankFileNameIsRejected(@TempDir Path base) {
    assertThrows(
        DocumentUploadException.class,
        () -> service(base, 20_000_000).store(tenantId, documentId, upload("   ", pdf())));
  }

  @Test
  void aSecondUploadCannotOverwriteTheFirst(@TempDir Path base) {
    DocumentStorageService service = service(base, 20_000_000);
    service.store(tenantId, documentId, upload("q.pdf", pdf()));
    // Same tenant and document id, same file name: CREATE_NEW must refuse rather than overwrite.
    assertThrows(
        DocumentUploadException.class,
        () -> service.store(tenantId, documentId, upload("q.pdf", pdf())));
  }
}
