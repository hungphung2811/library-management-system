package lms.customer.reader.client;

import lms.customer.reader.dto.ReaderDtos.ReaderProfileView;

/** Cổng sang identity-service: thẻ độc giả của chính người gọi (chuyển tiếp token độc giả, endpoint {@code @AuthenticatedOnly}). */
public interface IdentityReaderClient {

    ReaderProfileView myReaderProfile(String userToken);
}
