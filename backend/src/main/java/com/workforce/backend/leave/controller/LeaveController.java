package com.workforce.backend.leave.controller;

import com.workforce.backend.auth.CurrentUser;
import com.workforce.backend.auth.Role;
import com.workforce.backend.common.BadRequestException;
import com.workforce.backend.common.ConflictException;
import com.workforce.backend.employee.AppUserDetails;
import com.workforce.backend.common.utility.WorkingDayCalculator;
import com.workforce.backend.common.repository.JpaQueryRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/leaves")
public class LeaveController {
    private final JpaQueryRepository queries;
    public LeaveController(JpaQueryRepository queries){this.queries=queries;}

    public record LeaveRequest(Long leaveTypeId,LocalDate startDate,LocalDate endDate,String reason){}
    public record ActionRequest(String action,String comment){}

    @GetMapping("/types")
    public List<Map<String,Object>> types(){ return queries.queryForList("SELECT * FROM leave_types ORDER BY code"); }

    @GetMapping("/balances")
    public List<Map<String,Object>> balances(){
        Long employeeId=CurrentUser.details().getEmployeeId();
        int year=LocalDate.now().getYear();
        return queries.queryForList("SELECT lt.code,lt.name,b.total_days,b.pending_days,b.used_days,(b.total_days-b.pending_days-b.used_days) available_days FROM leave_balances b JOIN leave_types lt ON lt.id=b.leave_type_id WHERE b.employee_id=? AND b.year=? ORDER BY lt.code",employeeId,year);
    }

    @GetMapping
    public List<Map<String,Object>> list(){
        AppUserDetails user=CurrentUser.details();
        if(user.getRole()==Role.HR_ADMIN)
            return queries.queryForList("SELECT lr.*,e.employee_code,e.first_name,e.last_name,lt.code leave_type_code FROM leave_requests lr JOIN employees e ON e.id=lr.employee_id JOIN leave_types lt ON lt.id=lr.leave_type_id ORDER BY lr.created_at DESC");
        if(user.getRole()==Role.MANAGER)
            return queries.queryForList(
              "SELECT lr.*,e.employee_code,e.first_name,e.last_name,lt.code leave_type_code FROM leave_requests lr JOIN employees e ON e.id=lr.employee_id JOIN leave_types lt ON lt.id=lr.leave_type_id "+
              "WHERE lr.employee_id=? OR lr.id IN (SELECT lrs.leave_request_id FROM leave_request_steps lrs WHERE lrs.assigned_to=? AND lrs.status='PENDING') "+
              "OR e.manager_id=? OR e.id IN (WITH RECURSIVE team AS (SELECT id FROM employees WHERE manager_id=? UNION ALL SELECT e2.id FROM employees e2 JOIN team t ON e2.manager_id=t.id) SELECT id FROM team) ORDER BY lr.created_at DESC",
              user.getEmployeeId(),user.getEmployeeId(),user.getEmployeeId(),user.getEmployeeId());
        return queries.queryForList("SELECT lr.*,lt.code leave_type_code FROM leave_requests lr JOIN leave_types lt ON lt.id=lr.leave_type_id WHERE lr.employee_id=? ORDER BY lr.created_at DESC",user.getEmployeeId());
    }

