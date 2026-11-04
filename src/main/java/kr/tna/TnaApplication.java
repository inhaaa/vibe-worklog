package kr.tna;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TnaApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("migrate")) {
            if (args.length != 1) {
                throw new IllegalArgumentException("migrate 명령은 추가 인수를 허용하지 않습니다.");
            }
            kr.tna.common.config.MigrationRunner.migrate(System.getenv());
            return;
        }
        if (args.length > 0 && args[0].equals("operator-bootstrap")) {
            kr.tna.console.OperatorBootstrap.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("local-demo-bootstrap")) {
            kr.tna.console.LocalDemoBootstrap.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("demo-approval-setup")) {
            kr.tna.console.LocalDemoApprovalSetup.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("demo-identity-setup")) {
            kr.tna.console.LocalDemoIdentitySetup.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(TnaApplication.class, args);
    }
}
