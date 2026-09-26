package asia.jppwl.bridge.api;

import java.io.InputStream;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

/**
 * 前端静态单页与 AudioWorklet 处理器静态资源服务.
 */
@Path("/")
@ApplicationScoped
@RegisterForReflection
public class WebCallPageResource {

    @GET
    @Path("/call")
    @Produces("text/html;charset=UTF-8")
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

    @GET
    @Path("/audio-processor.js")
    @Produces("application/javascript;charset=UTF-8")
    public Response getAudioProcessorJs() {
        InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/resources/audio-processor.js");

        if (is == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return Response.ok(is).build();
    }

    @GET
    @Path("/audio-playback-processor.js")
    @Produces("application/javascript;charset=UTF-8")
    public Response getAudioPlaybackProcessorJs() {
        InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/resources/audio-playback-processor.js");

        if (is == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return Response.ok(is).build();
    }
}
