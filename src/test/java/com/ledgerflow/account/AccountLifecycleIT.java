package com.ledgerflow.account;

import java.util.List;
import java.util.Map;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AccountLifecycleIT {

    @Autowired
    TestRestTemplate rest;

    ApiTestClient api;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
    }

    @Test
    void openListAndReadAccountWithZeroInitialBalance() {
        ApiTestClient.Session session = api.registerAndLogin();

        ResponseEntity<Map> created = api.post(session.token(), "/api/v1/accounts",
                Map.of("type", "USER_WALLET", "currency", "USD", "name", "Main wallet"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        String accountId = (String) created.getBody().get("id");

        ResponseEntity<List> list = api.getList(session.token(), "/api/v1/accounts");
        assertThat(list.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) list.getBody().get(0)).get("id")).isEqualTo(accountId);

        ResponseEntity<Map> balance = api.get(session.token(), "/api/v1/accounts/" + accountId + "/balance");
        assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) balance.getBody().get("balanceMinorUnits")).longValue()).isZero();
        assertThat(balance.getBody().get("currency")).isEqualTo("USD");
    }

    @Test
    void otherUsersAccountIsInvisibleNot403() {
        ApiTestClient.Session alice = api.registerAndLogin();
        ApiTestClient.Session mallory = api.registerAndLogin();

        ResponseEntity<Map> created = api.post(alice.token(), "/api/v1/accounts",
                Map.of("type", "USER_WALLET", "currency", "USD", "name", "Alice wallet"));
        String accountId = (String) created.getBody().get("id");

        // 404, not 403: existence of another user's account is not disclosed.
        ResponseEntity<Map> get = api.get(mallory.token(), "/api/v1/accounts/" + accountId);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> balance = api.get(mallory.token(), "/api/v1/accounts/" + accountId + "/balance");
        assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unsupportedCurrencyIsRejected() {
        ApiTestClient.Session session = api.registerAndLogin();

        ResponseEntity<Map> response = api.post(session.token(), "/api/v1/accounts",
                Map.of("type", "USER_WALLET", "currency", "EUR", "name", "Euro wallet"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void emptyAccountCanBeClosedAndClosingIsIdempotent() {
        ApiTestClient.Session session = api.registerAndLogin();
        ResponseEntity<Map> created = api.post(session.token(), "/api/v1/accounts",
                Map.of("type", "USER_WALLET", "currency", "USD", "name", "Short-lived"));
        String accountId = (String) created.getBody().get("id");

        ResponseEntity<Map> closed = api.delete(session.token(), "/api/v1/accounts/" + accountId);
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().get("status")).isEqualTo("CLOSED");

        ResponseEntity<Map> again = api.delete(session.token(), "/api/v1/accounts/" + accountId);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("status")).isEqualTo("CLOSED");
    }

    @Test
    void transactionHistoryStartsEmpty() {
        ApiTestClient.Session session = api.registerAndLogin();
        ResponseEntity<List> transactions = api.getList(session.token(), "/api/v1/transactions");
        assertThat(transactions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transactions.getBody()).isEmpty();
    }
}
