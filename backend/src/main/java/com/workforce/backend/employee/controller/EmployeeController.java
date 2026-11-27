package com.workforce.backend.employee.controller;

import com.workforce.backend.auth.CurrentUser;
import com.workforce.backend.auth.Role;
import com.workforce.backend.employee.AppUserDetails;
import com.workforce.backend.common.BadRequestException;
import com.workforce.backend.common.ConflictException;
import com.workforce.backend.common.NotFoundException;
import com.workforce.backend.common.repository.JpaQueryRepository;
import java.time.LocalDate;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {
    private final JpaQueryRepository queries;

    public EmployeeController(JpaQueryRepository queries) { this.queries = queries; }

    public record EmployeeRequest(String employeeId, String firstName, String lastName, String email,
                                  LocalDate dateOfJoining, Long departmentId, Long managerId,
                                  String positionCode, String employmentStatus) {}
    public record SelfUpdateRequest(String phone, String address) {}

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String search,
                                    @RequestParam(required = false) Long departmentId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(defaultValue = "employeeCode") String sort) {
        AppUserDetails user = CurrentUser.details();
        String allowedSort = Set.of("employeeCode","firstName","lastName","email","employmentStatus")
            .contains(sort) ? sort : "employeeCode";
        StringBuilder from = new StringBuilder(
            " FROM employees e WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (user.getRole() == Role.EMPLOYEE) {
            from.append(" AND e.id = ? ");
            args.add(user.getEmployeeId());
        } else if (user.getRole() == Role.MANAGER) {
            from.append(" AND e.id IN (WITH RECURSIVE team AS (SELECT id FROM employees WHERE manager_id=? UNION ALL SELECT e.id FROM employees e JOIN team t ON e.manager_id=t.id) SELECT id FROM team)");
            args.add(user.getEmployeeId());
        }
        if (!search.isBlank()) {
            from.append(" AND (lower(e.employee_code) LIKE ? OR lower(e.first_name) LIKE ? OR lower(e.last_name) LIKE ? OR lower(e.email) LIKE ?) ");
            String v = "%" + search.toLowerCase() + "%";
            args.addAll(List.of(v,v,v,v));
        }
        if (departmentId != null) { from.append(" AND e.department_id=? "); args.add(departmentId); }
        if (status != null && !status.isBlank()) { from.append(" AND e.employment_status=? "); args.add(status.toUpperCase()); }

        Integer total = queries.queryForObject("SELECT COUNT(*)" + from, Integer.class, args.toArray());
        String orderColumn = "e." + switch (allowedSort) {
            case "firstName" -> "first_name"; case "lastName" -> "last_name";
            case "email" -> "email"; case "employmentStatus" -> "employment_status";
            default -> "employee_code";
        };
        args.add(size); args.add(page * size);
        List<Map<String,Object>> rows = queries.queryForList(
            "SELECT e.id,e.employee_code,e.first_name,e.last_name,e.email,e.date_of_joining," +
            "e.department_id,e.manager_id,e.employment_status,e.phone" +
            (user.getRole() == Role.HR_ADMIN ? ",e.address " : " ") +
            " FROM employees e " + from.toString().replace(" FROM employees e WHERE", "WHERE") +
            " ORDER BY " + orderColumn + " LIMIT ? OFFSET ?", args.toArray());
        return Map.of("content", rows, "page", page, "size", size, "total", total);
    }

    @GetMapping("/{id}")
    public Map<String,Object> get(@PathVariable Long id) {
        AppUserDetails user = CurrentUser.details();
        canViewEmployee(user, id);
        Map<String,Object> row = queries.queryForMap(
            "SELECT e.*, d.name AS department_name, m.first_name||' '||m.last_name AS manager_name" +
            " FROM employees e JOIN departments d ON d.id=e.department_id LEFT JOIN employees m ON m.id=e.manager_id WHERE e.id=?", id);
        if (user.getRole() != Role.HR_ADMIN) row.remove("address");
        return row;
    }

    @PostMapping
    @Transactional
    public Map<String,Object> create(@RequestBody EmployeeRequest r) {
        requireHr();
        validateEmployeeRequest(r);
        if (r.managerId()!=null && !managerExistsActive(r.managerId()))
            throw new BadRequestException("Manager must reference an active employee");
        if (r.email()!=null && queries.queryForObject(
            "SELECT COUNT(*) FROM employees WHERE lower(email)=lower(?)", Integer.class, r.email()) > 0)
            throw new ConflictException("Email already exists");
        if (queries.queryForObject(
            "SELECT COUNT(*) FROM employees WHERE employee_code=?", Integer.class, r.employeeId()) > 0)
            throw new ConflictException("Employee ID already exists");
        queries.update("INSERT INTO employees(employee_code,first_name,last_name,email,date_of_joining,department_id,manager_id,employment_status) VALUES(?,?,?,?,?,?,?,?)",
            r.employeeId(), r.firstName(), r.lastName(), r.email(), r.dateOfJoining(),
            r.departmentId(), r.managerId(), r.employmentStatus()==null?"ACTIVE":r.employmentStatus());
        Long id = queries.queryForObject("SELECT id FROM employees WHERE employee_code=?", Long.class, r.employeeId());
        if (r.positionCode()!=null && !r.positionCode().isBlank()) assignPosition(id, r.positionCode());
        if (r.employmentStatus()==null || "ACTIVE".equals(r.employmentStatus()))
            queries.update("INSERT INTO leave_balances(employee_id,leave_type_id,year,total_days) SELECT ?,id,EXTRACT(YEAR FROM CURRENT_DATE)::int,annual_balance FROM leave_types ON CONFLICT DO NOTHING", id);
        return get(id);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String,Object> update(@PathVariable Long id, @RequestBody EmployeeRequest r) {
        requireHr();
        Map<String,Object> employee;
        try { employee = queries.queryForMap("SELECT * FROM employees WHERE id=?", id); }
        catch (NotFoundException e) { throw e; }
        validateEmployeeRequest(r);
        if (queries.queryForObject(
                "SELECT COUNT(*) FROM employees WHERE lower(email)=lower(?) AND id<>?",
                Integer.class, r.email(), id) > 0) {
            throw new ConflictException("Email already exists");
        }
        if (queries.queryForObject(
                "SELECT COUNT(*) FROM departments WHERE id=? AND active=TRUE",
                Integer.class, r.departmentId()) == 0) {
            throw new BadRequestException("Department must reference an active department");
        }
        if (r.managerId()!=null) {
            if (r.managerId().equals(id)) throw new BadRequestException("Self-reporting is not allowed");
            if (!managerExistsActive(r.managerId())) throw new BadRequestException("Manager must be active");
            if (wouldCreateCycle(id, r.managerId())) throw new ConflictException("Reporting change would create a cycle");
        }
        queries.update("UPDATE employees SET first_name=?,last_name=?,email=?,date_of_joining=?,department_id=?,manager_id=?,employment_status=?,updated_at=now(),version=version+1 WHERE id=?",
            r.firstName(),r.lastName(),r.email(),r.dateOfJoining(),r.departmentId(),
            r.managerId(),r.employmentStatus()==null?employee.get("employment_status"):r.employmentStatus(),id);
        if (r.positionCode()!=null && !r.positionCode().isBlank()) assignPosition(id, r.positionCode());
        return get(id);
    }

    @PutMapping("/{id}/self")
    public Map<String,Object> updateSelf(@PathVariable Long id, @RequestBody SelfUpdateRequest r) {
        AppUserDetails user = CurrentUser.details();
        if (!user.getEmployeeId().equals(id)) throw new ConflictException("You can update only your own profile");
        queries.update("UPDATE employees SET phone=?,address=?,updated_at=now(),version=version+1 WHERE id=?", r.phone(),r.address(),id);
        return get(id);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String,Object> deactivate(@PathVariable Long id) {
        requireHr();
        Integer reports = queries.queryForObject("SELECT COUNT(*) FROM employees WHERE manager_id=? AND employment_status='ACTIVE'", Integer.class, id);
        if (reports != null && reports > 0)
            throw new ConflictException("Deactivate direct reports first; pending leave and history are preserved");
        queries.update("UPDATE employee_position_history SET end_date=COALESCE(end_date,CURRENT_DATE) WHERE employee_id=? AND end_date IS NULL", id);
        queries.update("UPDATE employees SET employment_status='INACTIVE',updated_at=now(),version=version+1 WHERE id=?", id);
        return get(id);
    }

    private void validateEmployeeRequest(EmployeeRequest r) {
        if (r.employeeId()==null || r.employeeId().isBlank() || r.firstName()==null || r.firstName().isBlank()
            || r.lastName()==null || r.lastName().isBlank() || r.email()==null || r.email().isBlank()
            || r.dateOfJoining()==null || r.departmentId()==null)
            throw new BadRequestException("Required employee fields are missing");
        if (!r.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new BadRequestException("Email address is invalid");
        if (r.employmentStatus()!=null && !Set.of("ACTIVE", "INACTIVE").contains(r.employmentStatus())) {
            throw new BadRequestException("Employment status must be ACTIVE or INACTIVE");
        }
    }

    private void requireHr() {
        if (CurrentUser.details().getRole()!=Role.HR_ADMIN)
            throw new org.springframework.security.access.AccessDeniedException("HR Administrator role required");
    }

    private boolean managerExistsActive(Long id) {
        Integer c = queries.queryForObject("SELECT COUNT(*) FROM employees WHERE id=? AND employment_status='ACTIVE'", Integer.class, id);
        return c!=null && c>0;
    }

    private boolean wouldCreateCycle(Long employeeId, Long managerId) {
        Integer c = queries.queryForObject(
            "WITH RECURSIVE chain AS (SELECT id,manager_id FROM employees WHERE id=? UNION ALL SELECT e.id,e.manager_id FROM employees e JOIN chain c ON e.id=c.manager_id) SELECT COUNT(*) FROM chain WHERE id=?",
            Integer.class, managerId, employeeId);
        return c!=null && c>0;
    }

    private void assignPosition(Long employeeId, String positionCode) {
        Long positionId = queries.queryForNullableObject("SELECT id FROM positions WHERE code=?",
            Long.class, positionCode);
        if (positionId==null) throw new BadRequestException("Position not found");
        try {
            queries.update("UPDATE employee_position_history SET end_date=CURRENT_DATE WHERE employee_id=? AND end_date IS NULL", employeeId);
            queries.update("UPDATE employee_position_history SET end_date=CURRENT_DATE WHERE position_id=? AND end_date IS NULL", positionId);
            queries.update("INSERT INTO employee_position_history(employee_id,position_id,start_date) VALUES(?,?,CURRENT_DATE)", employeeId,positionId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ConflictException("Position or employee already has an active assignment");
        }
    }

    private void canViewEmployee(AppUserDetails user, Long id) {
        if (user.getRole()==Role.HR_ADMIN) return;
        if (user.getEmployeeId().equals(id)) return;
        if (user.getRole()==Role.MANAGER) {
            Integer c = queries.queryForObject(
                "WITH RECURSIVE team AS (SELECT id FROM employees WHERE manager_id=? UNION ALL SELECT e.id FROM employees e JOIN team t ON e.manager_id=t.id) SELECT COUNT(*) FROM team WHERE id=?",
                Integer.class,user.getEmployeeId(),id);
            if (c!=null && c>0) return;
        }
        throw new org.springframework.security.access.AccessDeniedException("Employee is outside your access scope");
    }
}
