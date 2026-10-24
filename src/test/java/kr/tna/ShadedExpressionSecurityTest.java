package kr.tna;

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.lang.reflect.Method;
import org.apache.ibatis.ognl.OgnlRuntime;
import org.junit.jupiter.api.Test;

class ShadedExpressionSecurityTest {
    @Test
    void reflectionCannotProxyABlockedMethodInTheActualMybatisShadedEngine() throws Exception {
        Method blocked = Runtime.class.getMethod("getRuntime");
        assertThrows(IllegalAccessException.class, () -> OgnlRuntime.invokeMethod(null, blocked, new Object[0]));
        Method proxy = Method.class.getMethod("invoke", Object.class, Object[].class);
        assertThrows(IllegalAccessException.class,
                () -> OgnlRuntime.invokeMethod(blocked, proxy, new Object[]{null, new Object[0]}));
    }
}
