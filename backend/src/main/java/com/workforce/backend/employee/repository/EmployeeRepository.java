package com.workforce.backend.employee.repository;

import com.workforce.backend.common.repository.JpaQueryRepository;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeRepository {
    private final JpaQueryRepository queries;

    public EmployeeRepository(JpaQueryRepository queries) { this.queries = queries; }

    public int activeManagerCount(Long employeeId) {
        Integer count = queries.queryForObject(
            "SELECT COUNT(*) FROM employees WHERE id=? AND employment_status='ACTIVE'",
            Integer.class, employeeId);
        return count == null ? 0 : count;
    }

    public boolean managerChainContains(Long employeeId, Long managerId) {
        Integer count = queries.queryForObject(
            "WITH RECURSIVE chain AS (" +
            "SELECT id,manager_id FROM employees WHERE id=? " +
            "UNION ALL SELECT e.id,e.manager_id FROM employees e JOIN chain c ON e.id=c.manager_id" +
            ") SELECT COUNT(*) FROM chain WHERE id=?",
            Integer.class, managerId, employeeId);
        return count != null && count > 0;
    }
}
