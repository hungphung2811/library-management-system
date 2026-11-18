package lms.sales.shift.infrastructure.config;

import java.time.Clock;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shift.application.CashierShiftAdminUseCases;
import lms.sales.shift.application.CashierShiftRepository;
import lms.sales.shift.application.CashierShiftUseCases;
import lms.sales.shift.application.ShiftAttributionPort;
import lms.sales.shift.application.ShiftAttributionService;
import lms.sales.shift.application.ShiftLedgerPort;
import lms.sales.shift.application.ShiftOperations;
import lms.sales.shift.application.ShiftSettingsPort;
import lms.sales.shift.application.ShiftSettingsUseCases;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context shift (ADR-0030). */
@Configuration
public class ShiftBeanConfiguration {

    @Bean
    ShiftOperations shiftOperations(CashierShiftRepository shifts, ShiftLedgerPort ledger, ShiftSettingsPort settings,
                                    DocumentNumberPort numbers, SalesAuditPort audit, Clock clock) {
        return new ShiftOperations(shifts, ledger, settings, numbers, audit, clock);
    }

    @Bean
    CashierShiftUseCases cashierShiftUseCases(CashierShiftRepository shifts, ShiftOperations operations) {
        return new CashierShiftUseCases(shifts, operations);
    }

    @Bean
    CashierShiftAdminUseCases cashierShiftAdminUseCases(CashierShiftRepository shifts, ShiftOperations operations) {
        return new CashierShiftAdminUseCases(shifts, operations);
    }

    @Bean
    ShiftSettingsUseCases shiftSettingsUseCases(ShiftSettingsPort settings, SalesAuditPort audit, Clock clock) {
        return new ShiftSettingsUseCases(settings, audit, clock);
    }

    /** Cổng cho ordering gắn tiền vào ca (đọc ca mở và cờ tenant). */
    @Bean
    ShiftAttributionPort shiftAttributionPort(CashierShiftRepository shifts, ShiftSettingsPort settings) {
        return new ShiftAttributionService(shifts, settings);
    }
}
