package com.workforce.backend.employee.service.impl;

import com.workforce.backend.employee.repository.EmployeeRepository;
import com.workforce.backend.employee.service.EmployeeService;
import org.springframework.stereotype.Service;

@Service
public class EmployeeServiceImpl implements EmployeeService {
    private final EmployeeRepository repository;

    public EmployeeServiceImpl(EmployeeRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean wouldCreateReportingCycle(Long employeeId, Long managerId) {
        return repository.managerChainContains(employeeId, managerId);
    }

    @Override
    public boolean managerIsActive(Long managerId) {
        return repository.activeManagerCount(managerId) > 0;
    }
}
