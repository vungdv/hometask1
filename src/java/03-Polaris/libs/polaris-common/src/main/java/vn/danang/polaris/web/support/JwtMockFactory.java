package vn.danang.polaris.web.support;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
/*
Use final for java idiom: design and document for inheritance, or else prohibit it.
 */
public final class JwtMockFactory {
    public static JwtRequestPostProcessor user() {
        return SecurityMockMvcRequestPostProcessors.jwt();
    }
}
