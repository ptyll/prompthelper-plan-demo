package dev.gearreserve.messaging;

import dev.gearreserve.infrastructure.Database;
import dev.gearreserve.domain.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OutboxPublisherTest {
    @TempDir Path directory;
    Database db() throws Exception {
        Database db=new Database(directory.resolve("publisher.db"));db.initialize();
        for(int i=0;i<2;i++) db.createReservation(1,"demo",Instant.parse("2026-10-01T10:00:00Z"),Instant.parse("2026-10-01T11:00:00Z"),ReservationStatus.Approved);
        return db;
    }
    @Test void failedSendStopsBatchWithoutMarking() throws Exception {
        Database db=db(); AtomicInteger sends=new AtomicInteger();
        OutboxPublisher publisher=new OutboxPublisher(OutboxPublisher.databaseStore(db),(t,k,p)->{
            sends.incrementAndGet();return CompletableFuture.failedFuture(new IllegalStateException("offline"));
        },ReservationApproved.DEFAULT_TOPIC,10);
        assertThrows(Exception.class,publisher::publishBatch);
        assertEquals(1,sends.get());assertEquals(2,db.pendingOutbox(10).size());
    }
    @Test void ackMustArriveBeforeMarkAndBatchIsBounded() throws Exception {
        Database db=db();CompletableFuture<Void> ack=new CompletableFuture<>();CompletableFuture<Void> sent=new CompletableFuture<>();
        var first=db.pendingOutbox(10).getFirst();
        OutboxPublisher publisher=new OutboxPublisher(OutboxPublisher.databaseStore(db),(t,k,p)->{
            assertEquals(first.key(),k);assertEquals(first.payload(),p);assertEquals(ReservationApproved.DEFAULT_TOPIC,t);
            sent.complete(null);return ack;
        },ReservationApproved.DEFAULT_TOPIC,1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var result=executor.submit(publisher::publishBatch); sent.get(5,TimeUnit.SECONDS);
            assertEquals(2,db.pendingOutbox(10).size());
            // A separate writer can commit while send is awaiting ACK: no DB transaction spans network.
            db.createReservation(2,"parallel",Instant.parse("2026-10-01T10:00:00Z"),Instant.parse("2026-10-01T11:00:00Z"),ReservationStatus.Pending);
            ack.complete(null);assertEquals(1,result.get(5,TimeUnit.SECONDS));
        }
        assertEquals(1,db.pendingOutbox(10).size());
    }
    @Test void ackThenFailedMarkReplaysIdenticalPayload() throws Exception {
        Database db=db();var delivered=new ArrayList<String>();
        OutboxPublisher.Store failMark=new OutboxPublisher.Store(){
            public java.util.List<Database.OutboxMessage> pending(int limit)throws Exception{return db.pendingOutbox(limit);}
            public void markPublished(long id)throws Exception{throw new java.sql.SQLException("crash after ACK");}
        };
        OutboxPublisher.Sender sender=(t,k,p)->{delivered.add(k+":"+p);return CompletableFuture.completedFuture(null);};
        assertThrows(Exception.class,()->new OutboxPublisher(failMark,sender,"topic",1).publishBatch());
        assertEquals(2,db.pendingOutbox(10).size());
        assertEquals(1,new OutboxPublisher(OutboxPublisher.databaseStore(db),sender,"topic",1).publishBatch());
        assertEquals(delivered.get(0),delivered.get(1));
        assertEquals(1,db.pendingOutbox(10).size());
    }
    @Test void producerRequiresAllAcknowledgements() {
        var props=KafkaOutboxPublisher.producerProperties("127.0.0.1:19092");
        assertEquals("all",props.getProperty("acks"));assertEquals("true",props.getProperty("enable.idempotence"));
        assertThrows(NullPointerException.class,()->new OutboxPublisher(null,(t,k,p)->null,"topic",1));
    }
}
