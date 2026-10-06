# Secure Banking Application

This is a backend service for a small digital bank. It lets people create an account, sign in, open bank accounts, move money, and review their transactions. It is currently a Version 1 API, not a customer-facing web or mobile application.

## What It Does

### Customers

Customers can:

- Register with their name, email address, and password.
- Sign in and receive a short-lived access token.
- View their own profile.
- Open checking or savings accounts.
- View their own accounts and balances.
- Deposit money into an account.
- Withdraw money from an account.
- Transfer money to another account using its account number.
- View transaction history, newest transactions first.

Customers can only access accounts they own. They cannot see another customer's account details or use another customer's account for deposits, withdrawals, or transfers.

### Administrators

An administrator can review users, accounts, and transactions through read-only administrative
endpoints. Public registration never creates an administrator; it always creates a customer.

## Important Rules

- Money uses two decimal places and must be greater than zero.
- A single transaction cannot exceed `1,000,000,000.00`.
- Accounts cannot have a negative balance.
- Deposits, withdrawals, and transfers are completed atomically: either the whole operation succeeds
  or no balance changes are kept.
- Failed business operations such as insufficient funds are recorded with a reason when possible.
- Accounts can be active, frozen, or closed. Version 1 enforces these states, but does not expose an
  API for freezing or closing accounts.
- Passwords are stored using BCrypt, and the API uses stateless JWT bearer tokens rather than sessions.

## Current Technology

- Spring Boot 3.5.16
- Java 25
- PostgreSQL 16
- Flyway database migrations
- Maven

The service stores users, roles, bank accounts, and transaction records in PostgreSQL. Hibernate checks the database schema, while Flyway creates and updates it.

## Running

```bash
docker compose up -d                    
export JWT_SECRET='replace-with-a-random-string-of-at-least-32-chars'
export ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD='ChangeMe123!' 
mvn spring-boot:run
```

| Variable | Default | Notes |
|---|---|---|
| `JWT_SECRET` | none (required) | HS256 signing key, at least 32 characters. Startup fails without it. |
| `JWT_EXPIRATION` | `PT15M` | Access-token lifetime (ISO-8601 duration). |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/securebank` / `securebank` / `securebank` | |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | unset | Public registration only grants `CUSTOMER`. |

The schema is managed by Flyway (`src/main/resources/db/migration`); Hibernate only validates it.

## Tests

```bash
mvn test
```

Tests use an in-memory H2 database in PostgreSQL mode, so no running database is needed.

## API

All endpoints except register/login need `Authorization: Bearer <accessToken>`.

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/api/auth/register` | public | `{firstName, lastName, email, password}` → 201 |
| POST | `/api/auth/login` | public | `{email, password}` → `{accessToken, tokenType, expiresIn, expiresAt}` |
| GET | `/api/users/me` | any | Own profile |
| POST | `/api/accounts` | CUSTOMER | `{type: CHECKING\|SAVINGS}` → 201 |
| GET | `/api/accounts` | CUSTOMER | Own accounts |
| GET | `/api/accounts/{accountId}` | CUSTOMER | Owner only |
| POST | `/api/accounts/{accountId}/deposit` | CUSTOMER | `{amount, description?}` |
| POST | `/api/accounts/{accountId}/withdraw` | CUSTOMER | `{amount, description?}` |
| POST | `/api/transfers` | CUSTOMER | `{sourceAccountId, destinationAccountNumber, amount, description?}` → 201 |
| GET | `/api/accounts/{accountId}/transactions?page=&size=` | CUSTOMER | Owner only, newest first |
| GET | `/api/admin/users[/{id}]`, `/api/admin/accounts[/{id}]`, `/api/admin/accounts/{id}/transactions`, `/api/admin/transactions` | ADMIN | Read-only, paginated lists |

Errors use one JSON shape:

```json
{"timestamp":"…","status":422,"error":"Unprocessable Entity","code":"INSUFFICIENT_FUNDS","message":"Insufficient funds","path":"/api/accounts/…/withdraw"}
```

| Status | Codes |
|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_AMOUNT`, `INVALID_TRANSFER` |
| 401 | `INVALID_CREDENTIALS`, `UNAUTHORIZED` |
| 403 | `FORBIDDEN`, `ACCOUNT_ACCESS_DENIED` |
| 404 | `USER_NOT_FOUND`, `ACCOUNT_NOT_FOUND`, `NOT_FOUND` |
| 409 | `DUPLICATE_EMAIL`, `CONFLICT`, `CONCURRENT_MODIFICATION` |
| 422 | `INSUFFICIENT_FUNDS`, `ACCOUNT_FROZEN`, `ACCOUNT_CLOSED` |

## Design notes

- **Money** is `BigDecimal` / `NUMERIC(19,2)`. Amounts must be positive, have at most 2 decimal places
  (sub-cent amounts are rejected, not rounded) and be at most 1,000,000,000.00 per transaction.
  A database `CHECK` also keeps balances from going negative.
- **Consistency.** Each deposit, withdrawal, or transfer runs in one database transaction and locks the
  affected account rows (`SELECT … FOR UPDATE`). Transfers always lock the two accounts in ID order,
  so opposing transfers can't deadlock. Any failure rolls back both sides.
- **Transaction records.** Deposits set only the destination account, withdrawals only the source,
  and transfers set both. If a business rule rejects an operation on the caller's own account
  (insufficient funds, frozen or closed account), it is saved as `FAILED` with a reason, in a
  separate transaction after the rollback.
- **Ownership** always comes from the JWT subject (the user ID), never from request data. Transfers
  name the destination by account number, so customers never see other customers' internal account IDs.
- Account status changes (freeze/close) have no API in V1; the rules for them are enforced and tested.