    @PostMapping
    @Transactional
    public Map<String,Object> apply(@RequestBody LeaveRequest r){
        if(r.leaveTypeId()==null||r.startDate()==null||r.endDate()==null||r.reason()==null||r.reason().isBlank())
            throw new BadRequestException("Leave type, dates and reason are required");
        if(r.endDate().isBefore(r.startDate())) throw new BadRequestException("End date must not be before start date");
        long employeeId=CurrentUser.details().getEmployeeId();
        queries.queryForUpdate("SELECT id FROM employees WHERE id=? FOR UPDATE", employeeId);
        Integer overlap=queries.queryForObject("SELECT COUNT(*) FROM leave_requests WHERE employee_id=? AND status IN ('PENDING','APPROVED') AND start_date<=? AND end_date>=?",
            Integer.class,employeeId,r.endDate(),r.startDate());
        if(overlap!=null&&overlap>0) throw new ConflictException("Leave overlaps an existing pending or approved request");
        int year=r.startDate().getYear();
        Map<String,Object> balance;
        try{balance=queries.queryForMap("SELECT * FROM leave_balances WHERE employee_id=? AND leave_type_id=? AND year=?",employeeId,r.leaveTypeId(),year);}
        catch(Exception e){throw new BadRequestException("Leave balance not found for this year");}
        BigDecimal working=BigDecimal.valueOf(WorkingDayCalculator.count(r.startDate(),r.endDate()));
        BigDecimal total=(BigDecimal)balance.get("total_days"), used=(BigDecimal)balance.get("used_days"), pending=(BigDecimal)balance.get("pending_days");
        if(total.subtract(used).subtract(pending).compareTo(working)<0) throw new ConflictException("Insufficient leave balance");

        Long workflowId=queries.queryForObject("SELECT id FROM approval_workflows WHERE active=TRUE",Long.class);
        Long leaveId=queries.queryForObject("INSERT INTO leave_requests(employee_id,leave_type_id,start_date,end_date,working_days,reason,status,current_step_order) VALUES(?,?,?,?,?,?,'PENDING',1) RETURNING id",
            Long.class,employeeId,r.leaveTypeId(),r.startDate(),r.endDate(),working,r.reason());
        List<Map<String,Object>> steps=queries.queryForList("SELECT * FROM approval_workflow_steps WHERE workflow_id=? ORDER BY step_order",workflowId);
        Long managerId=queries.queryForNullableObject("SELECT manager_id FROM employees WHERE id=?",
            Long.class, employeeId);
        for(Map<String,Object>s:steps){
            String type=(String)s.get("approver_type");
            Long assigned="MANAGER".equals(type)?managerId:null;
            queries.update("INSERT INTO leave_request_steps(leave_request_id,step_order,name,approver_type,assigned_to,status) VALUES(?,?,?,?,?,?)",
                leaveId,s.get("step_order"),s.get("name"),type,assigned,"PENDING".equals(s.get("step_order").toString())?"PENDING":"WAITING");
        }
        queries.update("UPDATE leave_request_steps SET status='PENDING' WHERE leave_request_id=? AND step_order=1",leaveId);
        queries.update("UPDATE leave_balances SET pending_days=pending_days+? ,version=version+1 WHERE employee_id=? AND leave_type_id=? AND year=?",
            working,employeeId,r.leaveTypeId(),year);
        queries.update("INSERT INTO approval_history(leave_request_id,step_order,approver_id,action,comment) VALUES(?,0,?,'SUBMITTED',?)",
            leaveId,employeeId,r.reason());
        return queries.queryForMap("SELECT * FROM leave_requests WHERE id=?",leaveId);
    }

