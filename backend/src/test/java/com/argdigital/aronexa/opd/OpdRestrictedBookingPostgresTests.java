package com.argdigital.aronexa.opd;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Restricted SQL command evidence only on automatically disposed PostgreSQL 17.
 * Uses independent LOGIN and random ephemeral key; no production Flyway,
 * clinic database, developer login or publicly routable HTTP endpoint.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpdRestrictedBookingPostgresTests {
    @Container @ServiceConnection
    static PostgreSQLContainer pg=new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("db/test/role-bootstrap.sql")
            .withDatabaseName("aronexa_r110b2_disposable_only")
            .withUsername("aronexa_r110b2_temporary")
            .withPassword("testcontainer-only-not-production");

    @Autowired JdbcTemplate admin;
    private JdbcTemplate limited;
    private byte[] hmacKey;
    private String runtimePassword;
    private final LocalDate day=LocalDate.of(2026,11,13);
    private final LocalTime slot=LocalTime.of(10,0);

    private static Path reviewFile(String filename) {
        Path path=Path.of("deploy","opd","review-only",filename).toAbsolutePath();
        if(!Files.isRegularFile(path)) {
            path=Path.of("..","deploy","opd","review-only",filename).toAbsolutePath();
        }
        if(!Files.isRegularFile(path)) throw new IllegalStateException("Missing reviewed SQL: "+filename);
        return path;
    }

    private void executeOnlyInTestcontainer(String file) throws Exception {
        try(Connection c=admin.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c,new EncodedResource(
                new FileSystemResource(reviewFile(file))),false,false,
                ScriptUtils.DEFAULT_COMMENT_PREFIX,ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
        }
    }

    @BeforeAll
    void stageStrictlyDisposableRolesAndSchema() throws Exception {
        executeOnlyInTestcontainer("V11__opd_appointment_schema_candidate.sql");
        executeOnlyInTestcontainer("R110B2__restricted_booking_writer_candidate.sql");
        hmacKey=new byte[32];
        new SecureRandom().nextBytes(hmacKey);
        admin.update("""
            INSERT INTO opd_candidate.command_key(id,secret,enabled,expires_at)
            VALUES (1,?,true,clock_timestamp()+interval '1 hour')
            """,hmacKey);
        // Password is random AND only generated inside this throwaway container.
        byte[] raw=new byte[24];
        new SecureRandom().nextBytes(raw);
        String password=HexFormat.of().formatHex(raw);
        runtimePassword=password;
        admin.execute("ALTER ROLE aronexa_opd_runtime LOGIN PASSWORD '"+password+"'");
        limited=new JdbcTemplate(new DriverManagerDataSource(
                pg.getJdbcUrl(),"aronexa_opd_runtime",password));
        admin.update("""
            INSERT INTO public.auth_permission(code,description) VALUES
             ('APPOINTMENT_BOOK','Disposable R110B2 book test'),
             ('APPOINTMENT_CHECK_IN','Disposable R110B2 check-in test')
            ON CONFLICT(code) DO NOTHING
            """);
        admin.update("UPDATE public.module_registry SET implemented=true,released=true WHERE code='OPD'");
    }

    record Fixture(UUID tenant,UUID branch,UUID registration,UUID patient,
                   UUID staff,UUID session,UUID doctor) {}

    private UUID user(UUID tenant) {
        UUID user=UUID.randomUUID(),member=UUID.randomUUID();
        admin.update("""
            INSERT INTO public.app_user(id,username,password_hash,must_change_password)
            VALUES (?,?,?,false)
            """,user,"b2_"+user.toString().substring(0,12),"fictional-password-never-used");
        admin.update("""
            INSERT INTO public.user_tenant_membership(id,user_id,tenant_id)
            VALUES(?,?,?)
            """,member,user,tenant);
        return member;
    }

    private Fixture fixture(){
        UUID tenant=UUID.randomUUID(),facility=UUID.randomUUID();
        UUID branch=UUID.randomUUID(),patient=UUID.randomUUID();
        UUID registration=UUID.randomUUID(),session=UUID.randomUUID();
        admin.update("INSERT INTO public.tenant(id,code,display_name) VALUES(?,?,?)",
                tenant,"b2_"+tenant.toString().substring(0,10),"FICTIONAL tenant");
        admin.update("""
            INSERT INTO public.tenant_module_state
              (tenant_id,module_code,entitled,activated,clinically_authorized)
            VALUES (?,'OPD',true,true,true)
            """,tenant);
        admin.update("""
            INSERT INTO public.facility(id,tenant_id,code,display_name,facility_kind)
            VALUES (?,?,?,'FICTIONAL facility','CLINIC')
            """,facility,tenant,"f_"+facility.toString().substring(0,8));
        admin.update("""
            INSERT INTO public.branch(id,tenant_id,facility_id,code,display_name)
            VALUES (?,?,?,?,'FICTIONAL branch')
            """,branch,tenant,facility,"b_"+branch.toString().substring(0,8));
        UUID staff=user(tenant),doctor=user(tenant);
        UUID receptionRole=UUID.randomUUID(),doctorRole=UUID.randomUUID();
        admin.update("""
            INSERT INTO public.auth_role(id,tenant_id,code,display_name)
            VALUES (?,?,'RECEPTION','FICTIONAL receptionist')
            """,receptionRole,tenant);
        admin.update("""
            INSERT INTO public.auth_role(id,tenant_id,code,display_name)
            VALUES (?,?,'DOCTOR','FICTIONAL doctor')
            """,doctorRole,tenant);
        admin.update("""
            INSERT INTO public.auth_membership_role(tenant_id,membership_id,role_id)
            VALUES(?,?,?)
            """,tenant,staff,receptionRole);
        admin.update("""
            INSERT INTO public.auth_membership_role(tenant_id,membership_id,role_id)
            VALUES(?,?,?)
            """,tenant,doctor,doctorRole);
        admin.update("""
            INSERT INTO public.auth_role_permission(tenant_id,role_id,permission_code)
            VALUES (?,?,'APPOINTMENT_BOOK'),(?,?,'APPOINTMENT_CHECK_IN')
            """,tenant,receptionRole,tenant,receptionRole);
        admin.update("""
            INSERT INTO public.org_membership_branch_scope
              (tenant_id,membership_id,branch_id)
            VALUES (?,?,?),(?,?,?)
            """,tenant,staff,branch,tenant,doctor,branch);
        admin.update("""
            INSERT INTO public.auth_session(id,tenant_id,membership_id,
                       token_sha256,expires_at)
            VALUES (?,?,?,?,clock_timestamp()+interval '1 hour')
            """,session,tenant,staff,
                UUID.randomUUID().toString().replace("-","")
                 +UUID.randomUUID().toString().replace("-",""));
        admin.update("""
            INSERT INTO public.patient(id,tenant_id,display_name)
            VALUES (?,?,'FICTIONAL - NOT A PATIENT')
            """,patient,tenant);
        admin.update("""
            INSERT INTO public.patient_registration
              (id,tenant_id,patient_id,branch_id,registration_source,
               registered_by_membership_id)
            VALUES (?,?,?,?,'RECEPTION',?)
            """,registration,tenant,patient,branch,staff);
        return new Fixture(tenant,branch,registration,patient,staff,session,doctor);
    }

    private Map<String,Object> book(Fixture f,UUID request,UUID appointment,
            UUID nonce,UUID patient,String proofOverride,LocalTime at) {
        long expiration=Instant.now().plusSeconds(30).getEpochSecond();
        String proof=proofOverride==null
            ?OpdCommandProof.booking(hmacKey,1,f.tenant(),f.staff(),
               f.session(),f.branch(),f.registration(),patient,f.doctor(),
               day,at,request,appointment,nonce,expiration)
            :proofOverride;
        return limited.queryForMap("""
            SELECT * FROM opd_candidate.book_guarded(
             ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,f.tenant(),f.staff(),f.session(),f.branch(),f.registration(),
            patient,f.doctor(),day,at,request,appointment,1,nonce,expiration,proof);
    }

    private Map<String,Object> book(Fixture f,UUID request,UUID appointment) {
        return book(f,request,appointment,UUID.randomUUID(),f.patient(),null,slot);
    }

    private Map<String,Object> checkIn(Fixture f,UUID appointment,UUID request,
            int expected,String proofOverride) {
        long expiration=Instant.now().plusSeconds(30).getEpochSecond();
        UUID nonce=UUID.randomUUID();
        String proof=proofOverride==null
            ?OpdCommandProof.checkIn(hmacKey,1,f.tenant(),f.staff(),
                f.session(),f.branch(),appointment,expected,request,nonce,expiration)
            :proofOverride;
        return limited.queryForMap("""
            SELECT * FROM opd_candidate.checkin_guarded(?,?,?,?,?,?,?,?,?,?,?)
            """,f.tenant(),f.staff(),f.session(),f.branch(),appointment,
             expected,request,1,nonce,expiration,proof);
    }

    @Test
    void actualRestrictedLoginBooksChecksInAndAuditsCommittedAppointment() throws Exception {
        Fixture f=fixture();
        UUID id=UUID.randomUUID();
        var receipt=book(f,UUID.randomUUID(),id);
        assertThat(receipt.get("result_appointment_id")).isEqualTo(id);
        assertThat(receipt.get("result_stage")).isEqualTo("BOOKED");
        UUID request=UUID.randomUUID();
        var checked=checkIn(f,id,request,1,null);
        assertThat(checked.get("result_stage")).isEqualTo("CHECKED_IN");
        var replay=checkIn(f,id,request,1,null);
        assertThat(replay.get("result_replayed")).isEqualTo(true);
        // Separate physical database connection verifies committed data/audit.
        try(Connection c=DriverManager.getConnection(
                pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())){
            try(var ps=c.prepareStatement("""
                SELECT status,revision FROM opd_candidate.appointment WHERE id=?
                """)){
                ps.setObject(1,id);
                try(var rs=ps.executeQuery()){
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("CHECKED_IN");
                    assertThat(rs.getInt(2)).isEqualTo(2);
                }
            }
        }
        assertThat(admin.queryForObject("""
            SELECT count(*) FROM opd_candidate.appointment_event
            WHERE appointment_id=?
            """,Integer.class,id)).isEqualTo(2);
    }

    @Test
    void stolenRuntimeLoginCannotReadOrModifyTablesEvenWithForgedTenantGuc() throws Exception {
        Fixture f=fixture();
        assertThatThrownBy(()->limited.queryForList("""
            SELECT * FROM opd_candidate.appointment
            """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->limited.update("""
            INSERT INTO opd_candidate.command_key(id,secret,expires_at)
            VALUES(7,?,clock_timestamp()+interval '1 hour')
            """,hmacKey)).isInstanceOf(DataAccessException.class);
        // Even after spoofing a tenant GUC, the restricted LOGIN has no DML.
        try(Connection connection=DriverManager.getConnection(pg.getJdbcUrl(),
                "aronexa_opd_runtime",runtimePassword);
            var statement=connection.createStatement()){
            statement.execute("SELECT set_config('aronexa.tenant_id','"
                    +f.tenant()+"',false)");
            assertThatThrownBy(()->statement.executeUpdate(
                    "INSERT INTO opd_candidate.appointment(id) VALUES "
                    +"('00000000-0000-0000-0000-000000000001')"))
                .isInstanceOf(SQLException.class);
        }
        assertThat(admin.queryForObject("""
            SELECT rolbypassrls OR rolsuper FROM pg_catalog.pg_roles
            WHERE rolname='aronexa_opd_runtime'
            """,Boolean.class)).isFalse();
    }

    @Test
    void forgeryWrongRegistrationPatientAndReuseNonceCannotBook(){
        Fixture f=fixture();
        assertThatThrownBy(()->book(f,UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),f.patient(),"0".repeat(64),slot))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->book(f,UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),null,slot))
            .isInstanceOf(DataAccessException.class);
        UUID nonce=UUID.randomUUID();
        book(f,UUID.randomUUID(),UUID.randomUUID(),nonce,f.patient(),null,slot);
        assertThatThrownBy(()->book(f,UUID.randomUUID(),UUID.randomUUID(),
            nonce,f.patient(),null,LocalTime.of(10,15)))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    void idempotentBookingAndDoubleDoctorSlotConflictAreDatabaseEnforced(){
        Fixture f=fixture();
        UUID request=UUID.randomUUID(),id=UUID.randomUUID();
        book(f,request,id);
        var retry=book(f,request,UUID.randomUUID());
        assertThat(retry.get("result_appointment_id")).isEqualTo(id);
        assertThat(retry.get("result_replayed")).isEqualTo(true);
        assertThatThrownBy(()->book(f,UUID.randomUUID(),UUID.randomUUID()))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    void revocationPreventsBookingAndCheckInEvenWithValidSignedCommand(){
        Fixture f=fixture();
        UUID id=UUID.randomUUID();
        book(f,UUID.randomUUID(),id);
        admin.update("""
            UPDATE public.auth_session SET revoked_at=clock_timestamp()
            WHERE id=?
            """,f.session());
        assertThatThrownBy(()->book(f,UUID.randomUUID(),UUID.randomUUID(),
             UUID.randomUUID(),f.patient(),null,LocalTime.of(10,15)))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->checkIn(f,id,UUID.randomUUID(),1,null))
            .isInstanceOf(DataAccessException.class);
        assertThat(admin.queryForObject("""
            SELECT revision FROM opd_candidate.appointment WHERE id=?
            """,Integer.class,id)).isEqualTo(1);
    }
}
