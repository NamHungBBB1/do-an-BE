package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.TransactionRepository;
import com.doan.game.service.OutgoingMail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.payos.PayOS;
import vn.payos.crypto.CryptoProviderImpl;
import vn.payos.exception.PayOSException;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkResponse;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkStatus;
import vn.payos.model.webhooks.Webhook;
import vn.payos.model.webhooks.WebhookData;
import vn.payos.service.blocking.v2.paymentRequests.PaymentRequestsService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Payment flow over real HTTP (MockMvc + H2) with a FAKE PayOS: never calls the real gateway.
 *
 * The PayOS client is a REAL {@link PayOS} built with a test checksum key, except that {@code paymentRequests()}
 * (create / get / cancel — the calls that go over the network) returns a Mockito mock. {@code webhooks().verify()}
 * is therefore the SDK's real signature check: a webhook signed with the test key passes, one signed with another key
 * or tampered after signing is rejected. Signatures are produced with the SDK's own {@link CryptoProviderImpl}
 * (the same HMAC-SHA256 PayOS uses).
 *
 * Receipt mail is caught by the real {@code AuthFlowTest.MailCatcher} listener (not a mocked MailService, see
 * AuthFlowTest for why). Real clock: plan dates are checked against today in Vietnam time.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        // The background sweep must not reconcile while a test is stubbing PayOS.
        "app.payos.sweep-ms=86400000"})
@AutoConfigureMockMvc
class PaymentFlowTest {

    static final String CHECKSUM_KEY = "test-checksum-key-not-a-secret";
    private static final Pattern OTP_IN_MAIL = Pattern.compile(">(\\d{6})</p>");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PRICE = 2000;

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired TransactionRepository transactionRepo;
    @Autowired AuthFlowTest.MailCatcher mailCatcher;
    @MockitoBean PaymentRequestsService payosRequests;

