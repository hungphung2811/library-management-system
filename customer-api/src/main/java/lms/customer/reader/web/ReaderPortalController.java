package lms.customer.reader.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.customer.reader.client.CatalogTitleClient;
import lms.customer.reader.client.CirculationReaderClient;
import lms.customer.reader.client.CirculationReaderClient.ReservationRow;
import lms.customer.reader.client.IdentityReaderClient;
import lms.customer.reader.dto.ReaderDtos.BorrowingView;
import lms.customer.reader.dto.ReaderDtos.FinesView;
import lms.customer.reader.dto.ReaderDtos.ReaderProfileView;
import lms.customer.reader.dto.ReaderDtos.ReservationView;
import lms.customer.storefront.Feature;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import lms.customer.web.PublicCache;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cổng độc giả (ADR-0023): phiếu mượn, phạt, đặt trước, thẻ độc giả của chính người đăng nhập. Mỗi endpoint:
 * (1) phân giải {code} và yêu cầu module LENDING ({@link Feature#LIBRARY}; không đạt thì 404 SHOP_NOT_AVAILABLE, không gọi
 * service nào), (2) {@link ShopSessionGuard} (token dịch vụ hoặc sai aud thì 401, tenant lệch thì 403
 * SHOP_SESSION_MISMATCH), (3) chuyển tiếp TOKEN ĐỘC GIẢ tới circulation/identity, nơi tự lấy userId từ {@code sub} và kiểm
 * sở hữu. Không có tham số userId/readerId ở bất kỳ route nào. Mọi phản hồi {@code no-store}; không log thân/dữ liệu.
 */
@RestController
@RequestMapping("/public/t/{code}/me")
public class ReaderPortalController {

    private final StorefrontAccessCache storefronts;
    private final ShopSessionGuard guard;
    private final CirculationReaderClient circulation;
    private final IdentityReaderClient identity;
    private final CatalogTitleClient catalog;

    public ReaderPortalController(StorefrontAccessCache storefronts, ShopSessionGuard guard,
                                  CirculationReaderClient circulation, IdentityReaderClient identity,
                                  CatalogTitleClient catalog) {
        this.storefronts = storefronts;
        this.guard = guard;
        this.circulation = circulation;
        this.identity = identity;
        this.catalog = catalog;
    }

    @GetMapping("/borrowings")
    public ResponseEntity<List<BorrowingView>> borrowings(@PathVariable String code, Authentication authentication) {
        Session session = session(code, authentication);
        return PublicCache.noStore(circulation.myBorrowings(session.userToken()));
    }

    @GetMapping("/borrowings/{borrowingId}")
    public ResponseEntity<BorrowingView> borrowing(@PathVariable String code, @PathVariable String borrowingId,
                                                   Authentication authentication) {
        Session session = session(code, authentication);
        UUID id = uuid(borrowingId, "BORROWING_NOT_FOUND", "Không tìm thấy phiếu mượn");
        return PublicCache.noStore(circulation.myBorrowing(session.userToken(), id));
    }

    @GetMapping("/fines")
    public ResponseEntity<FinesView> fines(@PathVariable String code, Authentication authentication) {
        Session session = session(code, authentication);
        return PublicCache.noStore(circulation.myFines(session.userToken()));
    }

    @GetMapping("/reservations")
    public ResponseEntity<List<ReservationView>> reservations(@PathVariable String code,
                                                              Authentication authentication) {
        Session session = session(code, authentication);
        List<ReservationRow> rows = circulation.myReservations(session.userToken());
        return PublicCache.noStore(withTitles(session.shop(), rows));
    }

    /** Hủy đặt trước bằng POST (không DELETE): CORS công khai chỉ cho GET, POST. Của người khác thì 404 RESERVATION_NOT_FOUND. */
    @PostMapping("/reservations/{reservationId}/cancellation")
    public ResponseEntity<ReservationView> cancelReservation(@PathVariable String code,
                                                             @PathVariable String reservationId,
                                                             Authentication authentication) {
        Session session = session(code, authentication);
        UUID id = uuid(reservationId, "RESERVATION_NOT_FOUND", "Không tìm thấy đặt trước");
        ReservationRow cancelled = circulation.cancelReservation(session.userToken(), id);
        return PublicCache.noStore(withTitles(session.shop(), List.of(cancelled)).get(0));
    }

    @GetMapping("/reader-profile")
    public ResponseEntity<ReaderProfileView> readerProfile(@PathVariable String code, Authentication authentication) {
        Session session = session(code, authentication);
        return PublicCache.noStore(identity.myReaderProfile(session.userToken()));
    }

    /** Một lời gọi lô tới catalog cho cả danh sách (token dịch vụ), ghép theo Map titleId -> tên; thiếu tên thì null. */
    private List<ReservationView> withTitles(StorefrontAccess shop, List<ReservationRow> rows) {
        Map<UUID, String> names = catalog.titleNames(shop.accessToken(),
                rows.stream().map(ReservationRow::titleId).toList());
        return rows.stream().map(r -> new ReservationView(r.id(), r.titleId(), names.get(r.titleId()), r.status(),
                r.queuePosition(), r.reservedAt(), r.holdUntil())).toList();
    }

    private Session session(String code, Authentication authentication) {
        StorefrontAccess shop = storefronts.resolve(code).require(Feature.LIBRARY);
        return new Session(shop, guard.requireSession(shop, authentication).getTokenValue());
    }

    /** id không phải UUID thì không thể tồn tại: 404 với mã tương ứng (cùng thân), không gọi service phía sau. */
    private static UUID uuid(String raw, String code, String message) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new PublicApiException(HttpStatus.NOT_FOUND, code, message);
        }
    }

    private record Session(StorefrontAccess shop, String userToken) {
        @Override
        public String toString() {
            return "Session[***]";
        }
    }
}
