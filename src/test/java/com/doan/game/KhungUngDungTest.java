package com.doan.game;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Kiểm tra duy nhất còn lại ở giai đoạn khung: ứng dụng có dựng được ngữ cảnh không.
 *
 * Hai test cũ (AuthFlowTest, TelemetryIngestTest) đã bỏ cùng phần mã chúng kiểm — luồng
 * auth cũ và đường nạp EstimateEvent. Giữ lại thì CI đỏ vĩnh viễn vì trỏ vào gói không
 * còn tồn tại.
 *
 * Nó nhỏ nhưng không vô dụng: mọi @Service, @RestController và @Entity đều phải nạp
 * được, nên một bean thiếu phụ thuộc hay một ánh xạ JPA sai là hỏng ngay ở đây. Với một
 * khung 104 lớp thì đó đúng là thứ dễ gãy nhất.
 *
 * Có nghiệp vụ thật thì thay bằng test thật — đừng để mỗi test này.
 */
@SpringBootTest
class KhungUngDungTest {

    @Test
    void nguCanhDungDuoc() {
    }
}
