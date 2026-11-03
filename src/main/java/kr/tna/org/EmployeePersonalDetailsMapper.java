package kr.tna.org;

import java.util.List;
import java.util.UUID;
import kr.tna.org.EmployeePersonalDetailsData.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface EmployeePersonalDetailsMapper {
    @Select("""
            SELECT admission_type,recognized_months,group_joined_on,english_name,marital_status,married_on,
                veteran_status,disability_status,military_status,military_branch,mobile_phone,postal_code,
                address,address_detail,contact_email,version AS expected_version,'' AS reason,NULL::uuid AS command_id
            FROM tna.employee_personal_details WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId}
            """)
    Form stored(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Insert("""
            INSERT INTO tna.employee_personal_details(tenant_id,employee_id,admission_type,recognized_months,
                group_joined_on,english_name,marital_status,married_on,veteran_status,disability_status,
                military_status,military_branch,mobile_phone,postal_code,address,address_detail,contact_email,
                version,updated_by)
            VALUES(#{tenantId},#{employeeId},#{data.admissionType},#{data.recognizedMonths},#{data.groupJoinedOn},
                #{data.englishName},#{data.maritalStatus},#{data.marriedOn},#{data.veteranStatus},#{data.disabilityStatus},
                #{data.militaryStatus},#{data.militaryBranch},#{data.mobilePhone},#{data.postalCode},#{data.address},
                #{data.addressDetail},#{data.contactEmail},#{version},#{actorId})
            ON CONFLICT(tenant_id,employee_id) DO UPDATE SET admission_type=excluded.admission_type,
                recognized_months=excluded.recognized_months,group_joined_on=excluded.group_joined_on,
                english_name=excluded.english_name,marital_status=excluded.marital_status,married_on=excluded.married_on,
                veteran_status=excluded.veteran_status,disability_status=excluded.disability_status,
                military_status=excluded.military_status,military_branch=excluded.military_branch,
                mobile_phone=excluded.mobile_phone,postal_code=excluded.postal_code,address=excluded.address,
                address_detail=excluded.address_detail,contact_email=excluded.contact_email,
                version=excluded.version,updated_at=CURRENT_TIMESTAMP,updated_by=excluded.updated_by
            WHERE tna.employee_personal_details.version=#{expectedVersion}
            """)
    int save(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("data") Form data, @Param("version") long version,
            @Param("expectedVersion") long expectedVersion, @Param("actorId") UUID actorId);

    @Select("""
            SELECT id,kind,title,detail,effective_from,effective_to,revision
            FROM tna.employee_personal_entry WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId}
            ORDER BY effective_from DESC,revision DESC
            """)
    List<Entry> entries(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Insert("""
            INSERT INTO tna.employee_personal_entry(tenant_id,id,employee_id,kind,title,detail,effective_from,
                effective_to,revision,created_by)
            VALUES(#{tenantId},#{id},#{employeeId},#{data.kind},#{data.title},#{data.detail},#{data.effectiveFrom},
                #{data.effectiveTo},#{revision},#{actorId})
            """)
    int append(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId, @Param("id") UUID id,
            @Param("data") EntryForm data, @Param("revision") long revision, @Param("actorId") UUID actorId);

    @Select("""
            SELECT a.id,o.name AS organization_name,a.valid_from,a.valid_to
            FROM tna.employee_assignment a
            JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
            WHERE a.tenant_id=#{tenantId} AND a.employee_id=#{employeeId}
            ORDER BY a.valid_from DESC,a.id
            """)
    List<Appointment> appointments(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);
}
