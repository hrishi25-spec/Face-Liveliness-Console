package io.mosip.liveness.crud;

import io.mosip.liveness.models.entity.ConfigPolicy;
import io.mosip.liveness.models.enums.WorkflowType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConfigPolicyRepository extends JpaRepository<ConfigPolicy, UUID> {

    Optional<ConfigPolicy> findByWorkflowType(WorkflowType workflowType);
}
