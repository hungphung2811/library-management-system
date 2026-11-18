package lms.identity.tenantdata.application;

/** Tuần tự hóa manifest thành JSON (UTF-8, tất định theo thứ tự trường) để ký và đưa vào gói. */
public interface ManifestCodec {

    byte[] toJson(ManifestModel.Manifest manifest);
}
