package com.example.bot2.repository;

import com.example.bot2.entity.Vacation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface VacationRepository extends JpaRepository<Vacation, Long> {

    // Активные отпуска которые закончились (дата <= сегодня)
    List<Vacation> findByStatusAndEndDateLessThanEqual(
            Vacation.VacationStatus status, LocalDate date);
}
