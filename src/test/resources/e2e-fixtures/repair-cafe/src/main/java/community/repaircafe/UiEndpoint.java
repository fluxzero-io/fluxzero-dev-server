package community.repaircafe;

import io.fluxzero.sdk.web.ServeStatic;
import org.springframework.stereotype.Component;

@Component
@ServeStatic(value = "/", resourcePath = "classpath:/static", ignorePaths = "/api/*", fallbackFile = "index.html")
public class UiEndpoint {
}
