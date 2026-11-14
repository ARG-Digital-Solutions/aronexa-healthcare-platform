package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.TenantAuthorization;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PILOT-08 RC02B: deliberately default-off search of assigned-branch
 * registrations, through EXECUTE ONLY on a separately reviewed database
 * function. V9 disallows direct SELECT on patient and patient_identifier.
 *
 * No production definition or activation of that function ships with main
 * source; the prototype definition is in src/test/resources ONLY.
 * Name and MRN are identity data. Do not enable on real patients until a
 * security/privacy and revocation review approves the forward migration.
 */
@Service
public class PatientCandidateSearchService {
    private static final Pattern MRN=Pattern.compile("[A-Z0-9][A-Z0-9-]{2,47}");
    private static final Pattern NAME=Pattern.compile("[A-Za-z][A-Za-z .'-]{2,59}");
    private final TenantAuthorization authorization;
    private final PatientSqlScope sqlScope;
    private final JdbcTemplate jdbc;

    public PatientCandidateSearchService(TenantAuthorization authorization,
            PatientSqlScope sqlScope,
            @Qualifier("patientJdbcTemplate") JdbcTemplate jdbc) {
        this.authorization=authorization;
        this.sqlScope=sqlScope;
        this.jdbc=jdbc;
    }

    public record Match(UUID tenantId,UUID branchId,UUID registrationId,
                        UUID patientId,String displayName,String mrn) {}

    @Transactional(transactionManager="patientTransactionManager")
    public List<Match> search(UUID branch,String mode,String raw) {
        var actor=authorization.requirePermission("PATIENT_CANDIDATE_SEARCH");
        authorization.requirePermission("PATIENT_REGISTRATION_READ");
        if(branch==null||branch.equals(new UUID(0,0))
                ||mode==null||raw==null)
            throw new AccessDeniedException("Patient search denied");

        String term=raw.trim();
        if((!"MRN".equals(mode)&&!"NAME_PREFIX".equals(mode))
                ||("MRN".equals(mode)&&!MRN.matcher(term).matches())
                ||("NAME_PREFIX".equals(mode)&&!NAME.matcher(term).matches())) {
            throw new IllegalArgumentException("Invalid patient search criteria");
        }

        sqlScope.bindVerifiedStaff();
        // Independently verify live tenant module state and assigned branch
        // before invoking the privileged function. The function repeats
        // these checks, including PATIENT_CANDIDATE_SEARCH, and audits.
        List<Integer> enabled=jdbc.queryForList("""
            SELECT 1 FROM public.tenant_module_state s
              JOIN public.module_registry r ON r.code=s.module_code
             WHERE s.tenant_id=? AND s.module_code='PATIENT_REGISTRATION'
               AND r.implemented AND r.released AND s.entitled AND s.activated
               AND (NOT r.requires_clinical_authorization OR s.clinically_authorized)
            """,Integer.class,actor.tenantId());
        if(enabled.isEmpty())throw new AccessDeniedException("Patient search denied");
        List<Integer> assigned=jdbc.queryForList("""
            SELECT 1 FROM public.org_membership_branch_scope
             WHERE tenant_id=? AND branch_id=? AND membership_id=?
            """,Integer.class,actor.tenantId(),branch,actor.membershipId());
        if(assigned.isEmpty())throw new AccessDeniedException("Patient search denied");

        // Function is NOT installed by production Flyway. It exists in the
        // disposable PG17 candidate only. No direct SELECT on patient/identifier.
        var rows=jdbc.query("""
            SELECT tenant_id,branch_id,registration_id,patient_id,display_name,mrn
              FROM public.pilot08_find_patient_registration(?,?,?)
            """,(rs,i)->new Match(
                rs.getObject("tenant_id",UUID.class),
                rs.getObject("branch_id",UUID.class),
                rs.getObject("registration_id",UUID.class),
                rs.getObject("patient_id",UUID.class),
                rs.getString("display_name"),
                rs.getString("mrn")),branch,mode,term);
        if(rows.size()>10||rows.stream().anyMatch(row->
                !actor.tenantId().equals(row.tenantId())
                ||!branch.equals(row.branchId())))
            throw new AccessDeniedException("Patient search denied");
        return List.copyOf(rows);
    }
}

@RestController
@ConditionalOnProperty(name="aronexa.patient.search.reviewed-activation",
        havingValue="true",matchIfMissing=false)
@RequestMapping("/api/v1/branches/{branchId}/patient-search")
final class PatientCandidateSearchController {
    private final PatientCandidateSearchService service;

    PatientCandidateSearchController(PatientCandidateSearchService service) {
        this.service=service;
    }

    @GetMapping
    ResponseEntity<List<PatientCandidateSearchService.Match>> find(
            @PathVariable UUID branchId,
            @RequestParam String mode,@RequestParam String term) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.search(branchId,mode,term));
    }
}