    @BeforeEach
    void setUp() throws Exception {
        mailCatcher.sent.clear();
        // Other classes share this H2 and may have changed the PARENT price: set it every time.
        mvc.perform(put("/api/admin/plans/parent").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": " + PRICE + ", \"months\": 3}"))
                .andExpect(status().isOk());
        when(payosRequests.create(any(CreatePaymentLinkRequest.class))).thenAnswer(inv -> {
            CreatePaymentLinkRequest r = inv.getArgument(0);
            return CreatePaymentLinkResponse.builder()
                    .paymentLinkId("link-" + r.getOrderCode()).orderCode(r.getOrderCode()).amount(r.getAmount())
                    .qrCode("000201010212-fake-qr").bin("970422").accountNumber("0001112223")
                    .accountName("FINTEEN TEST").description(r.getDescription())
                    .currency("VND").checkoutUrl("https://pay.test.local/" + r.getOrderCode())
                    .expiredAt(r.getExpiredAt()).status(PaymentLinkStatus.PENDING).build();
        });
        // GET /api/payments/{orderCode} asks PayOS while PENDING: by default the link is still waiting.
        when(payosRequests.get(anyLong())).thenAnswer(inv -> link(inv.getArgument(0), PaymentLinkStatus.PENDING));
    }

    // ------------------------------------------------------------------ WF-1 end to end
    /** WF-1 / T1 / T2: buy → PENDING with QR → signed webhook → PAID, plan granted and usable, receipt mail sent. */
    @Test
    void buyThenSignedWebhookPaysGrantsPlanAndSendsReceipt() throws Exception {
        Buyer buyer = buyer("wf1");

        // A fresh buyer cannot open a family group yet: no plan.
        mvc.perform(openFamilyGroup(buyer.token())).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(3005));

        long orderCode = buy(buyer.token());
        mvc.perform(get("/api/payments/" + orderCode).header("Authorization", "Bearer " + buyer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("PENDING"))
                .andExpect(jsonPath("$.result.paidAt").doesNotExist());
        assertThat(entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(buyer.id())).isEmpty();

        mailCatcher.sent.clear();
        mvc.perform(webhook(signedWebhook(orderCode, PRICE, "00", CHECKSUM_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/api/payments/" + orderCode).header("Authorization", "Bearer " + buyer.token()))
                .andExpect(jsonPath("$.result.status").value("PAID"))
                .andExpect(jsonPath("$.result.amount").value(PRICE))
                .andExpect(jsonPath("$.result.paidAt").isNotEmpty());
        List<Entitlement> plans = entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(buyer.id());
        assertThat(plans).singleElement().satisfies(e -> {
            assertThat(e.getKind()).isEqualTo(PlanKind.PARENT);
            assertThat(e.getSource()).isEqualTo(EntitlementSource.PAYMENT);
            LocalDate today = LocalDate.now(ClockConfig.VN);
            assertThat(e.getStartsOn()).isEqualTo(today);
            assertThat(e.getExpiresOn()).isEqualTo(today.plusMonths(3).minusDays(1));
        });

        // Receipt mail went to the buyer after commit.
        assertThat(mailCatcher.sent).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo(buyer.email());
            assertThat(m.subject()).contains("Biên nhận").contains(String.valueOf(orderCode));
        });

        // Plan usable with the SAME token (scope is recomputed per request): /me lists it and a group opens.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + buyer.token()))
                .andExpect(jsonPath("$.result.plans[0]").value("PARENT"));
        mvc.perform(openFamilyGroup(buyer.token())).andExpect(status().isOk());

        // NFR-5 over HTTP: PayOS delivers the same webhook again → still one grant, no second receipt.
        mailCatcher.sent.clear();
        mvc.perform(webhook(signedWebhook(orderCode, PRICE, "00", CHECKSUM_KEY))).andExpect(status().isOk());
        assertThat(entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(buyer.id())).hasSize(1);
        assertThat(mailCatcher.sent).isEmpty();
    }

    // ------------------------------------------------------------------ signature
    /** NFR-5 "the gateway signature is verified": wrong key or tampered body → ignored (still 200), order stays PENDING. */
    @Test
    void webhookWithBadSignatureIsIgnored() throws Exception {
        Buyer buyer = buyer("badsig");
        long orderCode = buy(buyer.token());

        // Signed with a key that is not ours.
        mvc.perform(webhook(signedWebhook(orderCode, PRICE, "00", "someone-elses-key")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // Signed with our key, then the amount is changed in transit.
        JsonNode tampered = MAPPER.readTree(signedWebhook(orderCode, PRICE, "00", CHECKSUM_KEY));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.path("data")).put("amount", PRICE + 1);
        mvc.perform(webhook(tampered.toString())).andExpect(status().isOk());
        // No signature at all.
        JsonNode unsigned = MAPPER.readTree(signedWebhook(orderCode, PRICE, "00", CHECKSUM_KEY));
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsigned).put("signature", "");
        mvc.perform(webhook(unsigned.toString())).andExpect(status().isOk());

        assertThat(transactionRepo.findByOrderCode(orderCode).orElseThrow().getStatus().name()).isEqualTo("PENDING");
        assertThat(entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(buyer.id())).isEmpty();
        assertThat(mailCatcher.sent).isEmpty();

        // Control: the same order with a correct signature is accepted, so the rejections above were the signature.
        mvc.perform(webhook(signedWebhook(orderCode, PRICE, "00", CHECKSUM_KEY))).andExpect(status().isOk());
        assertThat(transactionRepo.findByOrderCode(orderCode).orElseThrow().getStatus().name()).isEqualTo("PAID");
    }

    // ------------------------------------------------------------------ cancel
    /** T3: cancel a PENDING order → PayOS cancel called, FAILED; cancel again → 4006 (rejected transition). */
    @Test
    void cancelPendingOrderFailsItAndSecondCancelIs4006() throws Exception {
        Buyer buyer = buyer("cancel");
        long orderCode = buy(buyer.token());

        mvc.perform(post("/api/payments/" + orderCode + "/cancel").param("reason", "doi y")
                        .header("Authorization", "Bearer " + buyer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(payosRequests).cancel(orderCode, "doi y");
        mvc.perform(get("/api/payments/" + orderCode).header("Authorization", "Bearer " + buyer.token()))
                .andExpect(jsonPath("$.result.status").value("FAILED"));

        mvc.perform(post("/api/payments/" + orderCode + "/cancel").header("Authorization", "Bearer " + buyer.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(4006));
        // Someone else's order: 3004, not 4006.
        Buyer stranger = buyer("cancelother");
        mvc.perform(post("/api/payments/" + orderCode + "/cancel").header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
    }

    // ------------------------------------------------------------------ reconcile
    /** T4 / T5: PayOS says the link EXPIRED (buyer polls) or FAILED / CANCELLED (admin reconciles) → FAILED, no plan. */
    @Test
    void expiredOrFailedLinkTurnsOrderFailed() throws Exception {
        Buyer buyer = buyer("expire");
        long expired = buy(buyer.token());
        when(payosRequests.get(expired)).thenReturn(link(expired, PaymentLinkStatus.EXPIRED));
        mvc.perform(get("/api/payments/" + expired).header("Authorization", "Bearer " + buyer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("FAILED"));

        Buyer other = buyer("failed");
        long failed = buy(other.token());
        when(payosRequests.get(failed)).thenReturn(link(failed, PaymentLinkStatus.FAILED));
        mvc.perform(post("/api/admin/transactions/" + failed + "/reconcile").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("FAILED"));

        Buyer third = buyer("cancelled");
        long cancelled = buy(third.token());
        when(payosRequests.get(cancelled)).thenReturn(link(cancelled, PaymentLinkStatus.CANCELLED));
        mvc.perform(post("/api/admin/transactions/" + cancelled + "/reconcile").with(admin()))
                .andExpect(jsonPath("$.result.status").value("FAILED"));

        // Still waiting at PayOS → stays PENDING.
        Buyer fourth = buyer("stillpending");
        long pending = buy(fourth.token());
        when(payosRequests.get(pending)).thenReturn(link(pending, PaymentLinkStatus.PENDING));
        mvc.perform(post("/api/admin/transactions/" + pending + "/reconcile").with(admin()))
                .andExpect(jsonPath("$.result.status").value("PENDING"));

        assertThat(entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(buyer.id())).isEmpty();
        assertThat(entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(other.id())).isEmpty();
        // Unknown order for the admin: 4005.
        mvc.perform(post("/api/admin/transactions/1/reconcile").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4005));
    }

    /** T2 via reconcile: the webhook never came, admin reconcile sees PAID → plan granted once. */
    @Test
    void adminReconcileOfPaidLinkGrantsPlan() throws Exception {
        Buyer buyer = buyer("recpaid");
        long orderCode = buy(buyer.token());
        when(payosRequests.get(orderCode)).thenReturn(link(orderCode, PaymentLinkStatus.PAID));

        mvc.perform(post("/api/admin/transactions/" + orderCode + "/reconcile").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("PAID"));
        mvc.perform(post("/api/admin/transactions/" + orderCode + "/reconcile").with(admin()))
                .andExpect(jsonPath("$.result.status").value("PAID"));
        assertThat(entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(buyer.id())).hasSize(1);
    }

    // ------------------------------------------------------------------ admin list, needs review
    /** ADM-1: admin transaction list shows the order; an amount mismatch is held for review (needsReview + note), no plan. */
    @Test
    void amountMismatchIsHeldForReviewInAdminList() throws Exception {
        Buyer buyer = buyer("review");
        long orderCode = buy(buyer.token());

        mvc.perform(webhook(signedWebhook(orderCode, PRICE - 1000, "00", CHECKSUM_KEY))).andExpect(status().isOk());

        assertThat(entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(buyer.id())).isEmpty();
        String row = "$.result[?(@.orderCode == " + orderCode + ")]";
        mvc.perform(get("/api/admin/transactions").param("status", "PENDING").param("size", "100").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(row + ".status").value("PENDING"))
                .andExpect(jsonPath(row + ".needsReview").value(true))
                .andExpect(jsonPath(row + ".email").value(buyer.email()))
                .andExpect(jsonPath(row + ".note").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("1000"))));
        // Filter by another status: the order is not there.
        mvc.perform(get("/api/admin/transactions").param("status", "PAID").param("size", "100").with(admin()))
                .andExpect(jsonPath(row).isEmpty());
        // A regular account cannot read the admin list.
        mvc.perform(get("/api/admin/transactions").header("Authorization", "Bearer " + buyer.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
    }

    // ------------------------------------------------------------------ history
    /** ADL-1 payment history: own orders only, newest first, every status; buying again while PENDING reuses the order. */
    @Test
    void paymentHistoryListsOnlyMyOrdersNewestFirst() throws Exception {
        Buyer buyer = buyer("history");
        long first = buy(buyer.token());
        // Same kind, QR still valid → the open order comes back, PayOS is not called again.
        assertThat(buy(buyer.token())).isEqualTo(first);
        mvc.perform(post("/api/payments/" + first + "/cancel").header("Authorization", "Bearer " + buyer.token()))
                .andExpect(status().isOk());
        long second = buy(buyer.token());
        assertThat(second).isNotEqualTo(first);

        mvc.perform(get("/api/payments").header("Authorization", "Bearer " + buyer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(2))
                .andExpect(jsonPath("$.result[0].orderCode").value(second))
                .andExpect(jsonPath("$.result[0].status").value("PENDING"))
                .andExpect(jsonPath("$.result[0].purpose").value("NEW"))
                .andExpect(jsonPath("$.result[1].orderCode").value(first))
                .andExpect(jsonPath("$.result[1].status").value("FAILED"))
                .andExpect(jsonPath("$.result[1].accountId").value(buyer.id().toString()));

        Buyer stranger = buyer("historyother");
        mvc.perform(get("/api/payments").header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isEmpty());
        mvc.perform(get("/api/payments/" + first).header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
        mvc.perform(get("/api/payments")).andExpect(status().isUnauthorized());
    }

    /** PayOS refuses to create the link → 502/4004 and no PENDING order is left behind (rollback). */
    @Test
    void gatewayErrorOnCreateLeavesNoOrder() throws Exception {
        Buyer buyer = buyer("payoserr");
        doThrow(new PayOSException("down")).when(payosRequests).create(any(CreatePaymentLinkRequest.class));

        mvc.perform(post("/api/payments").header("Authorization", "Bearer " + buyer.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"PARENT\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(4004));
        mvc.perform(get("/api/payments").header("Authorization", "Bearer " + buyer.token()))
                .andExpect(jsonPath("$.result").isEmpty());
        verify(payosRequests, never()).cancel(anyLong(), anyString());
    }

    // ------------------------------------------------------------------ helpers
    private record Buyer(UUID id, String email, String token) {}

    /** POST /api/payments for PARENT; returns the orderCode after checking the QR and PENDING row. */
    private long buy(String token) throws Exception {
        String body = mvc.perform(post("/api/payments").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"PARENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.qrCode").value("000201010212-fake-qr"))
                .andExpect(jsonPath("$.result.amount").value(PRICE))
                .andExpect(jsonPath("$.result.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long orderCode = MAPPER.readTree(body).path("result").path("orderCode").asLong();
        assertThat(transactionRepo.findByOrderCode(orderCode).orElseThrow().getStatus().name()).isEqualTo("PENDING");
        return orderCode;
    }

    /** A webhook body as PayOS sends it, signed with {@code key} (HMAC-SHA256 over the sorted data fields). */
    private static String signedWebhook(long orderCode, long amount, String code, String key) throws Exception {
        WebhookData data = WebhookData.builder()
                .orderCode(orderCode).amount(amount).description("FinTeen goi phu huynh")
                .accountNumber("0001112223").reference("FT" + orderCode).transactionDateTime("2026-10-28 10:00:00")
                .currency("VND").paymentLinkId("link-" + orderCode).code(code).desc("success")
                .counterAccountBankId("").counterAccountBankName("").counterAccountName("").counterAccountNumber("")
                .virtualAccountName("").virtualAccountNumber("").build();
        String signature = new CryptoProviderImpl().createSignatureFromObj(data, key);
        return MAPPER.writeValueAsString(Webhook.builder().code("00").desc("success").success(true)
                .data(data).signature(signature).build());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder webhook(String body) {
        return post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static PaymentLink link(long orderCode, PaymentLinkStatus status) {
        boolean paid = status == PaymentLinkStatus.PAID;
        return PaymentLink.builder().id("link-" + orderCode).orderCode(orderCode).amount(PRICE)
                .amountPaid(paid ? PRICE : 0L).amountRemaining(paid ? 0L : PRICE).status(status)
                .createdAt("2026-10-28T10:00:00+07:00").transactions(List.of()).build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder openFamilyGroup(String token) {
        return post("/api/groups").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nha mua goi\",\"context\":\"FAMILY\"}");
    }

    /** Admin JWT the way AdminPlanFlowTest does it: price and transaction APIs only check SCOPE_ADMIN. */
    private RequestPostProcessor admin() {
        Account a = new Account();
        a.setEmail("pay-admin-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Admin");
        a.setCreatedAt(Instant.now());
        String id = accountRepo.save(a).getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("SCOPE_ADMIN"));
    }

    /** Register + verify through the API (OTP from the caught mail) → a real ACCOUNT token. */
    private Buyer buyer(String prefix) throws Exception {
        String email = prefix + "-" + System.nanoTime() + "@test.local";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"phone\":\"0900000000\","
                                + "\"password\":\"matkhau123\",\"displayName\":\"Nguoi Mua\"}"))
                .andExpect(status().isOk());
        OutgoingMail last = mailCatcher.sent.get(mailCatcher.sent.size() - 1);
        Matcher m = OTP_IN_MAIL.matcher(last.body());
        assertThat(m.find()).as("mail must carry a 6-digit OTP").isTrue();
        String body = mvc.perform(post("/api/auth/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"otp\":\"" + m.group(1) + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = MAPPER.readTree(body).path("result").path("accessToken").asText();
        String sub = MAPPER.readTree(new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                StandardCharsets.UTF_8)).path("sub").asText();
        mailCatcher.sent.clear();
        return new Buyer(UUID.fromString(sub), email, token);
    }

    @TestConfiguration
    static class TestConfig {

        /**
         * Real PayOS client with a test checksum key: webhook verification is the SDK's own code. Only the
         * network calls (paymentRequests: create / get / cancel) go to the Mockito mock.
         */
        @Bean
        PayOS payOS(PaymentRequestsService payosRequests) {
            return new PayOS("test-client-id", "test-api-key", CHECKSUM_KEY) {
                @Override
                public PaymentRequestsService paymentRequests() {
                    return payosRequests;
                }
            };
        }

        @Bean
        AuthFlowTest.MailCatcher thu() {
            return new AuthFlowTest.MailCatcher();
        }
    }
}
