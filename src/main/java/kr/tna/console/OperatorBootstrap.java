package kr.tna.console;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.org.OrgCommands;

/** Explicit local CLI only. Never invoked by web requests or automatic application startup. */
public final class OperatorBootstrap {
    private OperatorBootstrap() { }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("운영자 로그인명을 한 개 지정해 주세요.");
        String url=System.getenv("TNA_JDBC_URL");
        if(url==null||!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|\\[::1\\]):[0-9]+/[A-Za-z0-9_-]+"))throw new IllegalArgumentException("명시적인 loopback 로컬 DB만 지원합니다.");
        var console=System.console();if(console==null)throw new IllegalStateException("비밀번호를 입력할 로컬 콘솔이 필요합니다.");
        char[] password=console.readPassword("신규 운영자 비밀번호: "),confirmation=console.readPassword("비밀번호 확인: ");
        try {
            if(password==null||confirmation==null||!Arrays.equals(password,confirmation))throw new IllegalArgumentException("비밀번호 확인이 일치하지 않습니다.");
            try(var connection=DriverManager.getConnection(url,"tna_migration",System.getenv("TNA_MIGRATION_DB_PASSWORD"))){create(connection,args[0],password);}
            System.out.println("로컬 운영자 등록을 완료했습니다.");
        }finally{if(password!=null)Arrays.fill(password,'\0');if(confirmation!=null)Arrays.fill(confirmation,'\0');}
    }
    public static UUID create(DataSource source,String login,char[] password)throws Exception {try(var connection=source.getConnection()){return create(connection,login,password);}}
    private static UUID create(Connection connection,String rawLogin,char[] password)throws Exception {
        String login=OrgCommands.requiredText(rawLogin,120,"운영자 로그인명").toLowerCase(Locale.ROOT);
        if(password==null||!PasswordPolicy.isValid(new String(password)))throw new IllegalArgumentException("비밀번호 정책을 확인해 주세요.");
        String hash=PasswordPolicy.passwordEncoder().encode(new String(password));UUID id=UUID.randomUUID();connection.setAutoCommit(false);
        try {
            try(var statement=connection.prepareStatement("INSERT INTO platform.operator_account(id,login_id,password_hash) VALUES (?,?,?)")){statement.setObject(1,id);statement.setString(2,login);statement.setString(3,hash);statement.executeUpdate();}
            try(var statement=connection.prepareStatement("INSERT INTO platform.operator_audit_event(id,operator_id,command_id,action,input_hash,reason,summary) VALUES (?,?,?,'OPERATOR_BOOTSTRAP',?,'명시적 로컬 운영자 등록','{}'::jsonb)")){statement.setObject(1,UUID.randomUUID());statement.setObject(2,id);statement.setObject(3,UUID.randomUUID());statement.setString(4,OrgCommands.fingerprint("OPERATOR_BOOTSTRAP",id,login));statement.executeUpdate();}
            connection.commit();return id;
        }catch(Exception e){connection.rollback();throw e;}
    }
}
