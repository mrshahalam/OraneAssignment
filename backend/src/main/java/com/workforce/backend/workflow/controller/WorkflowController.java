package com.workforce.backend.workflow.controller;

import com.workforce.backend.auth.CurrentUser;
import com.workforce.backend.auth.Role;
import com.workforce.backend.common.BadRequestException;
import com.workforce.backend.common.repository.JpaQueryRepository;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final JpaQueryRepository queries;
    public WorkflowController(JpaQueryRepository queries) { this.queries=queries; }
    public record StepRequest(int stepOrder,String name,String approverType) {}
    public record WorkflowRequest(String name,List<StepRequest> steps) {}

    @GetMapping
    public List<Map<String,Object>> list() {
        return queries.queryForList("SELECT w.*,COALESCE((SELECT json_agg(json_build_object('stepOrder',s.step_order,'name',s.name,'approverType',s.approver_type) ORDER BY s.step_order) FROM approval_workflow_steps s WHERE s.workflow_id=w.id),'[]') steps FROM approval_workflows w");
    }

    @PutMapping
    @Transactional
    public Map<String,Object> configure(@RequestBody WorkflowRequest r) {
        if (CurrentUser.details().getRole()!=Role.HR_ADMIN)
            throw new org.springframework.security.access.AccessDeniedException("HR Administrator role required");
        if (r.steps()==null||r.steps().isEmpty()) throw new BadRequestException("At least one workflow step is required");
        queries.update("UPDATE approval_workflows SET active=FALSE");
        Long id=queries.queryForObject("INSERT INTO approval_workflows(name,active) VALUES(?,TRUE) RETURNING id",Long.class,r.name()==null?"Leave Workflow":r.name());
        int order=1;
        for(StepRequest s:r.steps()) {
            if(!Set.of("MANAGER","HR_ADMIN").contains(s.approverType()))
                throw new BadRequestException("Approver type must be MANAGER or HR_ADMIN");
            queries.update("INSERT INTO approval_workflow_steps(workflow_id,step_order,name,approver_type) VALUES(?,?,?,?)",
                id,order++,s.name(),s.approverType());
        }
        return queries.queryForMap("SELECT * FROM approval_workflows WHERE id=?",id);
    }
}
