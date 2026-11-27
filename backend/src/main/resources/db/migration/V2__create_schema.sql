-- ===== Organization =====
CREATE TABLE departments (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(30)  NOT NULL UNIQUE,
    name        VARCHAR(150) NOT NULL,
    parent_id   BIGINT REFERENCES departments(id),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_dept_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX idx_departments_parent ON departments(parent_id);

CREATE TABLE positions (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                  VARCHAR(30)  NOT NULL UNIQUE,
    title                 VARCHAR(150) NOT NULL,
    department_id         BIGINT       NOT NULL REFERENCES departments(id),
    reporting_position_id BIGINT       REFERENCES positions(id),
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_pos_not_self_report CHECK (reporting_position_id IS NULL OR reporting_position_id <> id)
);
CREATE INDEX idx_positions_department ON positions(department_id);

-- ===== Employees and users =====
CREATE TABLE employees (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_code     VARCHAR(30)  NOT NULL UNIQUE,
    first_name        VARCHAR(100) NOT NULL,
    last_name         VARCHAR(100) NOT NULL,
    email             VARCHAR(255) NOT NULL,
    date_of_joining   DATE         NOT NULL,
    department_id     BIGINT       NOT NULL REFERENCES departments(id),
    manager_id        BIGINT       REFERENCES employees(id),
    employment_status VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    phone             VARCHAR(30),
    address           VARCHAR(500),
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_emp_status CHECK (employment_status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_emp_not_self_manager CHECK (manager_id IS NULL OR manager_id <> id)
);
CREATE UNIQUE INDEX uq_employees_email ON employees (lower(email));
CREATE INDEX idx_employees_manager    ON employees(manager_id);
CREATE INDEX idx_employees_department ON employees(department_id);
CREATE INDEX idx_employees_status     ON employees(employment_status);
CREATE INDEX idx_employees_name       ON employees(last_name, first_name);

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id   BIGINT       NOT NULL UNIQUE REFERENCES employees(id),
    username      VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    CONSTRAINT chk_user_role CHECK (role IN ('HR_ADMIN', 'MANAGER', 'EMPLOYEE'))
);

-- ===== Position assignment history =====
CREATE TABLE employee_position_history (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT    NOT NULL REFERENCES employees(id),
    position_id BIGINT    NOT NULL REFERENCES positions(id),
    start_date  DATE      NOT NULL,
    end_date    DATE,
    created_by  BIGINT    REFERENCES employees(id),
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_assignment_dates CHECK (end_date IS NULL OR end_date >= start_date)
);
-- The concurrency guard: the database itself allows only ONE active row per position
-- and ONE active row per employee.
CREATE UNIQUE INDEX uq_active_assignment_per_position
    ON employee_position_history(position_id) WHERE end_date IS NULL;
CREATE UNIQUE INDEX uq_active_assignment_per_employee
    ON employee_position_history(employee_id) WHERE end_date IS NULL;

-- ===== Leave =====
CREATE TABLE leave_types (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code           VARCHAR(20)  NOT NULL UNIQUE,
    name           VARCHAR(100) NOT NULL,
    annual_balance NUMERIC(5,1) NOT NULL CHECK (annual_balance >= 0)
);

CREATE TABLE leave_balances (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id   BIGINT       NOT NULL REFERENCES employees(id),
    leave_type_id BIGINT       NOT NULL REFERENCES leave_types(id),
    year          INT          NOT NULL,
    total_days    NUMERIC(5,1) NOT NULL,
    used_days     NUMERIC(5,1) NOT NULL DEFAULT 0,
    pending_days  NUMERIC(5,1) NOT NULL DEFAULT 0,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_balance UNIQUE (employee_id, leave_type_id, year),
    CONSTRAINT chk_balance_not_exceeded CHECK (used_days >= 0 AND pending_days >= 0
                                               AND used_days + pending_days <= total_days)
);

-- ===== Approval workflow (data-driven) =====
CREATE TABLE approval_workflows (
    id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name   VARCHAR(100) NOT NULL,
    active BOOLEAN      NOT NULL DEFAULT TRUE
);
-- Only one active workflow at a time
CREATE UNIQUE INDEX uq_one_active_workflow ON approval_workflows(active) WHERE active;

CREATE TABLE approval_workflow_steps (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id   BIGINT       NOT NULL REFERENCES approval_workflows(id),
    step_order    INT          NOT NULL CHECK (step_order > 0),
    name          VARCHAR(100) NOT NULL,
    approver_type VARCHAR(20)  NOT NULL,
    CONSTRAINT uq_workflow_step_order UNIQUE (workflow_id, step_order),
    CONSTRAINT chk_approver_type CHECK (approver_type IN ('MANAGER', 'HR_ADMIN'))
);

CREATE TABLE leave_requests (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id        BIGINT       NOT NULL REFERENCES employees(id),
    leave_type_id      BIGINT       NOT NULL REFERENCES leave_types(id),
    start_date         DATE         NOT NULL,
    end_date           DATE         NOT NULL,
    working_days       NUMERIC(5,1) NOT NULL CHECK (working_days > 0),
    reason             VARCHAR(500) NOT NULL,
    status             VARCHAR(15)  NOT NULL DEFAULT 'PENDING',
    current_step_order INT,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_leave_dates  CHECK (end_date >= start_date),
    CONSTRAINT chk_leave_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
);
CREATE INDEX idx_leave_requests_employee ON leave_requests(employee_id, status);
CREATE INDEX idx_leave_requests_status   ON leave_requests(status);

-- A copy (snapshot) of the workflow steps, taken when the request is submitted.
-- This keeps in-progress requests safe when HR changes the workflow later.
CREATE TABLE leave_request_steps (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    leave_request_id BIGINT      NOT NULL REFERENCES leave_requests(id),
    step_order       INT         NOT NULL,
    name             VARCHAR(100) NOT NULL,
    approver_type    VARCHAR(20) NOT NULL,
    assigned_to      BIGINT      REFERENCES employees(id),   -- set for MANAGER steps
    status           VARCHAR(15) NOT NULL DEFAULT 'WAITING',
    CONSTRAINT uq_request_step UNIQUE (leave_request_id, step_order),
    CONSTRAINT chk_step_status CHECK (status IN ('WAITING', 'PENDING', 'APPROVED', 'REJECTED', 'SKIPPED'))
);

CREATE TABLE approval_history (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    leave_request_id BIGINT      NOT NULL REFERENCES leave_requests(id),
    step_order       INT         NOT NULL,
    approver_id      BIGINT      NOT NULL REFERENCES employees(id),
    action           VARCHAR(10) NOT NULL,
    comment          VARCHAR(500),
    acted_at         TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT chk_action CHECK (action IN ('APPROVED', 'REJECTED', 'SUBMITTED', 'CANCELLED')),
    -- one decision per step: the database blocks two approvers acting on the same step
    CONSTRAINT uq_one_decision_per_step UNIQUE (leave_request_id, step_order, action)
);
CREATE INDEX idx_approval_history_request ON approval_history(leave_request_id);

-- ===== Bulk import =====
CREATE TABLE import_jobs (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name    VARCHAR(255) NOT NULL,
    status       VARCHAR(25)  NOT NULL DEFAULT 'QUEUED',
    total_rows   INT          NOT NULL DEFAULT 0,
    success_rows INT          NOT NULL DEFAULT 0,
    failed_rows  INT          NOT NULL DEFAULT 0,
    created_by   BIGINT       NOT NULL REFERENCES employees(id),
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    CONSTRAINT chk_import_status CHECK (status IN
        ('QUEUED', 'PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED'))
);

CREATE TABLE import_errors (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id        BIGINT NOT NULL REFERENCES import_jobs(id),
    row_number    INT    NOT NULL,
    raw_data      TEXT   NOT NULL,
    error_message VARCHAR(500) NOT NULL
);
CREATE INDEX idx_import_errors_job ON import_errors(job_id);