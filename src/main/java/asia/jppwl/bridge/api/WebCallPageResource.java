package asia.jppwl.bridge.api;

import java.io.InputStream;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * H5 极简实时通话前端单页接入端点 (SPA Page Endpoint).
 */
@Path("/call")
@ApplicationScoped
@RegisterForReflection
public class WebCallPageResource {

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response getCallPage() {
        InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/resources/call.html");

        if (is == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("<h1>Call page resource not found.</h1>")
                    .build();
        }

        return Response.ok(is).build();
    }
}
