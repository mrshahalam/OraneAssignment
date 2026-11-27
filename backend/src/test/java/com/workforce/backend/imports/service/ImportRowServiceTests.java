package com.workforce.backend.imports.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

import com.workforce.backend.common.BadRequestException;
import com.workforce.backend.common.repository.JpaQueryRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportRowServiceTests {
    @Test
    void invalidRequiredFieldsFailBeforeDatabaseWrites() {
        JpaQueryRepository repository = org.mockito.Mockito.mock(JpaQueryRepository.class);
        ImportRowService service = new ImportRowService(repository);

        assertThrows(BadRequestException.class, () -> service.processRow(
                ",,Last,,2026-01-01,ENG, , ,ACTIVE",
                Map.of(
                    "employee_id", 0,
                    "first_name", 1,
                    "last_name", 2,
                    "email", 3,
                    "date_of_joining", 4,
                    "department_code", 5,
                    "position_code", 6,
                    "manager_employee_id", 7,
                    "employment_status", 8)));
        verifyNoInteractions(repository);
    }

    @Test
    void positionIsValidatedBeforeEmployeeInsert() {
        JpaQueryRepository repository = org.mockito.Mockito.mock(JpaQueryRepository.class);
        org.mockito.Mockito.when(repository.queryForNullableObject(
                org.mockito.ArgumentMatchers.contains("FROM departments"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq("ENG"))).thenReturn(1L);
        org.mockito.Mockito.when(repository.queryForObject(
                org.mockito.ArgumentMatchers.contains("COUNT(*)"),
                org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(0);
        org.mockito.Mockito.when(repository.queryForNullableObject(
                org.mockito.ArgumentMatchers.contains("FROM positions"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq("MISSING"))).thenReturn(null);

        ImportRowService service = new ImportRowService(repository);
        Map<String, Integer> columns = Map.of(
                "employee_id", 0, "first_name", 1, "last_name", 2, "email", 3,
                "date_of_joining", 4, "department_code", 5, "position_code", 6,
                "manager_employee_id", 7, "employment_status", 8);

        assertThrows(BadRequestException.class, () -> service.processRow(
                "EMP100,Test,User,test@example.com,2026-01-01,ENG,MISSING,,ACTIVE", columns));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .update(org.mockito.ArgumentMatchers.contains("INSERT INTO employees"),
                        org.mockito.ArgumentMatchers.any());
    }
}
