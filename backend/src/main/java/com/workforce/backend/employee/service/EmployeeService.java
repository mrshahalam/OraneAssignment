package com.workforce.backend.employee.service;

public interface EmployeeService {
    boolean wouldCreateReportingCycle(Long employeeId, Long managerId);
    boolean managerIsActive(Long managerId);
}
