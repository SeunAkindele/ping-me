package com.pingme.reminder;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stage 3 — Persistence port for {@link Reminder}.
 *
 * <p>Stage 5 claim queries live on {@link ReminderClaimRepository}. A derived finder cannot express
 * {@code FOR UPDATE SKIP LOCKED}, and an unlocked read is how two schedulers claim the same row.
 */
public interface ReminderRepository extends JpaRepository<Reminder, Long> {

	List<Reminder> findByUser_Id(Long userId);

}
