package io.earthplatform.minecraft.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class PerfReportsTest {
    @Test void onlyHttpsOrThisComputerIsAReceiver() {
        assertNull(PerfReports.endpoint(""));
        assertNull(PerfReports.endpoint("http://example.com/report"));
        assertNull(PerfReports.endpoint("https://user:pass@example.com/report"));
        assertNull(PerfReports.endpoint("file:///tmp/x"));
        assertNotNull(PerfReports.endpoint("https://example.com/report"));
        assertNotNull(PerfReports.endpoint("http://127.0.0.1:8787/report"));
    }
    @Test void sendsTheLoggedLinesOnlyWhileOn() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        BlockingQueue<String> bodies=new LinkedBlockingQueue<>();
        server.createContext("/report",exchange->{
            try(var in=new GZIPInputStream(exchange.getRequestBody())){bodies.add(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
            exchange.sendResponseHeaders(204,-1);exchange.close();
        });
        server.start();
        AtomicBoolean on=new AtomicBoolean(false);
        try(PerfReports reports=new PerfReports(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/report"),on::get,"{\"type\":\"build\"}",200)) {
            reports.offer("{\"type\":\"window\",\"n\":0}");
            assertNull(bodies.poll(600,TimeUnit.MILLISECONDS),"nothing is sent while off");
            on.set(true);
            reports.offer("{\"type\":\"window\",\"n\":1}");reports.offer("{\"type\":\"window\",\"n\":2}");
            String body=bodies.poll(5,TimeUnit.SECONDS);
            assertNotNull(body);
            assertTrue(body.startsWith("{\"schema\":1,\"session\":\""+reports.session()+"\",\"build\":{\"type\":\"build\"},\"windows\":["),body);
            assertTrue(body.contains("\"n\":1")&&body.contains("\"n\":2")&&!body.contains("\"n\":0"),body);
            reports.offer("{\"type\":\"window\",\"n\":3}");on.set(false);reports.offer("{\"type\":\"window\",\"n\":4}");
            assertNull(bodies.poll(600,TimeUnit.MILLISECONDS),"turning off drops the held batch");
            assertEquals(1,reports.sent());
        } finally { server.stop(0); }
    }
}
