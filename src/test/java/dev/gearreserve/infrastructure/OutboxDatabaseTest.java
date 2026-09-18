package dev.gearreserve.infrastructure;

import dev.gearreserve.domain.ReservationStatus;
import dev.gearreserve.messaging.ReservationApprovedCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OutboxDatabaseTest {
    @TempDir Path directory;
    Path path() { return directory.resolve("test.db"); }
    Database db() throws Exception { Database db = new Database(path()); db.initialize(); return db; }
    long create(Database db, ReservationStatus status) throws Exception {
        return db.createReservation(2,"demo",Instant.parse("2026-10-01T10:00:00Z"),Instant.parse("2026-10-01T11:00:00Z"),status).id();
    }
    void sql(String sql) throws Exception {
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path()); var s=c.createStatement()){s.execute(sql);}
    }
    @Test void autoApprovalAndTransitionEmitOnceWithStablePayload() throws Exception {
        Database db=db(); long approved=create(db,ReservationStatus.Approved);
        long pending=create(db,ReservationStatus.Pending);
        assertEquals(1,db.pendingOutbox(10).size());
        String original=db.pendingOutbox(10).getFirst().payload();
        db.approveReservation(approved); db.approveReservation(pending); db.approveReservation(pending);
        assertTrue(db.approveReservation(999).isEmpty());
        var rows=db.pendingOutbox(10); assertEquals(2,rows.size());
        assertEquals(original,rows.getFirst().payload());
        assertEquals(approved,ReservationApprovedCodec.decode(original).reservationId());
        db.markPublished(rows.getFirst().id());
        assertEquals(1,db.pendingOutbox(10).size());
        db.initialize(); db.approveReservation(approved);
        assertEquals(1,db.pendingOutbox(10).size());
        assertThrows(SQLException.class,()->sql("UPDATE outbox SET payload='changed'"));
        assertThrows(SQLException.class,()->sql("INSERT INTO outbox(event_id,reservation_id,event_type,payload) SELECT 'other',reservation_id,event_type,payload FROM outbox LIMIT 1"));
        assertThrows(SQLException.class,()->sql("INSERT INTO outbox(event_id,reservation_id,event_type,payload) SELECT event_id,999,event_type,payload FROM outbox LIMIT 1"));
    }
    @Test void outboxInsertFailureRollsBackCreationAndApproval() throws Exception {
        Database db=db(); long pending=create(db,ReservationStatus.Pending);
        sql("CREATE TRIGGER fail_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'test failure'); END");
        assertThrows(SQLException.class,()->create(db,ReservationStatus.Approved));
        assertThrows(SQLException.class,()->db.approveReservation(pending));
        assertEquals(1,db.listReservations().size());
        assertEquals(ReservationStatus.Pending,db.findReservation(pending).orElseThrow().status());
        assertTrue(db.pendingOutbox(10).isEmpty());
    }
    @Test void concurrentApprovalProducesSingleEvent() throws Exception {
        Database db=db();long id=create(db,ReservationStatus.Pending);
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<ReservationStatus>>();
            for(int i=0;i<12;i++) futures.add(pool.submit(()->{start.await();return db.approveReservation(id).orElseThrow().status();}));
            start.countDown();
            for(var future:futures) assertEquals(ReservationStatus.Approved,future.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,db.pendingOutbox(10).size());
    }
    @Test void legacySchemaAndExistingApprovedRowsRemainWithoutBackfill() throws Exception {
        sql("CREATE TABLE equipment(id INTEGER PRIMARY KEY,name TEXT NOT NULL,requires_approval INTEGER NOT NULL)");
        sql("CREATE TABLE reservations(id INTEGER PRIMARY KEY AUTOINCREMENT,equipment_id INTEGER NOT NULL,requester_alias TEXT NOT NULL,start_utc TEXT NOT NULL,end_utc TEXT NOT NULL,status TEXT NOT NULL)");
        sql("INSERT INTO equipment VALUES(88,'legacy',0)");
        sql("INSERT INTO reservations VALUES(90,88,'legacy','2026-10-01T10:00:00Z','2026-10-01T11:00:00Z','Approved')");
        Database db=db();db.initialize();db.approveReservation(90);
        assertEquals("legacy",db.findEquipment(88).orElseThrow().name());
        assertEquals(1,db.listReservations().size());assertTrue(db.pendingOutbox(100).isEmpty());
        create(db,ReservationStatus.Approved);assertEquals(1,db.pendingOutbox(100).size());
    }
}
