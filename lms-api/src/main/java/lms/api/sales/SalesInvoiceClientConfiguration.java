package lms.api.sales;

import lms.api.config.ServiceClients;
import lms.api.sales.client.SalesInvoiceClient;
import lms.api.sales.client.http.HttpSalesInvoiceClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Client hóa đơn VAT nội bộ (ADR-0030 gói A): cùng sales-service, đường dẫn /internal/sales/invoices/**. */
@Configuration
public class SalesInvoiceClientConfiguration {

    @Bean
    SalesInvoiceClient salesInvoiceClient(ServiceClients clients) {
        return new HttpSalesInvoiceClient(clients.create(clients.urls().salesUrl()));
    }
}
