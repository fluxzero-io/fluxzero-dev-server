@RegisterType
@ApiDoc
@ApiDocInfo(
        title = "Repair Café Work Queue API",
        version = "1.0",
        description = """
                Register repair tickets, inspect the work queue, and manage repair lifecycles.
                """,
        serveOpenApi = true,
        serveApiReference = true)
@NoUserRequired
@Path("/api")
package community.repaircafe;

import io.fluxzero.common.serialization.RegisterType;
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;
import io.fluxzero.sdk.web.ApiDoc;
import io.fluxzero.sdk.web.ApiDocInfo;
import io.fluxzero.sdk.web.Path;
