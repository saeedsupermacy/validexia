package com.validexia.assessments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.validexia.ai.ModelProvider;
import com.validexia.ai.ModelRequest;
import com.validexia.ai.ModelResponse;
import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import com.validexia.support.TestTokens;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end assessment flows through the real filter chain on real PostgreSQL: assessment
 * lifecycle, claim entry, evidence-grounded comparison against documents the tenant actually
 * uploaded, deterministic severity, citation verification, idempotency and the human approval
 * boundary.
 *
 * <p>The model is a programmable stub. No live provider is called.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test",
      "validexia.ai.enabled=true",
      "validexia.ai.provider=stub",
      "validexia.ai.api-key=stub-key",
      "validexia.ai.model=stub-model",
      "validexia.documents.storage-base-path=./build/test-assessment-documents"
    })
@AutoConfigureMockMvc
@Import({TestClerkConfig.class, AssessmentsFlowTest.StubAiConfig.class})
class AssessmentsFlowTest {

  /** A ModelProvider whose behaviour each test programs; it captures every request it receives. */
  static final class StubModelProvider implements ModelProvider {
    final AtomicReference<Function<ModelRequest, ModelResponse>> handler = new AtomicReference<>();
    final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
      return "stub";
    }

    @Override
    public String model() {
      return "stub-model";
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
      requests.add(request);
      Function<ModelRequest, ModelResponse> current = handler.get();
      if (current == null) {
        throw new IllegalStateException("stub handler not programmed");
      }
      return current.apply(request);
    }
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class StubAiConfig {
    @org.springframework.context.annotation.Bean
    StubModelProvider stubModelProvider() {
      return new StubModelProvider();
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired MockMvc mvc;
  @Autowired DirectoryRepository directory;
  @Autowired StubModelProvider stub;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();
  private final ObjectMapper json = new ObjectMapper();
  private final JsonMapper mapper = JsonMapper.builder().build();

  @BeforeEach
  void resetStub() {
    stub.handler.set(null);
    stub.requests.clear();
  }

  // ---- helpers -------------------------------------------------------------------

  private String token(String subject) throws Exception {
    return TestTokens.mint(
        TestClerkConfig.key(), Instant.now(), 60, Map.of("sub", subject, "sid", "s-" + subject));
  }

  private UUID tenantWithMember(String subject) throws Exception {
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Assessment Tenant', ?)",
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

  private static final String VENDOR_BODY =
      "{\"name\":\"CloudScale Infrastructure\",\"legalName\":\"CloudScale Inc.\","
          + "\"lei\":\"1234567890ABCDEFGH12\",\"jurisdiction\":\"US-DE\",\"code\":\"VND-001\","
          + "\"category\":\"Cloud & Hosting\",\"domain\":\"cloudscale.example\","
          + "\"criticality\":\"Tier 1\",\"contractStart\":\"2024-01-15\","
          + "\"contractEnd\":\"2027-01-15\",\"tieringComplete\":true}";

  /** Creates a real vendor in the tenant; returns its id. */
  private String vendor(String token, UUID tenantId, String code) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/vendors")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VENDOR_BODY.replace("VND-001", code)))
            .andExpect(status().isOk())
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  /** Builds a real, valid PDF with one text-bearing page per requested page. */
  private static byte[] pdfWithPages(int pageCount) {
    try (PDDocument document = new PDDocument()) {
      for (int i = 0; i < pageCount; i++) {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          content.beginText();
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

  /** Uploads a PDF attached to a vendor; returns the document id. Requires the vendor to exist. */
  private String document(String token, UUID tenantId, String vendorId, int pages, String fileName)
      throws Exception {
    MvcResult result =
        mvc.perform(
                multipart("/api/v1/documents")
                    .file(
                        new MockMultipartFile(
                            "file", fileName, "application/pdf", pdfWithPages(pages)))
                    .param("vendorId", vendorId)
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processingStatus").value("Ready"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  /** Uploads a PDF with no vendor attached; returns the document id. */
  private String documentWithoutVendor(String token, UUID tenantId, String fileName)
      throws Exception {
    MvcResult result =
        mvc.perform(
                multipart("/api/v1/documents")
                    .file(
                        new MockMultipartFile("file", fileName, "application/pdf", pdfWithPages(1)))
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  /** Uploads bytes that pass storage validation but fail extraction; returns the document id. */
  private String failedDocument(String token, UUID tenantId, String vendorId) throws Exception {
    mvc.perform(
            multipart("/api/v1/documents")
                .file(
                    new MockMultipartFile(
                        "file", "broken.pdf", "application/pdf", "%PDF-1.7".getBytes()))
                .param("vendorId", vendorId)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isUnprocessableEntity());
    MvcResult list =
        mvc.perform(
                get("/api/v1/documents")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andReturn();
    return mapper.readTree(list.getResponse().getContentAsString()).get(0).get("id").asText();
  }

  private String assessment(String token, UUID tenantId, String vendorId) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/assessments")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"vendorId\":\"" + vendorId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Draft"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private void claims(String token, UUID tenantId, String assessmentId) throws Exception {
    mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/claims")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"claims\":[{\"questionText\":\"Is ISO 27001 certified?\","
                        + "\"answerText\":\"Yes, across all production regions.\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  /** Programs the stub to answer every comparison with the given citation. */
  private void compareAnswer(String documentId, int pageNumber, String quote, String findingType) {
    stub.handler.set(
        request -> {
          ObjectNode structured = json.createObjectNode();
          structured.put("findingType", findingType);
          structured.put("title", "Citation title");
          structured.put("explanation", "Citation explanation");
          ArrayNode ids = structured.putArray("citationDocumentIds");
          ids.add(documentId);
          ArrayNode numbers = structured.putArray("citationPageNumbers");
          numbers.add(String.valueOf(pageNumber));
          ArrayNode quotes = structured.putArray("citationQuotes");
          quotes.add(quote);
          return new ModelResponse("stub", "stub-model", null, structured, 1, 1, 1);
        });
  }

  /** Programs the stub to answer with no citation at all. */
  private void compareAnswerWithoutCitation(String findingType) {
    stub.handler.set(
        request -> {
          ObjectNode structured = json.createObjectNode();
          structured.put("findingType", findingType);
          structured.put("title", "No citation");
          structured.put("explanation", "Nothing to cite");
          structured.putArray("citationDocumentIds");
          structured.putArray("citationPageNumbers");
          structured.putArray("citationQuotes");
          return new ModelResponse("stub", "stub-model", null, structured, 1, 1, 1);
        });
  }

  private org.springframework.test.web.servlet.ResultActions compare(
      String token, UUID tenantId, String assessmentId, String documentId) throws Exception {
    return mvc.perform(
        post("/api/v1/assessments/" + assessmentId + "/compare")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", tenantId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"passages\":[{\"documentId\":\"" + documentId + "\",\"pageNumber\":1}]}"));
  }

  private int setStatus(String token, UUID tenantId, String assessmentId, String status)
      throws Exception {
    return mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/status")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private int findingStatus(String token, UUID tenantId, String assessmentId, String body)
      throws Exception {
    return mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/findings")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private JsonNode findings(String token, UUID tenantId, String assessmentId) throws Exception {
    MvcResult result =
        mvc.perform(
                get("/api/v1/assessments/" + assessmentId + "/findings")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  // ---- approval boundary ----------------------------------------------------------

  @Test
  void aFindingIsPendingApprovalUntilAHumanDecides() throws Exception {
    String subject = "asm-approve-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-A1");
    String assessmentId = assessment(token, tenantId, vendorId);

    int created =
        findingStatus(
            token,
            tenantId,
            assessmentId,
            "{\"findingType\":\"Unsupported\",\"title\":\"No SOC 2 on file\","
                + "\"explanation\":\"The vendor provided no SOC 2 report.\"}");
    org.junit.jupiter.api.Assertions.assertEquals(200, created);

    JsonNode listed = findings(token, tenantId, assessmentId);
    org.junit.jupiter.api.Assertions.assertEquals(1, listed.size());
    org.junit.jupiter.api.Assertions.assertEquals(
        "Pending Approval", listed.get(0).get("status").asText());
    org.junit.jupiter.api.Assertions.assertEquals(
        "High",
        listed.get(0).get("severity").asText(),
        "severity is deterministic for Unsupported");
    String findingId = listed.get(0).get("id").asText();

    MvcResult decided =
        mvc.perform(
                post("/api/v1/assessments/" + assessmentId + "/findings/" + findingId + "/decision")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"Approved\",\"reason\":\"checked with the vendor\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Approved"))
            .andExpect(jsonPath("$.decisionReason").value("checked with the vendor"))
            .andExpect(jsonPath("$.decidedByUserId").isNotEmpty())
            .andExpect(jsonPath("$.decidedAt").isNotEmpty())
            .andReturn();
    JsonNode decision = mapper.readTree(decided.getResponse().getContentAsString());
    org.junit.jupiter.api.Assertions.assertFalse(
        decision.get("decidedByUserId").asText().isBlank(), "the deciding actor is recorded");
    org.junit.jupiter.api.Assertions.assertFalse(
        decision.get("decidedAt").asText().isBlank(), "the decision timestamp is recorded");

    // A second decision is refused: the first human decision is terminal.
    mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/findings/" + findingId + "/decision")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"Rejected\",\"reason\":\"overruled\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void tenantsCannotSeeEachOthersAssessmentsOrFindings() throws Exception {
    String subjectA = "asm-a-" + UUID.randomUUID();
    String tokenA = token(subjectA);
    UUID tenantA = tenantWithMember(subjectA);
    String vendorA = vendor(tokenA, tenantA, "VND-B1");
    String assessmentA = assessment(tokenA, tenantA, vendorA);

    String subjectB = "asm-b-" + UUID.randomUUID();
    String tokenB = token(subjectB);
    UUID tenantB = tenantWithMember(subjectB);

    mvc.perform(
            get("/api/v1/assessments")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    mvc.perform(
            get("/api/v1/assessments/" + assessmentA + "/findings")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theTenantHeaderIsRequired() throws Exception {
    String subject = "asm-noheader-" + UUID.randomUUID();
    String token = token(subject);
    mvc.perform(
            post("/api/v1/assessments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vendorId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest());
  }

  // ---- evidence provenance -------------------------------------------------------

  @Test
  void comparisonResolvesStoredPagesAndIgnoresAnythingElse() throws Exception {
    String subject = "asm-ev-ok-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-C1");
    String documentId = document(token, tenantId, vendorId, 2, "policy.pdf");
    String assessmentId = assessment(token, tenantId, vendorId);
    claims(token, tenantId, assessmentId);

    // The model cites page 1 with a quotation that IS in the stored text.
    compareAnswer(documentId, 1, "Validexia test page 1", "Supported");
    compare(token, tenantId, assessmentId, documentId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.findingCount").value(1));

    JsonNode found = findings(token, tenantId, assessmentId);
    org.junit.jupiter.api.Assertions.assertEquals(1, found.size());
    JsonNode finding = found.get(0);
    org.junit.jupiter.api.Assertions.assertEquals("Supported", finding.get("findingType").asText());
    org.junit.jupiter.api.Assertions.assertEquals(
        "Low", finding.get("severity").asText(), "severity is deterministic for Supported");
    org.junit.jupiter.api.Assertions.assertEquals(
        1, finding.get("evidencePageNumber").asInt(), "the page number is the citation coordinate");
    org.junit.jupiter.api.Assertions.assertFalse(
        finding.get("evidenceRecordId").asText().isBlank(),
        "the finding is linked to a real evidence record");
    org.junit.jupiter.api.Assertions.assertEquals(
        "Validexia test page 1",
        finding.get("evidenceQuote").asText(),
        "the quote is read back from the evidence record, not from the caller");
    org.junit.jupiter.api.Assertions.assertFalse(
        finding.get("evidenceQuoteSha256").asText().isBlank());
  }

  @Test
  void aFabricatedQuoteIsDowngradedToUnsupported() throws Exception {
    String subject = "asm-ev-fake-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-D1");
    String documentId = document(token, tenantId, vendorId, 1, "policy.pdf");
    String assessmentId = assessment(token, tenantId, vendorId);
    claims(token, tenantId, assessmentId);

    // A quotation that appears nowhere in the stored page text.
    compareAnswer(documentId, 1, "we hold a SOC 2 Type II report", "Supported");
    compare(token, tenantId, assessmentId, documentId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.findingCount").value(1));

    JsonNode found = findings(token, tenantId, assessmentId);
    org.junit.jupiter.api.Assertions.assertEquals(1, found.size());
    org.junit.jupiter.api.Assertions.assertEquals(
        "Unsupported",
        found.get(0).get("findingType").asText(),
        "an unverifiable citation must downgrade the verdict");
    org.junit.jupiter.api.Assertions.assertTrue(
        found.get(0).get("evidenceRecordId").isNull(),
        "no evidence record is stored for a failed citation");
    org.junit.jupiter.api.Assertions.assertTrue(
        found.get(0).get("explanation").asText().contains("Citation verification failed"));
  }

  @Test
  void aCitationOfAPageThatWasNotSuppliedIsRejected() throws Exception {
    String subject = "asm-ev-foreign-page-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-E1");
    String documentId = document(token, tenantId, vendorId, 2, "policy.pdf");
    String assessmentId = assessment(token, tenantId, vendorId);
    claims(token, tenantId, assessmentId);

    // The passage list only offers page 1; the model cites page 2.
    compareAnswer(documentId, 2, "Validexia test page 2", "Supported");
    compare(token, tenantId, assessmentId, documentId).andExpect(status().isOk());

    JsonNode found = findings(token, tenantId, assessmentId);
    org.junit.jupiter.api.Assertions.assertEquals(
        "Unsupported", found.get(0).get("findingType").asText());
    org.junit.jupiter.api.Assertions.assertTrue(found.get(0).get("evidenceRecordId").isNull());
  }

  @Test
  void aForeignTenantDocumentIsRejected() throws Exception {
    String subjectA = "asm-xa-" + UUID.randomUUID();
    String tokenA = token(subjectA);
    UUID tenantA = tenantWithMember(subjectA);
    String vendorA = vendor(tokenA, tenantA, "VND-F1");
    String documentA = document(tokenA, tenantA, vendorA, 1, "a.pdf");

    String subjectB = "asm-xb-" + UUID.randomUUID();
    String tokenB = token(subjectB);
    UUID tenantB = tenantWithMember(subjectB);
    String vendorB = vendor(tokenB, tenantB, "VND-F2");
    String assessmentB = assessment(tokenB, tenantB, vendorB);
    claims(tokenB, tenantB, assessmentB);

    compare(tokenB, tenantB, assessmentB, documentA).andExpect(status().isNotFound());
  }

  @Test
  void aDocumentThatIsNotReadyIsRejected() throws Exception {
    String subject = "asm-notready-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-G1");
    String documentId = failedDocument(token, tenantId, vendorId);
    String assessmentId = assessment(token, tenantId, vendorId);
    claims(token, tenantId, assessmentId);

    compare(token, tenantId, assessmentId, documentId).andExpect(status().isBadRequest());
  }

  @Test
  void aDocumentWithoutAVendorIsRejected() throws Exception {
    String subject = "asm-novendor-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-H1");
    String documentId = documentWithoutVendor(token, tenantId, "orphan.pdf");
    String assessmentId = assessment(token, tenantId, vendorId);
    claims(token, tenantId, assessmentId);

    compare(token, tenantId, assessmentId, documentId).andExpect(status().isBadRequest());
  }

  @Test
  void aDocumentOfAnotherVendorIsRejected() throws Exception {
    String subject = "asm-wrongvendor-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String ownerVendor = vendor(token, tenantId, "VND-I1");
    String otherVendor = vendor(token, tenantId, "VND-I2");
    String documentId = document(token, tenantId, otherVendor, 1, "other.pdf");
    String assessmentId = assessment(token, tenantId, ownerVendor);
    claims(token, tenantId, assessmentId);

    compare(token, tenantId, assessmentId, documentId).andExpect(status().isBadRequest());
  }

  @Test
  void anUnknownPageIsRejected() throws Exception {
    String subject = "asm-badpage-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-J1");
    String documentId = document(token, tenantId, vendorId, 1, "one.pdf");
    String assessmentId = assessment(token, tenantId, vendorId);
    claims(token, tenantId, assessmentId);

    mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/compare")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"passages\":[{\"documentId\":\"" + documentId + "\",\"pageNumber\":9}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theManualFindingPathAppliesTheSameProvenanceRules() throws Exception {
    String subject = "asm-manual-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-K1");
    String documentId = document(token, tenantId, vendorId, 1, "policy.pdf");
    String assessmentId = assessment(token, tenantId, vendorId);

    // A quotation that is present in the stored text is accepted and linked to an evidence record.
    org.junit.jupiter.api.Assertions.assertEquals(
        200,
        findingStatus(
            token,
            tenantId,
            assessmentId,
            "{\"findingType\":\"Supported\",\"title\":\"Certified\","
                + "\"explanation\":\"On file.\",\"evidence\":{\"documentId\":\""
                + documentId
                + "\",\"pageNumber\":1,\"quote\":\"Validexia test page 1\"}}"));

    // A fabricated quotation on the same document is rejected and stores nothing.
    org.junit.jupiter.api.Assertions.assertEquals(
        400,
        findingStatus(
            token,
            tenantId,
            assessmentId,
            "{\"findingType\":\"Supported\",\"title\":\"Fabricated\","
                + "\"explanation\":\"Not on file.\",\"evidence\":{\"documentId\":\""
                + documentId
                + "\",\"pageNumber\":1,\"quote\":\"we hold a SOC 2 Type II report\"}}"));
  }

  @Test
  void findingCreationIsIdempotent() throws Exception {
    String subject = "asm-idem-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-L1");
    String assessmentId = assessment(token, tenantId, vendorId);
    String body =
        "{\"findingType\":\"Unsupported\",\"title\":\"Duplicate me\","
            + "\"explanation\":\"Same natural key.\"}";

    org.junit.jupiter.api.Assertions.assertEquals(
        200, findingStatus(token, tenantId, assessmentId, body));
    MvcResult second =
        mvc.perform(
                post("/api/v1/assessments/" + assessmentId + "/findings")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode repeated = mapper.readTree(second.getResponse().getContentAsString());

    JsonNode listed = findings(token, tenantId, assessmentId);
    org.junit.jupiter.api.Assertions.assertEquals(
        1, listed.size(), "a repeated natural key must not create a second finding");
    org.junit.jupiter.api.Assertions.assertEquals(
        listed.get(0).get("id").asText(), repeated.get("findingId").asText());
  }

  // ---- lifecycle and validation --------------------------------------------------

  @Test
  void assessmentLifecycleIsEnforced() throws Exception {
    String subject = "asm-life-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-M1");
    String assessmentId = assessment(token, tenantId, vendorId);

    mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/status")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"In Review\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("In Review"));

    mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/status")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"Approved\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("Approved"));

    // Approved is terminal.
    mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/status")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"Draft\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidRequestsAreRejected() throws Exception {
    String subject = "asm-invalid-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-N1");
    String assessmentId = assessment(token, tenantId, vendorId);

    mvc.perform(
            post("/api/v1/assessments")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/api/v1/assessments")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vendorId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/status")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"Nonsense\"}"))
        .andExpect(status().isBadRequest());

    org.junit.jupiter.api.Assertions.assertEquals(
        400,
        findingStatus(
            token,
            tenantId,
            assessmentId,
            "{\"findingType\":\"Nonsense\",\"title\":\"t\",\"explanation\":\"e\"}"));

    mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/claims")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"claims\":[{\"questionText\":\"\",\"answerText\":\"\"}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void everySeverityBranchIsDeterministic() throws Exception {
    String subject = "asm-sev-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-O1");
    String assessmentId = assessment(token, tenantId, vendorId);

    String[][] cases = {
      {"Contradictory", "Critical"},
      {"Unsupported", "High"},
      {"Missing", "Medium"},
      {"Supported", "Low"},
    };
    for (String[] item : cases) {
      MvcResult result =
          mvc.perform(
                  post("/api/v1/assessments/" + assessmentId + "/findings")
                      .header("Authorization", "Bearer " + token)
                      .header("X-Tenant-Id", tenantId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"findingType\":\""
                              + item[0]
                              + "\",\"title\":\"t-"
                              + item[0]
                              + "\",\"explanation\":\"e\"}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.severity").value(item[1]))
              .andReturn();
      JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
      org.junit.jupiter.api.Assertions.assertEquals(
          item[1], body.get("severity").asText(), item[0] + " must map to " + item[1]);
    }
  }

  // ---- Fix A: the permitted assessment lifecycle, enforced in the database ----------------

  @Test
  void onlyThePermittedAssessmentTransitionsSucceed() throws Exception {
    String subject = "asm-transitions-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);

    // Draft -> In Review, and Draft -> Rejected.
    String draftToReview = assessment(token, tenantId, vendor(token, tenantId, "VND-T1"));
    org.junit.jupiter.api.Assertions.assertEquals(
        200, setStatus(token, tenantId, draftToReview, "In Review"));
    String draftToRejected = assessment(token, tenantId, vendor(token, tenantId, "VND-T2"));
    org.junit.jupiter.api.Assertions.assertEquals(
        200, setStatus(token, tenantId, draftToRejected, "Rejected"));

    // In Review -> Approved, and In Review -> Rejected.
    String reviewToApproved = assessment(token, tenantId, vendor(token, tenantId, "VND-T3"));
    org.junit.jupiter.api.Assertions.assertEquals(
        200, setStatus(token, tenantId, reviewToApproved, "In Review"));
    org.junit.jupiter.api.Assertions.assertEquals(
        200, setStatus(token, tenantId, reviewToApproved, "Approved"));
    String reviewToRejected = assessment(token, tenantId, vendor(token, tenantId, "VND-T4"));
    org.junit.jupiter.api.Assertions.assertEquals(
        200, setStatus(token, tenantId, reviewToRejected, "In Review"));
    org.junit.jupiter.api.Assertions.assertEquals(
        200, setStatus(token, tenantId, reviewToRejected, "Rejected"));

    // Draft -> Approved is not permitted.
    String draft = assessment(token, tenantId, vendor(token, tenantId, "VND-T5"));
    org.junit.jupiter.api.Assertions.assertEquals(
        400, setStatus(token, tenantId, draft, "Approved"));

    // In Review -> Draft is not permitted.
    String inReview = assessment(token, tenantId, vendor(token, tenantId, "VND-T6"));
    org.junit.jupiter.api.Assertions.assertEquals(
        200, setStatus(token, tenantId, inReview, "In Review"));
    org.junit.jupiter.api.Assertions.assertEquals(
        400, setStatus(token, tenantId, inReview, "Draft"));

    // Approved and Rejected are terminal.
    org.junit.jupiter.api.Assertions.assertEquals(
        400, setStatus(token, tenantId, reviewToApproved, "In Review"));
    org.junit.jupiter.api.Assertions.assertEquals(
        400, setStatus(token, tenantId, reviewToApproved, "Rejected"));
    org.junit.jupiter.api.Assertions.assertEquals(
        400, setStatus(token, tenantId, draftToRejected, "In Review"));
    org.junit.jupiter.api.Assertions.assertEquals(
        400, setStatus(token, tenantId, reviewToRejected, "Approved"));

    // A no-op is not a transition.
    org.junit.jupiter.api.Assertions.assertEquals(
        400, setStatus(token, tenantId, reviewToApproved, "Approved"));
  }

  // ---- Fix B: a citation-less verdict must keep its real meaning -------------------------

  @Test
  void aCitationLessMissingVerdictStaysMissing() throws Exception {
    String subject = "asm-missing-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-U1");
    String documentId = document(token, tenantId, vendorId, 1, "policy.pdf");
    String assessmentId = assessment(token, tenantId, vendorId);
    claims(token, tenantId, assessmentId);

    compareAnswerWithoutCitation("Missing");
    compare(token, tenantId, assessmentId, documentId).andExpect(status().isOk());

    JsonNode found = findings(token, tenantId, assessmentId);
    org.junit.jupiter.api.Assertions.assertEquals(1, found.size());
    org.junit.jupiter.api.Assertions.assertEquals(
        "Missing",
        found.get(0).get("findingType").asText(),
        "no evidence is exactly what Missing means");
    org.junit.jupiter.api.Assertions.assertEquals(
        "Medium", found.get(0).get("severity").asText(), "Missing maps to Medium");
    org.junit.jupiter.api.Assertions.assertTrue(found.get(0).get("evidenceRecordId").isNull());
  }

  @Test
  void supportedWithNoCitationsBecomesUnsupported() throws Exception {
    String subject = "asm-supported-none-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = vendor(token, tenantId, "VND-U2");
    String documentId = document(token, tenantId, vendorId, 1, "policy.pdf");
    String assessmentId = assessment(token, tenantId, vendorId);
    claims(token, tenantId, assessmentId);

    compareAnswerWithoutCitation("Supported");
    compare(token, tenantId, assessmentId, documentId).andExpect(status().isOk());

    JsonNode found = findings(token, tenantId, assessmentId);
    org.junit.jupiter.api.Assertions.assertEquals(1, found.size());
    org.junit.jupiter.api.Assertions.assertEquals(
        "Unsupported",
        found.get(0).get("findingType").asText(),
        "a positive verdict cannot stand without a verified citation");
    org.junit.jupiter.api.Assertions.assertEquals(
        "High", found.get(0).get("severity").asText(), "Unsupported maps to High");
    org.junit.jupiter.api.Assertions.assertTrue(found.get(0).get("evidenceRecordId").isNull());
  }
}
