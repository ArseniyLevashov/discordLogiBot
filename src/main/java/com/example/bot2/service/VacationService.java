package com.example.bot2.service;

import com.example.bot2.entity.Vacation;
import com.example.bot2.repository.VacationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class VacationService {

    private final VacationRepository vacationRepo;

    public Vacation createVacation(String userId, String username, String guildId,
                                   String channelId, LocalDate endDate) {
        Vacation v = new Vacation();
        v.setUserId(userId);
        v.setUsername(username);
        v.setGuildId(guildId);
        v.setChannelId(channelId);
        v.setEndDate(endDate);
        v.setStatus(Vacation.VacationStatus.ACTIVE);
        return vacationRepo.save(v);
    }

    public List<Vacation> getEndedVacations() {
        return vacationRepo.findByStatusAndEndDateLessThanEqual(
                Vacation.VacationStatus.ACTIVE, LocalDate.now());
    }

    public void markCompleted(Vacation vacation) {
        vacation.setStatus(Vacation.VacationStatus.COMPLETED);
        vacationRepo.save(vacation);
    }
}
