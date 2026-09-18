package dev.gearreserve;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gearreserve.infrastructure.Database;
import dev.gearreserve.messaging.ReservationApprovedCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalOutboxHttpTest {
    @TempDir Path directory;
    @Test void httpCreationApprovalRepeatAndInvalidRequestsPreserveContract() throws Exception {
        Path path=directory.resolve("http.db");var server=App.createServer(0,path);server.start();
        try(var client=HttpClient.newHttpClient()) {
            String base="http://127.0.0.1:"+server.getAddress().getPort();Database db=new Database(path);
            var pending=post(client,base+"/reservations",body(2));
            assertEquals(201,pending.statusCode());assertTrue(pending.body().contains("\"status\":\"Pending\""));
            assertTrue(db.pendingOutbox(10).isEmpty());
            long id=new ObjectMapper().readTree(pending.body()).get("id").asLong();
            var first=post(client,base+"/reservations/"+id+"/approve","");
            var repeat=post(client,base+"/reservations/"+id+"/approve","");
            assertEquals(200,first.statusCode());assertEquals(first.body(),repeat.body());
            assertEquals(1,db.pendingOutbox(10).size());
            assertEquals(id,ReservationApprovedCodec.decode(db.pendingOutbox(10).getFirst().payload()).reservationId());
            var automatic=post(client,base+"/reservations",body(1));assertEquals(201,automatic.statusCode());
            assertTrue(automatic.body().contains("\"status\":\"Approved\""));assertEquals(2,db.pendingOutbox(10).size());
            assertEquals(404,post(client,base+"/reservations",body(999)).statusCode());
            assertEquals(400,post(client,base+"/reservations",body(1).replace("demo-http"," ")).statusCode());
            assertEquals(404,post(client,base+"/reservations/999/approve","").statusCode());
            assertEquals(2,db.pendingOutbox(10).size());
        } finally {server.stop(0);}
    }
    String body(int equipment){return "{\"equipmentId\":"+equipment+",\"requesterAlias\":\"demo-http\",\"startUtc\":\"2026-10-01T10:00:00Z\",\"endUtc\":\"2026-10-01T11:00:00Z\"}";}
    HttpResponse<String> post(HttpClient client,String uri,String body)throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
