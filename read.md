# Enterprise Workforce Management

A Spring Boot REST application with PostgreSQL and a small role-aware browser UI.

## Run

Requirements: Docker Desktop.

```powershell
cd C:\Users\DELL\Downloads\OraneAssignment
docker compose up --build
```

Open:

- React application: http://localhost:5173
- Backend API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui/index.html

Stop:

```powershell
docker compose down
```

For frontend development only:

```powershell
cd frontend
npm install
npm run dev
```

To remove database data:

```powershell
docker compose down -v
```

## Seeded credentials

All passwords are `Password@123`.

| Role | Username |
|---|---|
| HR Administrator | hr@company.com |
| Manager | manager1@company.com |
| Manager | manager2@company.com |
| Employee | emp1@company.com |

## API authorization

JWT bearer tokens are returned by `POST /api/auth/login`.

Main endpoints:

- `GET/POST /api/employees`
- `PUT/DELETE /api/employees/{id}`
- `PUT /api/employees/{id}/self`
- `GET/POST/PUT/DELETE /api/departments`
- `GET/POST/PUT /api/positions`
- `POST /api/positions/{id}/assign`
- `GET /api/positions/{id}/history`
- `GET/PUT /api/workflows`
- `GET /api/leaves/types`, `GET /api/leaves/balances`
- `GET/POST /api/leaves`
- `POST /api/leaves/{id}/action`
- `POST /api/leaves/{id}/cancel`
- `POST/GET /api/imports`

## Import format

Upload CSV files with the columns in `sample_import_template.csv`. Maximum size is 5 MB. Generate the 1,000+ row test file with:

```powershell
python generate_sample_import.py
```

## Important assumptions

- Deactivation ends active positions and is blocked while active direct reports remain. Leave and assignment history are preserved.
- Manager home address is excluded from Manager responses; phone is visible.
- Reporting and department cycles are rejected before a relationship is saved.
- Workflow changes create a new active workflow; requests keep a snapshot of their original steps.
- A requester with no manager cannot progress through a manager approval step. Configure an HR-only workflow or assign the requester a manager for full progression.
- Reimporting an existing employee ID or email reports the row as a duplicate and never overwrites it.
- Leave consumes working days Monday-Friday. Pending requests reserve balance; approval consumes it; rejection or cancellation releases it.
- Concurrent leave submissions lock the employee row before checking overlap and available balance. Approval and cancellation lock the request row, and a partial unique database index permits only one approval/rejection decision per request step.
- Each import row is handled by a separate Spring transactional service call. Invalid rows roll back employee, position and balance writes before the row-level error is saved.
- Position uniqueness is enforced by PostgreSQL partial unique indexes and a database constraint, so concurrent assignment requests cannot create a double assignment.
- Import processing uses four fixed worker threads and batches of 100 futures for bounded processing.

## Technology

Java 21, Spring Boot, Spring Security/JWT, Spring Data JPA/Hibernate, PostgreSQL/Flyway, Docker Compose, React, and Vite. Users and employees are mapped JPA entities and authentication uses a Spring Data repository. The feature controllers currently use a shared repository backed by Hibernate's `EntityManager` with PostgreSQL native queries; this keeps recursive hierarchy queries and database-enforced concurrency constraints straightforward. No `JdbcTemplate` or Spring JDBC starter is used.
