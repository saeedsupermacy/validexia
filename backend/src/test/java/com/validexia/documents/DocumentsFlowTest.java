package com.validexia.documents;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import com.validexia.support.TestTokens;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end document flows through the real filter chain: upload a PDF, extract its pages, record
 * an evidence citation, and confirm a second tenant sees none of it.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test",
      "validexia.documents.storage-base-path=./build/test-documents"
    })
@AutoConfigureMockMvc
@Import(TestClerkConfig.class)
class DocumentsFlowTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired MockMvc mvc;
  @Autowired DirectoryRepository directory;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  private static final JsonMapper json = JsonMapper.builder().build();

  private String token(String subject) throws Exception {
    return TestTokens.mint(
        TestClerkConfig.key(), Instant.now(), 60, Map.of("sub", subject, "sid", "s-" + subject));
  }

  private UUID tenantWithMember(String subject) throws Exception {
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Document Tenant', ?)",
        tenantId,
        "t-" + tenantId);
    String token = token(subject);
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    UUID userId = directory.findUser(subject).orElseThrow().userId();
    admin.update(
        "INSERT INTO public.memberships (user_id, tenant_id, role) VALUES (?, ?, 'admin')",
        userId,
        tenantId);
    return tenantId;
  }

  /** Builds a real, valid PDF with one text-bearing page per requested page. */
  private static byte[] pdfWithPages(int pageCount) {
    try (PDDocument document = new PDDocument()) {
      for (int i = 0; i < pageCount; i++) {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          content.beginText();
          // PDFBox 3 removed the PDType1Font.HELVETICA_BOLD constants; standard 14 fonts are
          // constructed from Standard14Fonts.FontName.
          content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
          content.newLineAtOffset(72, 700);
          content.showText("Validexia test page " + (i + 1));
          content.endText();
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("could not build a test PDF", e);
    }
  }

  private String upload(String token, UUID tenantId, byte[] pdf, String fileName) throws Exception {
    return mvc.perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", fileName, "application/pdf", pdf))
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processingStatus").value("Ready"))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void uploadExtractsPagesAndRecordsEvidence() throws Exception {
    String subject = "doc-flow-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);

    String body = upload(token, tenantId, pdfWithPages(3), "questionnaire.pdf");
    String documentId = json.readTree(body).get("id").asText();

    // Page boundaries survived extraction: one row per page, in order, with hashes.
    mvc.perform(
            get("/api/v1/documents/" + documentId + "/pages")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].pageNumber").value(1))
        .andExpect(jsonPath("$[2].pageNumber").value(3))
        .andExpect(
            jsonPath("$[0].textContent").value(org.hamcrest.Matchers.containsString("test page 1")))
        .andExpect(jsonPath("$[0].textSha256").isNotEmpty());

    // An evidence citation is anchored to a page.
    mvc.perform(
            post("/api/v1/documents/" + documentId + "/evidence")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType("application/json")
                .content("{\"pageNumber\":1,\"quoteText\":\"Validexia test page 1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pageNumber").value(1))
        .andExpect(jsonPath("$.quoteSha256").isNotEmpty());

    mvc.perform(
            get("/api/v1/documents/" + documentId + "/evidence")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void tenantsCannotSeeEachOthersDocuments() throws Exception {
    String subjectA = "doc-a-" + UUID.randomUUID();
    String tokenA = token(subjectA);
    UUID tenantA = tenantWithMember(subjectA);
    String body = upload(tokenA, tenantA, pdfWithPages(1), "a.pdf");
    String documentId = json.readTree(body).get("id").asText();

    String subjectB = "doc-b-" + UUID.randomUUID();
    String tokenB = token(subjectB);
    UUID tenantB = tenantWithMember(subjectB);

    mvc.perform(
            get("/api/v1/documents")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    mvc.perform(
            get("/api/v1/documents/" + documentId)
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isNotFound());

    mvc.perform(
            get("/api/v1/documents/" + documentId + "/pages")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isNotFound());
  }

  @Test
  void nonPdfContentIsRejectedEvenWithAPdfFileName() throws Exception {
    String subject = "doc-notpdf-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);

    mvc.perform(
            multipart("/api/v1/documents")
                .file(
                    new MockMultipartFile(
                        "file", "fake.pdf", "application/pdf", "this is not a PDF".getBytes()))
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theTenantHeaderIsRequired() throws Exception {
    String subject = "doc-noheader-" + UUID.randomUUID();
    String token = token(subject);

    mvc.perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "q.pdf", "application/pdf", pdfWithPages(1)))
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  // ---- M2: evidence page-reference validation ----------------------------------------

  /** Posts one evidence citation and returns the HTTP status, for assertion. */
  private int evidenceStatus(String token, UUID tenantId, String documentId, String body)
      throws Exception {
    return mvc.perform(
            post("/api/v1/documents/" + documentId + "/evidence")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType("application/json")
                .content(body))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private JsonNode pagesOf(String token, UUID tenantId, String documentId) throws Exception {
    String body =
        mvc.perform(
                get("/api/v1/documents/" + documentId + "/pages")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body);
  }

  private String documentId(String token, UUID tenantId, int pages, String fileName)
      throws Exception {
    return json.readTree(upload(token, tenantId, pdfWithPages(pages), fileName)).get("id").asText();
  }

  @Test
  void evidenceAcceptsValidAndUnanchoredPageReferences() throws Exception {
    String subject = "doc-ev-ok-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String documentId = documentId(token, tenantId, 3, "ev.pdf");
    JsonNode pages = pagesOf(token, tenantId, documentId);
    String page1 = pages.get(0).get("id").asText();
    String page2 = pages.get(1).get("id").asText();

    org.junit.jupiter.api.Assertions.assertEquals(
        200,
        evidenceStatus(
            token, tenantId, documentId, "{\"pageNumber\":2,\"quoteText\":\"by number\"}"));
    org.junit.jupiter.api.Assertions.assertEquals(
        200,
        evidenceStatus(
            token, tenantId, documentId, "{\"pageId\":\"" + page1 + "\",\"quoteText\":\"by id\"}"));
    org.junit.jupiter.api.Assertions.assertEquals(
        200,
        evidenceStatus(
            token,
            tenantId,
            documentId,
            "{\"pageId\":\"" + page2 + "\",\"pageNumber\":2,\"quoteText\":\"both agree\"}"));
    // Unanchored evidence stays legal.
    org.junit.jupiter.api.Assertions.assertEquals(
        200, evidenceStatus(token, tenantId, documentId, "{\"quoteText\":\"unanchored\"}"));
  }

  @Test
  void evidenceRejectsFabricatedPageReferences() throws Exception {
    String subject = "doc-ev-bad-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String documentId = documentId(token, tenantId, 2, "ev.pdf");
    String page1 = pagesOf(token, tenantId, documentId).get(0).get("id").asText();

    // Out-of-range and non-positive page numbers.
    org.junit.jupiter.api.Assertions.assertEquals(
        400,
        evidenceStatus(token, tenantId, documentId, "{\"pageNumber\":999,\"quoteText\":\"x\"}"));
    org.junit.jupiter.api.Assertions.assertEquals(
        400, evidenceStatus(token, tenantId, documentId, "{\"pageNumber\":0,\"quoteText\":\"x\"}"));
    org.junit.jupiter.api.Assertions.assertEquals(
        400,
        evidenceStatus(token, tenantId, documentId, "{\"pageNumber\":-1,\"quoteText\":\"x\"}"));
    // Unknown page id.
    org.junit.jupiter.api.Assertions.assertEquals(
        400,
        evidenceStatus(
            token,
            tenantId,
            documentId,
            "{\"pageId\":\"" + UUID.randomUUID() + "\",\"quoteText\":\"x\"}"));
    // Id and number name different pages.
    org.junit.jupiter.api.Assertions.assertEquals(
        400,
        evidenceStatus(
            token,
            tenantId,
            documentId,
            "{\"pageId\":\"" + page1 + "\",\"pageNumber\":2,\"quoteText\":\"x\"}"));
  }

  @Test
  void evidenceCannotReferenceAnotherDocumentsPage() throws Exception {
    String subject = "doc-ev-xdoc-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String docA = documentId(token, tenantId, 2, "a.pdf");
    String docB = documentId(token, tenantId, 1, "b.pdf");
    String pageA = pagesOf(token, tenantId, docA).get(0).get("id").asText();

    // A page id owned by document A.
    org.junit.jupiter.api.Assertions.assertEquals(
        400,
        evidenceStatus(
            token, tenantId, docB, "{\"pageId\":\"" + pageA + "\",\"quoteText\":\"x\"}"));
    // Page 2 exists in A but not in the single-page B.
    org.junit.jupiter.api.Assertions.assertEquals(
        400, evidenceStatus(token, tenantId, docB, "{\"pageNumber\":2,\"quoteText\":\"x\"}"));
  }

  @Test
  void crossTenantEvidenceCreationIsRejected() throws Exception {
    String subjectA = "doc-ev-a-" + UUID.randomUUID();
    String tokenA = token(subjectA);
    UUID tenantA = tenantWithMember(subjectA);
    String documentId = documentId(tokenA, tenantA, 1, "a.pdf");

    String subjectB = "doc-ev-b-" + UUID.randomUUID();
    String tokenB = token(subjectB);
    UUID tenantB = tenantWithMember(subjectB);

    // Tenant B cannot cite tenant A's document, anchored or not.
    org.junit.jupiter.api.Assertions.assertEquals(
        404, evidenceStatus(tokenB, tenantB, documentId, "{\"pageNumber\":1,\"quoteText\":\"x\"}"));
    org.junit.jupiter.api.Assertions.assertEquals(
        404, evidenceStatus(tokenB, tenantB, documentId, "{\"quoteText\":\"x\"}"));
  }

  // ---- Finding 1: evidence requires a Ready document ----------------------------------

  /**
   * Uploads bytes that pass storage validation (a {@code .pdf} name and the {@code %PDF-} magic
   * header) but cannot be parsed, so the service persists the document as {@code Failed} and
   * returns 422. Returns the failed document's id, read back from the tenant's document list.
   */
  private String failedDocumentId(String token, UUID tenantId, byte[] pdf, String fileName)
      throws Exception {
    mvc.perform(
            multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", fileName, "application/pdf", pdf))
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DOCUMENT_EXTRACTION_FAILED"));
    String list =
        mvc.perform(
                get("/api/v1/documents")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(list).get(0).get("id").asText();
  }

  /** A payload that passes storage validation but is not a parseable PDF. */
  private static byte[] unparseablePdf() {
    return "%PDF-1.7".getBytes();
  }

  @Test
  void aFailedPdfIsPersistedAndRejectedByTheApi() throws Exception {
    String subject = "doc-failed-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);

    String documentId = failedDocumentId(token, tenantId, unparseablePdf(), "broken.pdf");

    // The failure is retained as an audit artifact: the row exists, is Failed, and has no pages.
    mvc.perform(
            get("/api/v1/documents/" + documentId)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processingStatus").value("Failed"))
        .andExpect(jsonPath("$.pageCount").value(0));

    mvc.perform(
            get("/api/v1/documents/" + documentId + "/pages")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void evidenceAgainstAFailedDocumentIsRejected() throws Exception {
    String subject = "doc-failed-ev-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String documentId = failedDocumentId(token, tenantId, unparseablePdf(), "broken.pdf");

    // Unanchored evidence is the case that previously succeeded.
    org.junit.jupiter.api.Assertions.assertEquals(
        400, evidenceStatus(token, tenantId, documentId, "{\"quoteText\":\"unanchored\"}"));
    // Anchored evidence was already rejected because a failed document has no pages.
    org.junit.jupiter.api.Assertions.assertEquals(
        400, evidenceStatus(token, tenantId, documentId, "{\"pageNumber\":1,\"quoteText\":\"x\"}"));

    // Neither attempt stored anything.
    mvc.perform(
            get("/api/v1/documents/" + documentId + "/evidence")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void crossTenantAccessToAFailedDocumentIsStillNotFound() throws Exception {
    String subjectA = "doc-failed-a-" + UUID.randomUUID();
    String tokenA = token(subjectA);
    UUID tenantA = tenantWithMember(subjectA);
    String documentId = failedDocumentId(tokenA, tenantA, unparseablePdf(), "broken.pdf");

    String subjectB = "doc-failed-b-" + UUID.randomUUID();
    String tokenB = token(subjectB);
    UUID tenantB = tenantWithMember(subjectB);

    // The tenant check runs before the status check, so a foreign Failed document is still a 404.
    org.junit.jupiter.api.Assertions.assertEquals(
        404, evidenceStatus(tokenB, tenantB, documentId, "{\"quoteText\":\"x\"}"));
    org.junit.jupiter.api.Assertions.assertEquals(
        404, evidenceStatus(tokenB, tenantB, documentId, "{\"pageNumber\":1,\"quoteText\":\"x\"}"));
    mvc.perform(
            get("/api/v1/documents/" + documentId)
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isNotFound());
  }

  @Test
  void evidenceAgainstAReadyDocumentStillSucceeds() throws Exception {
    String subject = "doc-ready-ev-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String documentId = documentId(token, tenantId, 1, "ready.pdf");

    org.junit.jupiter.api.Assertions.assertEquals(
        200, evidenceStatus(token, tenantId, documentId, "{\"quoteText\":\"unanchored\"}"));
    org.junit.jupiter.api.Assertions.assertEquals(
        200,
        evidenceStatus(
            token, tenantId, documentId, "{\"pageNumber\":1,\"quoteText\":\"anchored\"}"));
  }
}
