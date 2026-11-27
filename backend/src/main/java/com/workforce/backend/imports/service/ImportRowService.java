package com.workforce.backend.imports.service;

import com.workforce.backend.common.BadRequestException;
import com.workforce.backend.common.ConflictException;
import com.workforce.backend.common.repository.JpaQueryRepository;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportRowService {
    private final JpaQueryRepository queries;

    public ImportRowService(JpaQueryRepository queries) {
        this.queries = queries;
    }

    @Transactional
    public void processRow(String raw, Map<String, Integer> columns) {
        String[] values = parseCsv(raw);
        String employeeCode = value(values, columns, "employee_id");
        String firstName = value(values, columns, "first_name");
        String lastName = value(values, columns, "last_name");
        String email = value(values, columns, "email");
        String joiningDate = value(values, columns, "date_of_joining");
        String departmentCode = value(values, columns, "department_code");
        String status = value(values, columns, "employment_status");
        String positionCode = value(values, columns, "position_code");
        String managerCode = value(values, columns, "manager_employee_id");

        if (employeeCode.isBlank() || firstName.isBlank() || lastName.isBlank()
                || email.isBlank() || joiningDate.isBlank()
                || departmentCode.isBlank() || status.isBlank()) {
            throw new BadRequestException("Required fields are missing");
        }
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new BadRequestException("Email address is invalid");
        }

        LocalDate dateOfJoining;
        try {
            dateOfJoining = LocalDate.parse(joiningDate);
        } catch (RuntimeException ex) {
            throw new BadRequestException("date_of_joining must use YYYY-MM-DD format");
        }
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) {
            throw new BadRequestException("employment_status must be ACTIVE or INACTIVE");
        }

        Long departmentId = queries.queryForNullableObject(
                "SELECT id FROM departments WHERE code=? AND active=TRUE",
                Long.class, departmentCode.toUpperCase());
        if (departmentId == null) {
            throw new BadRequestException("Department code not found");
        }

        Integer duplicate = queries.queryForObject(
                "SELECT COUNT(*) FROM employees WHERE employee_code=? OR lower(email)=lower(?)",
                Integer.class, employeeCode, email);
        if (duplicate > 0) {
            throw new ConflictException("Employee ID or email already exists; duplicate skipped");
        }

        Long managerId = null;
        if (!managerCode.isBlank()) {
            managerId = queries.queryForNullableObject(
                    "SELECT id FROM employees WHERE employee_code=? AND employment_status='ACTIVE'",
                    Long.class, managerCode);
            if (managerId == null) {
                throw new BadRequestException("Manager employee ID not found or inactive");
            }
        }

        Long positionId = null;
        if (!positionCode.isBlank()) {
            positionId = queries.queryForNullableObject(
                    "SELECT id FROM positions WHERE code=? AND active=TRUE",
                    Long.class, positionCode.toUpperCase());
            if (positionId == null) {
                throw new BadRequestException("Position code not found or inactive");
            }
        }

        queries.update(
                "INSERT INTO employees(employee_code,first_name,last_name,email,date_of_joining,department_id,manager_id,employment_status) " +
                "VALUES(?,?,?,?,?,?,?,?)",
                employeeCode, firstName, lastName, email, dateOfJoining, departmentId, managerId, status);
        Long employeeId = queries.queryForObject(
                "SELECT id FROM employees WHERE employee_code=?", Long.class, employeeCode);

        if (positionId != null) {
            queries.update(
                    "INSERT INTO employee_position_history(employee_id,position_id,start_date) VALUES(?,?,CURRENT_DATE)",
                    employeeId, positionId);
        }
        if ("ACTIVE".equals(status)) {
            queries.update(
                    "INSERT INTO leave_balances(employee_id,leave_type_id,year,total_days) " +
                    "SELECT ?,id,EXTRACT(YEAR FROM CURRENT_DATE)::int,annual_balance FROM leave_types " +
                    "ON CONFLICT DO NOTHING",
                    employeeId);
        }
    }

    private String value(String[] values, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= values.length ? "" : values[index].trim();
    }

    private String[] parseCsv(String line) {
        java.util.List<String> values = new java.util.ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        if (quoted) throw new BadRequestException("CSV row contains an unclosed quoted field");
        values.add(value.toString());
        return values.toArray(String[]::new);
    }
}
