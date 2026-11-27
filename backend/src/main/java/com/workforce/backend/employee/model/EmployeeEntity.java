package com.workforce.backend.employee.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "employees")
public class EmployeeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employment_status", nullable = false)
    private String employmentStatus;

    protected EmployeeEntity() {}

    public Long getId() { return id; }
    public String getEmploymentStatus() { return employmentStatus; }
}
