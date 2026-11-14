package lms.database.api;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.MongoTransactionManager;

/** Kết nối MongoDB dùng Spring Data MongoDB, không đi qua cơ chế repository của JPA. */
public interface MongoConnection extends DatabaseConnection {

    MongoTemplate mongoTemplate();

    /** Transaction của Mongo cần replica set; chỉ dùng khi server hỗ trợ. */
    MongoTransactionManager transactionManager();
}
