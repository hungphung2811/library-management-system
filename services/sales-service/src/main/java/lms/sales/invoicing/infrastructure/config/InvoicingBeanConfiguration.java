package lms.sales.invoicing.infrastructure.config;

import java.time.Clock;
import lms.sales.invoicing.application.AdjustInvoiceForReturnUseCase;
import lms.sales.invoicing.application.AutoIssueInvoiceUseCase;
import lms.sales.invoicing.application.InternalInvoiceIssuer;
import lms.sales.invoicing.application.InvoiceAdjustmentPort;
import lms.sales.invoicing.application.InvoiceAutoIssuePort;
import lms.sales.invoicing.application.InvoiceIssuerPort;
import lms.sales.invoicing.application.InvoiceOrderSourcePort;
import lms.sales.invoicing.application.InvoiceQueryUseCases;
import lms.sales.invoicing.application.InvoiceRendererPort;
import lms.sales.invoicing.application.InvoiceRepository;
import lms.sales.invoicing.application.InvoiceRequestRepository;
import lms.sales.invoicing.application.InvoiceRequestUseCases;
import lms.sales.invoicing.application.InvoiceSettingsPort;
import lms.sales.invoicing.application.InvoiceSettingsUseCases;
import lms.sales.invoicing.application.InvoiceUseCases;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.SalesAuditPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root của context invoicing (ADR-0030 gói A). Bên phát hành mặc định là {@link InternalInvoiceIssuer}
 * (số nội bộ, không gửi nhà cung cấp hóa đơn điện tử); cắm nhà cung cấp thật bằng bean {@link InvoiceIssuerPort} khác.
 */
@Configuration
public class InvoicingBeanConfiguration {

    @Bean
    InvoiceIssuerPort invoiceIssuerPort(DocumentNumberPort numbers) {
        return new InternalInvoiceIssuer(numbers);
    }

    @Bean
    InvoiceUseCases invoiceUseCases(InvoiceOrderSourcePort orders, InvoiceRepository invoices,
                                    InvoiceRequestRepository requests, InvoiceSettingsPort settings,
                                    InvoiceIssuerPort issuer, SalesAuditPort audit, Clock clock) {
        return new InvoiceUseCases(orders, invoices, requests, settings, issuer, audit, clock);
    }

    /** Móc từ ordering (CommitReturnUseCase): điều chỉnh giảm khi trả hàng. */
    @Bean
    InvoiceAdjustmentPort invoiceAdjustmentPort(InvoiceRepository invoices, InvoiceIssuerPort issuer,
                                                SalesAuditPort audit) {
        return new AdjustInvoiceForReturnUseCase(invoices, issuer, audit);
    }

    /** Móc từ ordering: tự phát hành khi đơn thành COMPLETED + PAID (no-op khi tenant tắt). */
    @Bean
    InvoiceAutoIssuePort invoiceAutoIssuePort(InvoiceSettingsPort settings, InvoiceRequestRepository requests,
                                              InvoiceRepository invoices, InvoiceOrderSourcePort orders,
                                              InvoiceUseCases useCases) {
        return new AutoIssueInvoiceUseCase(settings, requests, invoices, orders, useCases);
    }

    @Bean
    InvoiceQueryUseCases invoiceQueryUseCases(InvoiceRepository invoices, InvoiceRendererPort renderer) {
        return new InvoiceQueryUseCases(invoices, renderer);
    }

    @Bean
    InvoiceRequestUseCases invoiceRequestUseCases(InvoiceOrderSourcePort orders, InvoiceRequestRepository requests,
                                                  InvoiceAutoIssuePort autoIssue, SalesAuditPort audit, Clock clock) {
        return new InvoiceRequestUseCases(orders, requests, autoIssue, audit, clock);
    }

    @Bean
    InvoiceSettingsUseCases invoiceSettingsUseCases(InvoiceSettingsPort port, SalesAuditPort audit, Clock clock) {
        return new InvoiceSettingsUseCases(port, audit, clock);
    }
}
