package lms.api.sales.client.http;

import java.util.List;
import java.util.UUID;
import lms.api.sales.client.SalesShiftClient;
import lms.api.sales.dto.SalesDtos.SalesPage;
import lms.api.sales.dto.SalesShiftDtos.ApproveVarianceRequest;
import lms.api.sales.dto.SalesShiftDtos.CloseShiftRequest;
import lms.api.sales.dto.SalesShiftDtos.MyShiftsQuery;
import lms.api.sales.dto.SalesShiftDtos.OpenShiftRequest;
import lms.api.sales.dto.SalesShiftDtos.ReopenShiftRequest;
import lms.api.sales.dto.SalesShiftDtos.ShiftDetailView;
import lms.api.sales.dto.SalesShiftDtos.ShiftSearchQuery;
import lms.api.sales.dto.SalesShiftDtos.ShiftSettingsView;
import lms.api.sales.dto.SalesShiftDtos.ShiftView;
import lms.api.sales.dto.SalesShiftDtos.UpdateShiftSettingsRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/** Gọi ca thu ngân của sales-service. Token, tenant và correlation id do RestClient chuyển tiếp; lỗi 4xx chuyển nguyên. */
public class HttpSalesShiftClient implements SalesShiftClient {

    private static final String BASE = "/internal/sales/shifts";
    private static final String SETTINGS = "/internal/sales/shift-settings";
    private static final ParameterizedTypeReference<SalesPage<ShiftView>> SHIFT_PAGE =
            new ParameterizedTypeReference<>() { };

    private final RestClient client;

    public HttpSalesShiftClient(RestClient client) {
        this.client = client;
    }

    private static void param(UriBuilder b, String name, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return;
        }
        b.queryParam(name, value);
    }

    @Override
    public ShiftView openShift(OpenShiftRequest request) {
        return client.post().uri(BASE).body(request).retrieve().body(ShiftView.class);
    }

    @Override
    public ShiftDetailView currentShift() {
        return client.get().uri(BASE + "/me/current").retrieve().body(ShiftDetailView.class);
    }

    @Override
    public SalesPage<ShiftView> myShifts(MyShiftsQuery q) {
        SalesPage<ShiftView> page = client.get().uri(b -> {
            b.path(BASE + "/me");
            param(b, "status", q.status());
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build();
        }).retrieve().body(SHIFT_PAGE);
        return page == null ? new SalesPage<>(List.of(), 0, 0, 0) : page;
    }

    @Override
    public ShiftDetailView myShift(UUID id) {
        return client.get().uri(BASE + "/me/{id}", id).retrieve().body(ShiftDetailView.class);
    }

    @Override
    public ShiftView closeCurrentShift(CloseShiftRequest request) {
        return client.post().uri(BASE + "/me/current/closing").body(request).retrieve().body(ShiftView.class);
    }

    @Override
    public SalesPage<ShiftView> searchShifts(ShiftSearchQuery q) {
        SalesPage<ShiftView> page = client.get().uri(b -> {
            b.path(BASE);
            param(b, "cashierUserId", q.cashierUserId());
            param(b, "status", q.status());
            param(b, "varianceStatus", q.varianceStatus());
            param(b, "from", q.from());
            param(b, "to", q.to());
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build();
        }).retrieve().body(SHIFT_PAGE);
        return page == null ? new SalesPage<>(List.of(), 0, 0, 0) : page;
    }

    @Override
    public ShiftDetailView getShift(UUID id) {
        return client.get().uri(BASE + "/{id}", id).retrieve().body(ShiftDetailView.class);
    }

    @Override
    public ShiftView closeShiftOnBehalf(UUID id, CloseShiftRequest request) {
        return client.post().uri(BASE + "/{id}/closing", id).body(request).retrieve().body(ShiftView.class);
    }

    @Override
    public ShiftView reopenShift(UUID id, ReopenShiftRequest request) {
        return client.post().uri(BASE + "/{id}/reopening", id).body(request).retrieve().body(ShiftView.class);
    }

    @Override
    public ShiftView approveShiftVariance(UUID id, ApproveVarianceRequest request) {
        var spec = client.post().uri(BASE + "/{id}/variance-approval", id);
        if (request != null) {
            spec = spec.body(request);
        }
        return spec.retrieve().body(ShiftView.class);
    }

    @Override
    public ShiftSettingsView getShiftSettings() {
        return client.get().uri(SETTINGS).retrieve().body(ShiftSettingsView.class);
    }

    @Override
    public ShiftSettingsView updateShiftSettings(UpdateShiftSettingsRequest request) {
        return client.put().uri(SETTINGS).body(request).retrieve().body(ShiftSettingsView.class);
    }
}
