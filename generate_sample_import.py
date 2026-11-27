import csv

path = "sample_import_1000.csv"
with open(path, "w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow(["employee_id","first_name","last_name","email","date_of_joining","department_code","position_code","manager_employee_id","employment_status"])
    for i in range(1, 1001):
        if i % 10 == 0:
            writer.writerow(["", "Invalid", "Row", "bad-email", "not-a-date", "NOPE", "", "EMP003", "ACTIVE"])
        elif i % 11 == 0:
            writer.writerow([f"EMP{i:04}", "Duplicate", "Sample", "emp{i}@company.com", "2026-01-15", "ENG-BE", "", "EMP003", "ACTIVE"])
            writer.writerow([f"EMP{i:04}", "Duplicate", "Again", "emp{i}@company.com", "2026-01-15", "ENG-BE", "", "EMP003", "ACTIVE"])
        else:
            writer.writerow([f"EMP{i:04}", "Employee", str(i), f"employee{i}@company.com", "2026-01-15", "ENG-BE", "", "EMP003", "ACTIVE"])
