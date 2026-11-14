package com.argdigital.aronexa.patient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real loopback HTTP through a source-owned default-off endpoint, plus
 * PostgreSQL 17 nonowner patient SQL EXECUTE-only role on disposable database.
 * Never applies any RC02B SQL to real/installed/original DB.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"server.address=127.0.0.1","server.error.include-message=never",
            "aronexa.patient.search.reviewed-activation=true"})
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientCandidateSearchHttpAcceptanceTests {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_rc02b_disposable_only")
            .withUsername("aronexa_rc02b_disposable_only")
            .withPassword("temporary-rc02b-owner-only");

    @Autowired JdbcTemplate admin;
    @Autowired DataSource ownerDataSource;
    @Autowired PasswordEncoder passwords;
    @Value("${local.server.port}") int port;
    private final HttpClient client=HttpClient.newHttpClient();

    @BeforeAll
    void installSearchOnlyInDisposablePostgres() throws Exception {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(postgres.getDatabaseName())
                .isEqualTo("aronexa_rc02b_disposable_only");
        try(Connection c=ownerDataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                ScriptUtils.executeSqlScript(c,new EncodedResource(
                        new ClassPathResource("db/test/pilot08-rc02b-patient-search.sql")),
                        false,false,ScriptUtils.DEFAULT_COMMENT_PREFIX,
                        ScriptUtils.EOF_STATEMENT_SEPARATOR,
                        ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                        ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
                c.commit();
            } catch(Exception e) {
                c.rollback();throw e;
            }
        }
    }

    record Fixture(UUID tenant,UUID branch,UUID otherBranch,UUID membership,
            UUID registration,UUID patient,String mrn,String name,
            String tenantCode,String username,String password) {}

    private Fixture seed(boolean searchPermission,boolean readPermission,
            boolean moduleEnabled,boolean forcedPassword) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        UUID tenant=UUID.randomUUID(),facility=UUID.randomUUID(),
            branch=UUID.randomUUID(),otherBranch=UUID.randomUUID(),
            user=UUID.randomUUID(),membership=UUID.randomUUID(),
            role=UUID.randomUUID(),patient=UUID.randomUUID(),
            registration=UUID.randomUUID();
        String mrn="PAT-"+suffix.toUpperCase()+"001";
        String name="Fictional Search "+suffix;
        String code="s_"+suffix,username="reception_"+suffix;
        String password="Synthetic-Rc02b-Only123!";
        admin.update("INSERT INTO tenant(id,code,display_name) VALUES (?,?,?)",
                tenant,code,"Fictional search clinic");
        admin.update("""
            INSERT INTO facility(id,tenant_id,code,display_name,facility_kind)
            VALUES(?,?,?,'Fictional','CLINIC')
            """,facility,tenant,"f_"+suffix);
        admin.update("""
            INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
            VALUES(?,?,?,?,'Test branch')
            """,branch,tenant,facility,"b_"+suffix);
        admin.update("""
            INSERT INTO branch(id,tenant_id,facility_id,code,display_name)
            VALUES(?,?,?,?,'Foreign branch')
            """,otherBranch,tenant,facility,"o_"+suffix);
        admin.update("""
            INSERT INTO app_user(id,username,password_hash,must_change_password)
            VALUES(?,?,?,?)
            """,user,username,passwords.encode(password),forcedPassword);
        admin.update("""
            INSERT INTO user_tenant_membership(id,user_id,tenant_id)
            VALUES(?,?,?)
            """,membership,user,tenant);
        admin.update("""
            INSERT INTO auth_role(id,tenant_id,code,display_name)
            VALUES (?,?,'RECEPTION','Fictional receptionist')
            """,role,tenant);
        if(searchPermission)admin.update("""
            INSERT INTO auth_role_permission(tenant_id,role_id,permission_code)
            VALUES (?,?,'PATIENT_CANDIDATE_SEARCH')
            """,tenant,role);
        if(readPermission)admin.update("""
            INSERT INTO auth_role_permission(tenant_id,role_id,permission_code)
            VALUES (?,?,'PATIENT_REGISTRATION_READ')
            """,tenant,role);
        admin.update("""
            INSERT INTO auth_membership_role(tenant_id,membership_id,role_id)
            VALUES(?,?,?)
            """,tenant,membership,role);
        admin.update("""
            INSERT INTO org_membership_branch_scope(tenant_id,membership_id,branch_id)
            VALUES (?,?,?)
            """,tenant,membership,branch);
        admin.update("""
            INSERT INTO patient(id,tenant_id,display_name)
            VALUES (?,?,?)
            """,patient,tenant,name);
        admin.update("""
            INSERT INTO patient_identifier(id,tenant_id,patient_id,identifier_type,identifier_value)
            VALUES (?,?,?,'MRN',?)
            """,UUID.randomUUID(),tenant,patient,mrn);
        admin.update("""
            INSERT INTO patient_registration(
                id,tenant_id,patient_id,branch_id,
                registration_source,registered_by_membership_id)
            VALUES (?,?,?,?,'RECEPTION',?)
            """,registration,tenant,patient,branch,membership);
        if(moduleEnabled) {
            admin.update("""
                UPDATE module_registry SET implemented=true,released=true
                WHERE code='PATIENT_REGISTRATION'
                """);
            admin.update("""
                INSERT INTO tenant_module_state
                (tenant_id,module_code,entitled,activated,clinically_authorized)
                VALUES (?,'PATIENT_REGISTRATION',true,true,true)
                """,tenant);
        }
        return new Fixture(tenant,branch,otherBranch,membership,
                registration,patient,mrn,name,code,username,password);
    }

    private HttpResponse<String> request(String method,String path,
            String bearer,String body) throws Exception {
        var b=HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:"+port+path))
                .header("Accept","application/json");
        if(bearer!=null)b.header("Authorization","Bearer "+bearer);
        if(method.equals("POST"))b.header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        else b.GET();
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String login(Fixture f) throws Exception {
        String request="{\"tenantCode\":\""+f.tenantCode()+"\",\"username\":\""+
                f.username()+"\",\"password\":\""+f.password()+"\"}";
        var r=request("POST","/api/v1/auth/login",null,request);
        assertThat(r.statusCode()).isEqualTo(200);
        var m=Pattern.compile("\"accessToken\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"")
                .matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
    private HttpResponse<String> search(UUID branch,String mode,String term,
            String token) throws Exception {
        return request("POST","/api/v1/branches/"+branch+"/patient-search",
            token,"{\"mode\":\""+mode+"\",\"term\":\""+term+"\"}");
    }
    private int audits(Fixture f) {
        return admin.queryForObject("""
            SELECT count(*) FROM pilot08_patient_search_audit
            WHERE tenant_id=? AND membership_id=?
            """,Integer.class,f.tenant(),f.membership());
    }

    @Test
    void realHttpExactMrnAndNamePrefixReturnOnlyBranchRegistrationAndAudit()
            throws Exception {
        Fixture f=seed(true,true,true,false);
        String token=login(f);
        for(var criteria:new String[][]{
            {"MRN",f.mrn()},{"NAME_PREFIX","Fictional Search"}}) {
            var found=search(f.branch(),criteria[0],criteria[1],token);
            assertThat(found.statusCode()).as(found.body()).isEqualTo(200);
            assertThat(found.headers().firstValue("Cache-Control"))
                    .contains("no-store");
            assertThat(found.body()).contains(
                    f.name(),f.mrn(),f.patient().toString(),
                    f.registration().toString(),f.branch().toString());
            assertThat(found.body()).doesNotContain(
                    "password","accessToken","membershipId","dateOfBirth",
                    "phone","contact","birthDate","medical");
        }
        assertThat(audits(f)).isEqualTo(2);
        var empty=search(f.branch(),"MRN","NOT-FOUND-999",token);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(empty.body()).isEqualTo("[]");
        assertThat(audits(f)).isEqualTo(3);
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM pilot08_patient_search_audit
            WHERE tenant_id=? AND membership_id=? AND result_count=0
            """,Integer.class,f.tenant(),f.membership())).isOne();
    }

    @Test
    void anonymousWrongBranchTenantAndRevokedBranchDoNotExposePatients()
            throws Exception {
        Fixture f=seed(true,true,true,false);
        Fixture foreign=seed(true,true,true,false);
        assertThat(search(f.branch(),"MRN",f.mrn(),null).statusCode())
                .isEqualTo(401);
        String token=login(f);
        assertThat(search(f.otherBranch(),"MRN",f.mrn(),token).statusCode())
                .isEqualTo(403);
        assertThat(search(foreign.branch(),"MRN",foreign.mrn(),token).statusCode())
                .isEqualTo(403);
        assertThat(audits(f)).isZero();
        assertThat(audits(foreign)).isZero();
        admin.update("""
            DELETE FROM org_membership_branch_scope
            WHERE tenant_id=? AND branch_id=? AND membership_id=?
            """,f.tenant(),f.branch(),f.membership());
        assertThat(search(f.branch(),"MRN",f.mrn(),token).statusCode())
                .isEqualTo(403);
        assertThat(audits(f)).isZero();
        assertThat(request("POST","/api/v1/auth/logout",token,"{}")
                .statusCode()).isEqualTo(204);
        assertThat(search(f.branch(),"MRN",f.mrn(),token).statusCode())
                .isEqualTo(401);
    }

    @Test
    void liveSearchPermissionRevocationImmediatelyDeniesSameBearer()
            throws Exception {
        Fixture f=seed(true,true,true,false);
        String bearer=login(f);
        assertThat(search(f.branch(),"MRN",f.mrn(),bearer).statusCode())
                .isEqualTo(200);
        assertThat(audits(f)).isOne();
        admin.update("""
            DELETE FROM auth_role_permission rp
            USING auth_membership_role mr
            WHERE rp.tenant_id=mr.tenant_id AND rp.role_id=mr.role_id
              AND mr.tenant_id=? AND mr.membership_id=?
              AND rp.permission_code='PATIENT_CANDIDATE_SEARCH'
            """,f.tenant(),f.membership());
        assertThat(search(f.branch(),"MRN",f.mrn(),bearer).statusCode())
                .isEqualTo(403);
        assertThat(audits(f)).isOne();
    }

    @Test
    void bothPermissionsModuleAndForcedPasswordAreIndependentlyRequired()
            throws Exception {
        for(int mode=0;mode<4;mode++) {
            Fixture f=switch(mode) {
                case 0 -> seed(false,true,true,false);
                case 1 -> seed(true,false,true,false);
                case 2 -> seed(true,true,false,false);
                default -> seed(true,true,true,true);
            };
            assertThat(search(f.branch(),"MRN",f.mrn(),login(f)).statusCode())
                    .isEqualTo(403);
            assertThat(audits(f)).isZero();
        }
    }

    @Test
    void unsafeWildcardShortOrMalformedQueriesRejectBeforeSearch() throws Exception {
        Fixture f=seed(true,true,true,false);
        String token=login(f);
        for(var input:new String[][]{
            {"NAME_PREFIX","Fi"},
            {"NAME_PREFIX","Fic%"},
            {"NAME_PREFIX","Fic_"},
            {"NAME_PREFIX","Fic; DROP"},
            {"MRN","%"},
            {"MRN","ab"},
            {"MRN","A'. OR TRUE"},
            {"UNREVIEWED","ABC"}}) {
            var r=search(f.branch(),input[0],input[1],token);
            assertThat(r.statusCode()).as(input[1]).isEqualTo(400);
        }
        assertThat(audits(f)).isZero();
    }

    @Test
    void patientRoleHasFunctionExecuteButNeverDirectIdentitySelect() {
        for(String table:new String[]{"patient","patient_identifier",
                "patient_contact","patient_relationship","patient_identity_audit",
                "pilot08_patient_search_audit"}) {
            assertThat(admin.queryForObject("""
                SELECT has_table_privilege('aronexa_patient_runtime',?, 'SELECT')
                """,Boolean.class,"public."+table)).as(table).isFalse();
        }
        assertThat(admin.queryForObject("""
            SELECT has_function_privilege(
             'aronexa_patient_runtime',
             'public.pilot08_find_patient_registration(uuid,text,text)','EXECUTE')
            """,Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
            SELECT rolsuper OR rolbypassrls
            FROM pg_roles WHERE rolname='aronexa_patient_runtime'
            """,Boolean.class)).isFalse();
    }
}
