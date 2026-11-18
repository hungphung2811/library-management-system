package lms.circulation.policy.application;

/** Cổng hỏi bậc hỏng đã được khoản phạt nào dùng chưa (khoản phạt thuộc context fine nên fine cài adapter). */
public interface DamageLevelUsagePort {

    boolean isUsed(String code);
}
