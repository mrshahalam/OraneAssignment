package com.workforce.backend.position.controller;

import com.workforce.backend.auth.CurrentUser;
import com.workforce.backend.auth.Role;
import com.workforce.backend.common.BadRequestException;
import com.workforce.backend.common.ConflictException;
import com.workforce.backend.common.repository.JpaQueryRepository;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/positions")
public class PositionController {
    private final JpaQueryRepository queries;
    public PositionController(JpaQueryRepository queries) { this.queries=queries; }

    public record PositionRequest(String code,String title,Long departmentId,Long reportingPositionId) {}
    public record AssignmentRequest(Long employeeId,LocalDate startDate) {}

    @GetMapping
    public List<Map<String,Object>> list(@RequestParam(required=false) Boolean vacant) {
        String sql="SELECT p.*,d.name department_name,e.id active_employee_id,e.first_name||' '||e.last_name active_employee_name "+
            "FROM positions p JOIN departments d ON d.id=p.department_id "+
            "LEFT JOIN employee_position_history h ON h.position_id=p.id AND h.end_date IS NULL "+
            "LEFT JOIN employees e ON e.id=h.employee_id WHERE p.active=TRUE";
        if (Boolean.TRUE.equals(vacant)) sql+=" AND h.id IS NULL";
        return queries.queryForList(sql+" ORDER BY p.code");
    }

    @PostMapping
    public Map<String,Object> create(@RequestBody PositionRequest r) {
        requireHr();
        if (r.code()==null||r.code().isBlank()||r.title()==null||r.title().isBlank()||r.departmentId()==null)
            throw new BadRequestException("Position code, title and department are required");
        try { queries.update("INSERT INTO positions(code,title,department_id,reporting_position_id) VALUES(?,?,?,?)",
            r.code().toUpperCase(),r.title(),r.departmentId(),r.reportingPositionId()); }
        catch (DataIntegrityViolationException e) { throw new ConflictException("Position code already exists"); }
        return queries.queryForMap("SELECT * FROM positions WHERE code=?",r.code().toUpperCase());
    }

    @PutMapping("/{id}")
    public Map<String,Object> update(@PathVariable Long id,@RequestBody PositionRequest r) {
        requireHr();
        queries.update("UPDATE positions SET code=?,title=?,department_id=?,reporting_position_id=? WHERE id=?",
            r.code().toUpperCase(),r.title(),r.departmentId(),r.reportingPositionId(),id);
        return queries.queryForMap("SELECT * FROM positions WHERE id=?",id);
    }

    @PostMapping("/{id}/assign")
    @Transactional
    public Map<String,Object> assign(@PathVariable Long id,@RequestBody AssignmentRequest r) {
        requireHr();
        if (r.employeeId()==null) throw new BadRequestException("employeeId is required");
        Integer active=queries.queryForObject("SELECT COUNT(*) FROM employees WHERE id=? AND employment_status='ACTIVE'",Integer.class,r.employeeId());
        if (active==null||active==0) throw new BadRequestException("Employee must be active");
        LocalDate start=r.startDate()==null?LocalDate.now():r.startDate();
        try {
            queries.update("UPDATE employee_position_history SET end_date=? WHERE employee_id=? AND end_date IS NULL",start,r.employeeId());
            queries.update("UPDATE employee_position_history SET end_date=? WHERE position_id=? AND end_date IS NULL",start,id);
            queries.update("INSERT INTO employee_position_history(employee_id,position_id,start_date,created_by) VALUES(?,?,?,?)",
                r.employeeId(),id,start,CurrentUser.details().getEmployeeId());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Position or employee already has an active assignment");
        }
        return queries.queryForMap("SELECT * FROM employee_position_history WHERE employee_id=? AND position_id=? AND end_date IS NULL",r.employeeId(),id);
    }

    @PostMapping("/{id}/end")
    @Transactional
    public void end(@PathVariable Long id,@RequestParam(defaultValue="") String date) {
        requireHr();
        queries.update("UPDATE employee_position_history SET end_date=? WHERE position_id=? AND end_date IS NULL",
            date.isBlank()?LocalDate.now():LocalDate.parse(date),id);
    }

    @GetMapping("/{id}/history")
    public List<Map<String,Object>> history(@PathVariable Long id) {
        return queries.queryForList("SELECT h.*,e.employee_code,e.first_name,e.last_name FROM employee_position_history h JOIN employees e ON e.id=h.employee_id WHERE h.position_id=? ORDER BY h.start_date DESC,h.id DESC",id);
    }

    private void requireHr() {
        if (CurrentUser.details().getRole()!=Role.HR_ADMIN)
            throw new org.springframework.security.access.AccessDeniedException("HR Administrator role required");
    }
}
