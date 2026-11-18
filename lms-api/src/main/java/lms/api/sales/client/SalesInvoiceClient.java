package lms.api.sales.client;

import java.util.UUID;
import lms.api.sales.dto.SalesDtos.SalesPage;
import lms.api.sales.dto.SalesInvoiceDtos.CancelInvoiceRequest;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceRequestView;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceSearchQuery;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceSettingsView;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceSummaryView;
import lms.api.sales.dto.SalesInvoiceDtos.InvoiceView;
import lms.api.sales.dto.SalesInvoiceDtos.IssueInvoiceRequest;
import lms.api.sales.dto.SalesInvoiceDtos.PrintedInvoice;
import lms.api.sales.dto.SalesInvoiceDtos.ReplaceInvoiceRequest;
import lms.api.sales.dto.SalesInvoiceDtos.SaveInvoiceRequestRequest;
import lms.api.sales.dto.SalesInvoiceDtos.UpdateInvoiceSettingsRequest;

/**
 * Cổng sang hóa đơn VAT nội bộ của sales-service (/internal/sales/invoices, /orders/{id}/invoice,
 * /orders/{id}/invoice-request, /invoice-settings; ADR-0030 gói A). Client riêng, không nằm trong {@link SalesClient}.
 */
public interface SalesInvoiceClient {

    SalesPage<InvoiceSummaryView> searchInvoices(InvoiceSearchQuery query);

    InvoiceView getInvoice(UUID id);

    PrintedInvoice printInvoice(UUID id);

    InvoiceView issueInvoice(UUID orderId, IssueInvoiceRequest request);

    InvoiceView cancelInvoice(UUID id, CancelInvoiceRequest request);

    InvoiceView replaceInvoice(UUID id, ReplaceInvoiceRequest request);

    InvoiceRequestView getInvoiceRequest(UUID orderId);

    InvoiceRequestView saveInvoiceRequest(UUID orderId, SaveInvoiceRequestRequest request);

    InvoiceSettingsView getInvoiceSettings();

    InvoiceSettingsView updateInvoiceSettings(UpdateInvoiceSettingsRequest request);
}
