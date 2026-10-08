package com.securebank.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final String PASSWORD = "Sup3rSecret!";
    protected static final String ADMIN_EMAIL = "admin@securebank.test";
    protected static final String ADMIN_PASSWORD = "AdminPassword123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** Registered user with a valid access token. Emails are unique per call so tests stay independent. */
    public record TestUser(UUID id, String email, String token) {
    }

    protected TestUser registerAndLogin(String firstName) throws Exception {
        String email = firstName.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        JsonNode registered = json(register(firstName, email, PASSWORD).andExpect(status().isCreated()));
        return new TestUser(UUID.fromString(registered.get("id").asText()), email, login(email, PASSWORD));
    }

    protected ResultActions register(String firstName, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("firstName", firstName, "lastName", "Tester",
                        "email", email, "password", password))));
    }

    protected ResultActions loginRequest(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", password))));
    }

    protected String login(String email, String password) throws Exception {
        return json(loginRequest(email, password).andExpect(status().isOk())).get("accessToken").asText();
    }

    protected JsonNode createAccount(TestUser user, String type) throws Exception {
        return json(perform(post("/api/accounts"), user.token(), Map.of("type", type))
                .andExpect(status().isCreated()));
    }

    /** Sends a deposit with a fresh idempotency key. */
    protected ResultActions deposit(TestUser user, String accountId, String amount) throws Exception {
        return deposit(user, accountId, amount, newIdempotencyKey());
    }

    protected ResultActions deposit(TestUser user, String accountId, String amount, String idempotencyKey)
            throws Exception {
        return perform(withIdempotencyKey(post("/api/accounts/{id}/deposit", accountId), idempotencyKey),
                user.token(), Map.of("amount", new BigDecimal(amount)));
    }

    /** Sends a withdrawal with a fresh idempotency key. */
    protected ResultActions withdraw(TestUser user, String accountId, String amount) throws Exception {
        return withdraw(user, accountId, amount, newIdempotencyKey());
    }

    protected ResultActions withdraw(TestUser user, String accountId, String amount, String idempotencyKey)
            throws Exception {
        return perform(withIdempotencyKey(post("/api/accounts/{id}/withdraw", accountId), idempotencyKey),
                user.token(), Map.of("amount", new BigDecimal(amount)));
    }

    /** Sends a transfer with a fresh idempotency key. */
    protected ResultActions transfer(TestUser user, String sourceAccountId, String destinationAccountNumber,
                                     String amount) throws Exception {
        return transfer(user, sourceAccountId, destinationAccountNumber, amount, newIdempotencyKey());
    }

    protected ResultActions transfer(TestUser user, String sourceAccountId, String destinationAccountNumber,
                                     String amount, String idempotencyKey) throws Exception {
        return perform(withIdempotencyKey(post("/api/transfers"), idempotencyKey), user.token(), Map.of(
                "sourceAccountId", sourceAccountId,
                "destinationAccountNumber", destinationAccountNumber,
                "amount", new BigDecimal(amount)));
    }

    protected static String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }

    private static MockHttpServletRequestBuilder withIdempotencyKey(MockHttpServletRequestBuilder request,
                                                                    String idempotencyKey) {
        return idempotencyKey == null ? request : request.header("Idempotency-Key", idempotencyKey);
    }

    protected ResultActions getAuthenticated(String path, String token, Object... uriVars) throws Exception {
        return mockMvc.perform(get(path, uriVars).header("Authorization", "Bearer " + token));
    }

    protected JsonNode balanceOf(TestUser user, String accountId) throws Exception {
        return json(getAuthenticated("/api/accounts/{id}", user.token(), accountId)
                .andExpect(status().isOk())).get("balance");
    }

    protected ResultActions perform(MockHttpServletRequestBuilder request, String token, Object body)
            throws Exception {
        return mockMvc.perform(request
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body)));
    }

    protected JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
