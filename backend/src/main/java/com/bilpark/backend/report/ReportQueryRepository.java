package com.bilpark.backend.report;

import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Read-only report queries; always concrete time ranges [from, to). */
public interface ReportQueryRepository extends Repository<ParkingRecord, Long> {

    String ROW = "select new com.bilpark.backend.report.RecordRow(r.id, r.licensePlate, r.street, z.id, z.zoneName, "
            + "r.entryTime, r.exitTime, r.vehicleType, r.fee, r.status, r.debtPaid, r.paymentMethod, "
            + "r.collectedByUsername, r.deviceId, r.tariffId, r.paidMinutes, r.cancelledFromStatus, "
            + "r.cancelledAt, r.cancelReason) from ParkingRecord r left join r.zone z ";

    /** Closed or cancelled records whose exit or cancellation falls into the window (personnel scoring). */
    @Query(ROW + "where (r.exitTime >= :from and r.exitTime < :to and r.status in "
            + "(com.bilpark.backend.model.ParkingStatus.PAID, com.bilpark.backend.model.ParkingStatus.RUNAWAY, "
            + "com.bilpark.backend.model.ParkingStatus.CANCELLED)) "
            + "or (r.status = com.bilpark.backend.model.ParkingStatus.CANCELLED "
            + "and r.cancelledAt >= :from and r.cancelledAt < :to)")
    List<RecordRow> personnelRecords(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(ROW + "where r.status = :status and r.exitTime >= :from and r.exitTime < :to order by r.exitTime, r.id")
    List<RecordRow> byStatusAndExit(@Param("status") ParkingStatus status,
                                    @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(ROW + "where r.status = com.bilpark.backend.model.ParkingStatus.CANCELLED "
            + "and r.cancelledAt >= :from and r.cancelledAt < :to")
    List<RecordRow> cancelledBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select count(r) from ParkingRecord r where r.entryTime >= :from and r.entryTime < :to")
    long countByEntry(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(ROW + "where r.entryTime >= :from and r.entryTime < :to order by r.entryTime, r.id")
    List<RecordRow> byEntry(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select s from Shift s left join fetch s.zone where s.startTime < :to "
            + "and (s.endTime is null or s.endTime > :from) order by s.startTime, s.id")
    List<Shift> shiftsOverlapping(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select u from User u left join fetch u.assignedZone where u.role = :role")
    List<User> usersByRole(@Param("role") Role role);

    @Query("select s.monthlyFee from Subscription s where s.startDate >= :from and s.startDate <= :to")
    List<Double> subscriptionFeesStartedBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
