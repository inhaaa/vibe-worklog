package kr.tna;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class WorktimeSchemaIT {
    @Test void resultsSchemaExists(){var jdbc=new JdbcTemplate(DatabaseFixture.start().adminDataSource());assertNotNull(jdbc.queryForObject("SELECT to_regclass('tna.day_result')::text",String.class));}
}
