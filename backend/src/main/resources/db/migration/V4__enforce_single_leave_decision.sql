ALTER TABLE approval_history
    DROP CONSTRAINT uq_one_decision_per_step;

CREATE UNIQUE INDEX uq_one_leave_decision_per_step
    ON approval_history (leave_request_id, step_order)
    WHERE action IN ('APPROVED', 'REJECTED');