    @PostMapping("/{id}/action")
    @Transactional
    public Map<String,Object> action(@PathVariable Long id,@RequestBody ActionRequest r){
        if(r.action()==null||!Set.of("APPROVED","REJECTED").contains(r.action().toUpperCase()))
            throw new BadRequestException("Action must be APPROVED or REJECTED");
        if("REJECTED".equals(r.action().toUpperCase())&&(r.comment()==null||r.comment().isBlank()))
            throw new BadRequestException("A comment is required when rejecting");
        AppUserDetails user=CurrentUser.details();
        Map<String,Object> leave=queries.queryForUpdate("SELECT * FROM leave_requests WHERE id=? FOR UPDATE",id);
        if(!"PENDING".equals(leave.get("status"))) throw new ConflictException("Leave request is no longer pending");
        if(user.getEmployeeId().equals(((Number)leave.get("employee_id")).longValue()))
            throw new ConflictException("You cannot act on your own leave request");
        int step=((Number)leave.get("current_step_order")).intValue();
        Map<String,Object> stepRow;
        try{stepRow=queries.queryForMap("SELECT * FROM leave_request_steps WHERE leave_request_id=? AND step_order=?",id,step);}
        catch(Exception e){throw new ConflictException("Invalid workflow step");}
        String approverType=(String)stepRow.get("approver_type");
        if("MANAGER".equals(approverType)){
            if(user.getRole()!=Role.MANAGER) throw new org.springframework.security.access.AccessDeniedException("Only the assigned manager can act");
            Object assigned=stepRow.get("assigned_to");
            if(assigned==null||!user.getEmployeeId().equals(((Number)assigned).longValue()))
                throw new org.springframework.security.access.AccessDeniedException("Only the requester manager can act");
        } else if("HR_ADMIN".equals(approverType)){
            if(user.getRole()!=Role.HR_ADMIN) throw new org.springframework.security.access.AccessDeniedException("Only HR can act");
        } else throw new ConflictException("Unsupported approver type");

        try {
            queries.update("INSERT INTO approval_history(leave_request_id,step_order,approver_id,action,comment) VALUES(?,?,?,?,?)",
                id,step,user.getEmployeeId(),r.action().toUpperCase(),r.comment());
        } catch(DataIntegrityViolationException e){throw new ConflictException("This step has already been acted on");}

        if("REJECTED".equals(r.action().toUpperCase())){
            queries.update("UPDATE leave_request_steps SET status='REJECTED' WHERE leave_request_id=? AND step_order=?",id,step);
            releaseOrReject(id);
            queries.update("UPDATE leave_requests SET status='REJECTED',updated_at=now(),version=version+1 WHERE id=?",id);
        } else {
            queries.update("UPDATE leave_request_steps SET status='APPROVED' WHERE leave_request_id=? AND step_order=?",id,step);
            Integer next=queries.queryForObject("SELECT MIN(step_order) FROM leave_request_steps WHERE leave_request_id=? AND step_order>? AND status='WAITING'",Integer.class,id,step);
            if(next==null){
                consumeLeave(id);
                queries.update("UPDATE leave_requests SET status='APPROVED',current_step_order=NULL,updated_at=now(),version=version+1 WHERE id=?",id);
            } else {
                queries.update("UPDATE leave_request_steps SET status='PENDING' WHERE leave_request_id=? AND step_order=?",id,next);
                queries.update("UPDATE leave_requests SET current_step_order=?,updated_at=now(),version=version+1 WHERE id=?",next,id);
            }
        }
        return queries.queryForMap("SELECT * FROM leave_requests WHERE id=?",id);
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public Map<String,Object> cancel(@PathVariable Long id){
        AppUserDetails user=CurrentUser.details();
        Map<String,Object> leave=queries.queryForUpdate("SELECT * FROM leave_requests WHERE id=? FOR UPDATE",id);
        if(!user.getEmployeeId().equals(((Number)leave.get("employee_id")).longValue()))
            throw new org.springframework.security.access.AccessDeniedException("Only the requester can cancel");
        if(!"PENDING".equals(leave.get("status"))) throw new ConflictException("Only pending leave can be cancelled");
        queries.update("UPDATE leave_request_steps SET status='SKIPPED' WHERE leave_request_id=? AND status IN ('WAITING','PENDING')",id);
        releaseOrReject(id);
        queries.update("UPDATE leave_requests SET status='CANCELLED',updated_at=now(),version=version+1 WHERE id=?",id);
        queries.update("INSERT INTO approval_history(leave_request_id,step_order,approver_id,action,comment) VALUES(?,0,?,'CANCELLED',?)",
            id,user.getEmployeeId(),"Cancelled by employee");
        return queries.queryForMap("SELECT * FROM leave_requests WHERE id=?",id);
    }

    private void releaseOrReject(Long leaveId){
        queries.update("UPDATE leave_balances b SET pending_days=pending_days-l.working_days,version=b.version+1 FROM leave_requests l WHERE l.id=? AND b.employee_id=l.employee_id AND b.leave_type_id=l.leave_type_id AND b.year=EXTRACT(YEAR FROM l.start_date)::int",
            leaveId);
    }
    private void consumeLeave(Long leaveId){
        queries.update("UPDATE leave_balances b SET pending_days=pending_days-l.working_days,used_days=used_days+l.working_days,version=b.version+1 FROM leave_requests l WHERE l.id=? AND b.employee_id=l.employee_id AND b.leave_type_id=l.leave_type_id AND b.year=EXTRACT(YEAR FROM l.start_date)::int",
            leaveId);
    }
}
