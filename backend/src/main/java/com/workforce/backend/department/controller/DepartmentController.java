package com.workforce.backend.department.controller;

import com.workforce.backend.auth.CurrentUser;
import com.workforce.backend.auth.Role;
import com.workforce.backend.common.BadRequestException;
import com.workforce.backend.common.ConflictException;
import com.workforce.backend.common.repository.JpaQueryRepository;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/departments")
public class DepartmentController {
    private final JpaQueryRepository queries;
    public DepartmentController(JpaQueryRepository queries) { this.queries = queries; }

    public record DepartmentRequest(String code, String name, Long parentId) {}

    @GetMapping
    public List<Map<String,Object>> list() {
        return queries.queryForList("SELECT d.*, (SELECT COUNT(*) FROM employees e WHERE e.department_id=d.id AND e.employment_status='ACTIVE') active_employees FROM departments d WHERE d.active=TRUE ORDER BY d.code");
    }

    @GetMapping("/tree")
    public List<Map<String,Object>> tree() {
        return queries.queryForList(
            "WITH RECURSIVE tree AS (" +
            "SELECT d.id,d.code,d.name,d.parent_id,1 depth FROM departments d WHERE d.parent_id IS NULL " +
            "UNION ALL SELECT d.id,d.code,d.name,d.parent_id,t.depth+1 FROM departments d JOIN tree t ON d.parent_id=t.id) " +
            "SELECT * FROM tree ORDER BY depth,id");
    }

    @PostMapping
    public Map<String,Object> create(@RequestBody DepartmentRequest r) {
        requireHr();
        if (r.code()==null || r.code().isBlank() || r.name()==null || r.name().isBlank())
            throw new BadRequestException("Department code and name are required");
        try {
            queries.update("INSERT INTO departments(code,name,parent_id) VALUES(?,?,?)",r.code().toUpperCase(),r.name(),r.parentId());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ConflictException("Department code already exists");
        }
        return queries.queryForMap("SELECT * FROM departments WHERE code=?",r.code().toUpperCase());
    }

    @PutMapping("/{id}")
    public Map<String,Object> update(@PathVariable Long id,@RequestBody DepartmentRequest r) {
        requireHr();
        if (r.parentId()!=null && (r.parentId().equals(id) || wouldCycle(id,r.parentId())))
            throw new ConflictException("Department cannot be parented to itself or one of its descendants");
        queries.update("UPDATE departments SET code=?,name=?,parent_id=? WHERE id=?",r.code().toUpperCase(),r.name(),r.parentId(),id);
        return queries.queryForMap("SELECT * FROM departments WHERE id=?",id);
    }

    @DeleteMapping("/{id}")
    public void deactivate(@PathVariable Long id) {
        requireHr();
        Integer employees=queries.queryForObject("SELECT COUNT(*) FROM employees WHERE department_id=? AND employment_status='ACTIVE'",Integer.class,id);
        Integer children=queries.queryForObject("SELECT COUNT(*) FROM departments WHERE parent_id=? AND active=TRUE",Integer.class,id);
        if (employees!=null && employees>0) throw new ConflictException("Department has active employees");
        if (children!=null && children>0) throw new ConflictException("Department has active sub-departments");
        queries.update("UPDATE departments SET active=FALSE WHERE id=?",id);
    }

    private boolean wouldCycle(Long id,Long parentId) {
        Integer c=queries.queryForObject("WITH RECURSIVE chain AS (SELECT id,parent_id FROM departments WHERE id=? UNION ALL SELECT d.id,d.parent_id FROM departments d JOIN chain c ON d.id=c.parent_id) SELECT COUNT(*) FROM chain WHERE id=?",Integer.class,parentId,id);
        return c!=null&&c>0;
    }
    private void requireHr() {
        if (CurrentUser.details().getRole()!=Role.HR_ADMIN)
            throw new org.springframework.security.access.AccessDeniedException("HR Administrator role required");
    }
}
