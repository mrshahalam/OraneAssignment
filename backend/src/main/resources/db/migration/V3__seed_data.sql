-- Departments
INSERT INTO departments (code, name, parent_id) VALUES ('HR',  'Human Resources', NULL);
INSERT INTO departments (code, name, parent_id) VALUES ('ENG', 'Engineering', NULL);
INSERT INTO departments (code, name, parent_id)
    VALUES ('ENG-BE', 'Backend Team', (SELECT id FROM departments WHERE code = 'ENG'));

-- Positions
INSERT INTO positions (code, title, department_id)
    VALUES ('POS-HR-01', 'HR Manager', (SELECT id FROM departments WHERE code = 'HR'));
INSERT INTO positions (code, title, department_id)
    VALUES ('POS-ENG-01', 'Engineering Head', (SELECT id FROM departments WHERE code = 'ENG'));
INSERT INTO positions (code, title, department_id, reporting_position_id)
    VALUES ('POS-ENG-02', 'Team Lead', (SELECT id FROM departments WHERE code = 'ENG-BE'),
            (SELECT id FROM positions WHERE code = 'POS-ENG-01'));
INSERT INTO positions (code, title, department_id, reporting_position_id)
    VALUES ('POS-ENG-03', 'Software Engineer', (SELECT id FROM departments WHERE code = 'ENG-BE'),
            (SELECT id FROM positions WHERE code = 'POS-ENG-02'));
INSERT INTO positions (code, title, department_id, reporting_position_id)
    VALUES ('POS-ENG-04', 'Software Engineer', (SELECT id FROM departments WHERE code = 'ENG-BE'),
            (SELECT id FROM positions WHERE code = 'POS-ENG-02'));   -- stays vacant

-- Employees (managers first, so manager_id can be resolved)
INSERT INTO employees (employee_code, first_name, last_name, email, date_of_joining, department_id)
    VALUES ('EMP001', 'Asha', 'Verma', 'hr@company.com', '2020-01-10',
            (SELECT id FROM departments WHERE code = 'HR'));
INSERT INTO employees (employee_code, first_name, last_name, email, date_of_joining, department_id)
    VALUES ('EMP002', 'Rahul', 'Sharma', 'manager1@company.com', '2019-04-01',
            (SELECT id FROM departments WHERE code = 'ENG'));
INSERT INTO employees (employee_code, first_name, last_name, email, date_of_joining, department_id, manager_id)
    VALUES ('EMP003', 'Neha', 'Gupta', 'manager2@company.com', '2021-06-15',
            (SELECT id FROM departments WHERE code = 'ENG-BE'),
            (SELECT id FROM employees WHERE employee_code = 'EMP002'));
INSERT INTO employees (employee_code, first_name, last_name, email, date_of_joining, department_id, manager_id)
    VALUES ('EMP004', 'Amit', 'Singh', 'emp1@company.com', '2022-02-20',
            (SELECT id FROM departments WHERE code = 'ENG-BE'),
            (SELECT id FROM employees WHERE employee_code = 'EMP003'));
INSERT INTO employees (employee_code, first_name, last_name, email, date_of_joining, department_id, manager_id)
    VALUES ('EMP005', 'Priya', 'Nair', 'emp2@company.com', '2023-08-05',
            (SELECT id FROM departments WHERE code = 'ENG-BE'),
            (SELECT id FROM employees WHERE employee_code = 'EMP003'));
INSERT INTO employees (employee_code, first_name, last_name, email, date_of_joining, department_id, manager_id)
    VALUES ('EMP006', 'Karan', 'Mehta', 'emp3@company.com', '2024-01-12',
            (SELECT id FROM departments WHERE code = 'ENG'),
            (SELECT id FROM employees WHERE employee_code = 'EMP002'));

-- Users (password for all: Password@123)
INSERT INTO users (employee_id, username, password_hash, role)
SELECT id, email, '$2b$10$qWkzyeuegFXL19bIwtmNCujQR7wL0yCsZCPfBsSVgwkwVdwEl47hy',
       CASE employee_code
            WHEN 'EMP001' THEN 'HR_ADMIN'
            WHEN 'EMP002' THEN 'MANAGER'
            WHEN 'EMP003' THEN 'MANAGER'
            ELSE 'EMPLOYEE' END
FROM employees;

-- Some position assignments
INSERT INTO employee_position_history (employee_id, position_id, start_date)
SELECT e.id, p.id, e.date_of_joining
FROM (VALUES ('EMP001','POS-HR-01'), ('EMP002','POS-ENG-01'),
             ('EMP003','POS-ENG-02'), ('EMP004','POS-ENG-03')) AS m(emp, pos)
JOIN employees e ON e.employee_code = m.emp
JOIN positions p ON p.code = m.pos;

-- Leave types and balances for 2026
INSERT INTO leave_types (code, name, annual_balance) VALUES
    ('CASUAL', 'Casual Leave', 12),
    ('SICK',   'Sick Leave',   10),
    ('EARNED', 'Earned Leave', 15);

INSERT INTO leave_balances (employee_id, leave_type_id, year, total_days)
SELECT e.id, lt.id, 2026, lt.annual_balance FROM employees e CROSS JOIN leave_types lt;

-- Default workflow: Manager approval, then HR approval
INSERT INTO approval_workflows (name, active) VALUES ('Default Leave Workflow', TRUE);
INSERT INTO approval_workflow_steps (workflow_id, step_order, name, approver_type)
SELECT id, 1, 'Manager Approval', 'MANAGER' FROM approval_workflows WHERE active;
INSERT INTO approval_workflow_steps (workflow_id, step_order, name, approver_type)
SELECT id, 2, 'HR Approval', 'HR_ADMIN' FROM approval_workflows WHERE active;