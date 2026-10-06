package com.securebank.integration;

import com.securebank.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIntegrationTest extends IntegrationTestBase {

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTamperedAndGarbageTokens() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        String[] parts = alice.token().split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();

        getAuthenticated("/api/users/me", tampered)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Access token is invalid or expired"));
        getAuthenticated("/api/users/me", "not-a-jwt").andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmailReturnsSameGenericError() throws Exception {
        TestUser alice = registerAndLogin("Alice");

        loginRequest(alice.email(), "WrongPassword1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
        loginRequest("nobody-" + UUID.randomUUID() + "@example.com", "WrongPassword1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        TestUser alice = registerAndLogin("Alice");

        register("Alice", alice.email().toUpperCase(), PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void registrationValidatesInput() throws Exception {
        register("", "not-an-email", "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3));
    }

    @Test
    void registrationCannotGrantAdminRole() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"firstName":"Eve","lastName":"X","email":"eve-%s@example.com",
                         "password":"Sup3rSecret!","roles":["ADMIN"]}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void customersCannotUseAdminEndpoints() throws Exception {
        TestUser alice = registerAndLogin("Alice");

        getAuthenticated("/api/admin/users", alice.token())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        getAuthenticated("/api/admin/accounts", alice.token()).andExpect(status().isForbidden());
        getAuthenticated("/api/admin/transactions", alice.token()).andExpect(status().isForbidden());
    }

    @Test
    void adminCanViewUsersAccountsAndTransactionsButCannotActAsCustomer() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        String accountId = createAccount(alice, "CHECKING").get("id").asText();
        deposit(alice, accountId, "42.00");
        String adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        getAuthenticated("/api/admin/users/{id}", adminToken, alice.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(alice.email()));
        getAuthenticated("/api/admin/users", adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        getAuthenticated("/api/admin/accounts/{id}", adminToken, accountId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerEmail").value(alice.email()))
                .andExpect(jsonPath("$.balance").value(42.00));
        getAuthenticated("/api/admin/accounts/{id}/transactions", adminToken, accountId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("DEPOSIT"));
        getAuthenticated("/api/admin/transactions", adminToken).andExpect(status().isOk());

        // ADMIN is a read-only role in Version 1; it has no customer privileges.
        withdraw(new TestUser(null, ADMIN_EMAIL, adminToken), accountId, "1.00").andExpect(status().isForbidden());
    }

    @Test
    void errorResponsesDoNotLeakInternals() throws Exception {
        TestUser alice = registerAndLogin("Alice");

        getAuthenticated("/api/accounts/{id}", alice.token(), "not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
        getAuthenticated("/api/accounts/{id}", alice.token(), UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mockMvc.perform(post("/api/accounts").header("Authorization", "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\": \"BITCOIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"))
                .andExpect(content().string(not(containsString("com.securebank"))));
    }
}
