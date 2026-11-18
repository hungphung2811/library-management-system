package lms.demo.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Dữ liệu 64 đầu sách demo (ADR-0018), nhóm theo 8 chủ đề, mỗi chủ đề 8 sách liên tiếp (chủ đề = index / 8, cùng thứ tự
 * với {@code DemoPlanGenerator.TOPICS}). Chỉ là dữ liệu minh họa: tên sách, tác giả, nhà xuất bản có thật nhưng số trang,
 * lần tái bản, dịch giả... chỉ mang tính gần đúng, không dùng làm tư liệu tham khảo.
 *
 * <p>Mỗi dòng: tên | phụ đề | tác giả | nhà xuất bản | năm | lần tái bản | ngôn ngữ | số trang | định dạng | dịch giả |
 * bộ sách | tập thứ | nhãn (cách nhau bằng dấu chấm phẩy). Trường rỗng nghĩa là không có.
 */
final class DemoTitleData {

    /** Một dòng dữ liệu đã tách. */
    record Row(String name, String subtitle, String author, String publisher, int year, String edition,
               String language, int pageCount, String format, String translator, String series, Integer seriesNumber,
               List<String> tags) {
    }

    private static final String[] ROWS = {
            // ---- Lập trình
            "Clean Code|A Handbook of Agile Software Craftsmanship|Robert C. Martin|Pearson|2008|First Edition|en|464|PAPERBACK|||| lập trình;clean code;phần mềm",
            "The Pragmatic Programmer|Your Journey to Mastery|Andrew Hunt, David Thomas|Addison-Wesley|2019|20th Anniversary Edition|en|352|HARDCOVER|||| lập trình;thực hành tốt;phần mềm",
            "Head First Design Patterns|A Brain-Friendly Guide|Eric Freeman, Elisabeth Robson|O'Reilly Media|2020|Second Edition|en|672|PAPERBACK||Head First|1|design pattern;java;lập trình",
            "Head First Java||Kathy Sierra, Bert Bates|O'Reilly Media|2022|Third Edition|en|690|PAPERBACK||Head First|2|java;lập trình;nhập môn",
            "Lập trình Java cơ bản|Từ nhập môn đến ứng dụng|Phạm Hữu Khang|NXB Giáo dục Việt Nam|2015|Tái bản lần thứ 2|vi|380|PAPERBACK|||| java;giáo trình;lập trình",
            "Cấu trúc dữ liệu và giải thuật||Đỗ Xuân Lôi|NXB Đại học Quốc gia Hà Nội|2012|Tái bản lần thứ 5|vi|520|PAPERBACK|||| giáo trình;giải thuật;đại học",
            "Python cho người mới bắt đầu|Hướng dẫn thực hành qua dự án nhỏ|Lê Hoàng Long|NXB Thanh niên|2021||vi|288|EBOOK|||| python;nhập môn;lập trình",
            "Designing Data-Intensive Applications|The Big Ideas Behind Reliable, Scalable, and Maintainable Systems|Martin Kleppmann|O'Reilly Media|2017|First Edition|en|616|PAPERBACK|||| hệ thống phân tán;cơ sở dữ liệu;kiến trúc",
            // ---- Lịch sử Việt Nam
            "Đại Việt sử ký toàn thư|Bản dịch trọn bộ|Ngô Sĩ Liên|NXB Khoa học xã hội|2009|Tái bản lần thứ 3|vi|700|HARDCOVER|Cao Huy Giu|||sử học;cổ sử;chính sử",
            "Việt Nam sử lược||Trần Trọng Kim|NXB Văn học|2012|Tái bản lần thứ 4|vi|596|PAPERBACK|||| sử học;lịch sử;kinh điển",
            "Lịch sử Việt Nam bằng tranh – Triều Lý|Lịch sử Việt Nam bằng tranh|Trần Bạch Đằng|NXB Kim Đồng|2010|Tái bản lần thứ 2|vi|144|PAPERBACK||Lịch sử Việt Nam bằng tranh|1|truyện tranh;lịch sử;thiếu niên",
            "Lịch sử Việt Nam bằng tranh – Triều Trần|Lịch sử Việt Nam bằng tranh|Trần Bạch Đằng|NXB Kim Đồng|2010|Tái bản lần thứ 2|vi|144|PAPERBACK||Lịch sử Việt Nam bằng tranh|2|truyện tranh;lịch sử;thiếu niên",
            "Nhật ký trong tù||Hồ Chí Minh|NXB Chính trị quốc gia Sự thật|2018|Tái bản lần thứ 6|vi|200|HARDCOVER|||| thơ;cách mạng;danh nhân",
            "Việt Nam văn minh sử cương||Lê Văn Siêu|NXB Văn học|2014|Tái bản lần thứ 2|vi|480|PAPERBACK|||| văn hóa;văn minh;khảo cứu",
            "Lược sử Việt Nam|Từ thời tiền sử đến thế kỷ XX|Nguyễn Quang Ngọc|NXB Giáo dục Việt Nam|2017|Tái bản lần thứ 1|vi|320|PAPERBACK|||| lịch sử;giáo trình;tổng quan",
            "Hào khí Thăng Long|Những trang sử đất kinh kỳ|Nguyễn Khắc Thuần|NXB Thanh niên|2020||vi|256|PAPERBACK|||| thăng long;hà nội;lịch sử",
            // ---- Văn học
            "Truyện Kiều||Nguyễn Du|NXB Văn học|2015|Tái bản lần thứ 6|vi|280|HARDCOVER|||| thơ;kinh điển;văn học trung đại",
            "Số đỏ||Vũ Trọng Phụng|NXB Văn học|2018|Tái bản lần thứ 5|vi|304|PAPERBACK|||| tiểu thuyết;hiện thực;trào phúng",
            "Nhà giả kim||Paulo Coelho|NXB Văn học|2013|Tái bản lần thứ 12|vi|227|PAPERBACK|Lê Chu Cầu|||tiểu thuyết;triết lý;bán chạy",
            "Tôi thấy hoa vàng trên cỏ xanh||Nguyễn Nhật Ánh|NXB Trẻ|2015|Tái bản lần thứ 3|vi|378|PAPERBACK|||| tiểu thuyết;tuổi thơ;nông thôn",
            "Mắt biếc||Nguyễn Nhật Ánh|NXB Trẻ|2019|Tái bản lần thứ 9|vi|300|HARDCOVER|||| tiểu thuyết;tình yêu;tuổi trẻ",
            "Harry Potter và Hòn đá Phù thủy||J.K. Rowling|NXB Trẻ|2017|Tái bản lần thứ 8|vi|368|PAPERBACK|Lý Lan|Harry Potter|1|giả tưởng;phép thuật;thiếu niên",
            "Harry Potter và Phòng chứa Bí mật||J.K. Rowling|NXB Trẻ|2017|Tái bản lần thứ 8|vi|400|PAPERBACK|Lý Lan|Harry Potter|2|giả tưởng;phép thuật;thiếu niên",
            "Những người khốn khổ||Victor Hugo|NXB Văn học|2009|Tái bản lần thứ 3|vi|700|HARDCOVER|Huỳnh Lý|||tiểu thuyết;kinh điển;văn học Pháp",
            // ---- Kinh tế
            "Đắc nhân tâm||Dale Carnegie|First News - Trí Việt|2016|Tái bản lần thứ 10|vi|320|PAPERBACK|Nguyễn Hiến Lê|||kỹ năng sống;giao tiếp;bán chạy",
            "Cha giàu cha nghèo|Những bài học về tiền bạc|Robert T. Kiyosaki|NXB Trẻ|2017|Tái bản lần thứ 7|vi|336|PAPERBACK|Nguyễn Văn Phước|||tài chính cá nhân;đầu tư;bán chạy",
            "Nghĩ giàu và làm giàu||Napoleon Hill|NXB Lao động|2019|Tái bản lần thứ 4|vi|400|PAPERBACK|Lê Minh Châu|||kinh doanh;tư duy;thành công",
            "Từ tốt đến vĩ đại|Tại sao một số công ty bứt phá còn số khác thì không|Jim Collins|Alpha Books|2020|Tái bản lần thứ 3|vi|416|HARDCOVER|Nguyễn Hà|||quản trị;doanh nghiệp;lãnh đạo",
            "Khởi nghiệp tinh gọn|Cách các doanh nhân hiện đại vận dụng sự đổi mới liên tục|Eric Ries|Alpha Books|2018|Tái bản lần thứ 3|vi|400|PAPERBACK|Nguyễn Thị Thu Huyền|||khởi nghiệp;sản phẩm;quản trị",
            "Kinh tế học vĩ mô||N. Gregory Mankiw|NXB Thống kê|2013|Tái bản lần thứ 2|vi|700|PAPERBACK|Nguyễn Ngọc Toàn|||kinh tế học;giáo trình;vĩ mô",
            "Marketing căn bản||Philip Kotler|NXB Lao động - Xã hội|2012|Tái bản lần thứ 5|vi|632|PAPERBACK|Vũ Trọng Hùng|||marketing;giáo trình;kinh doanh",
            "Quản trị chiến lược|Khái niệm và tình huống|Fred R. David|NXB Kinh tế TP.HCM|2010|Tái bản lần thứ 2|vi|620|PAPERBACK|Trần Văn Thành|||quản trị;chiến lược;giáo trình",
            // ---- Khoa học
            "Lược sử thời gian|Từ Big Bang đến lỗ đen|Stephen Hawking|NXB Trẻ|2019|Tái bản lần thứ 6|vi|280|PAPERBACK|Cao Chi, Phạm Văn Thiều|||vũ trụ;vật lý;phổ biến khoa học",
            "Sapiens: Lược sử loài người||Yuval Noah Harari|Nhã Nam|2017|Tái bản lần thứ 5|vi|548|PAPERBACK|Nguyễn Thủy Chung|||nhân loại học;lịch sử;bán chạy",
            "Homo Deus: Lược sử tương lai||Yuval Noah Harari|Nhã Nam|2018|Tái bản lần thứ 2|vi|520|PAPERBACK|Dương Thanh Vũ|||tương lai;công nghệ;nhân loại học",
            "Vũ trụ trong vỏ hạt dẻ||Stephen Hawking|NXB Trẻ|2018|Tái bản lần thứ 3|vi|270|HARDCOVER|Phạm Văn Thiều|||vũ trụ;vật lý;phổ biến khoa học",
            "Nguồn gốc các loài||Charles Darwin|NXB Thế giới|2013|Tái bản lần thứ 1|vi|700|HARDCOVER|Đặng Ngọc Lan|||tiến hóa;sinh học;kinh điển",
            "Súng, vi trùng và thép|Số phận của các xã hội loài người|Jared Diamond|NXB Thế giới|2015|Tái bản lần thứ 2|vi|600|PAPERBACK|Trần Tiễn Cao Đăng|||lịch sử;địa lý;nhân loại học",
            "Khám phá thế giới vi sinh vật||Nguyễn Lân Dũng|NXB Giáo dục Việt Nam|2011|Tái bản lần thứ 2|vi|240|PAPERBACK|||| sinh học;vi sinh;phổ biến khoa học",
            "Cosmos||Carl Sagan|Random House|2013|Reprint Edition|en|432|PAPERBACK|||| vũ trụ;thiên văn;phổ biến khoa học",
            // ---- Thiếu nhi
            "Dế Mèn phiêu lưu ký||Tô Hoài|NXB Kim Đồng|2019|Tái bản lần thứ 20|vi|144|HARDCOVER|||| truyện thiếu nhi;phiêu lưu;kinh điển",
            "Doraemon – Tập 1||Fujiko F. Fujio|NXB Kim Đồng|2020|Tái bản lần thứ 15|vi|192|PAPERBACK|Nguyễn Hoàng Anh|Doraemon|1|truyện tranh;manga;thiếu nhi",
            "Doraemon – Tập 2||Fujiko F. Fujio|NXB Kim Đồng|2020|Tái bản lần thứ 15|vi|192|PAPERBACK|Nguyễn Hoàng Anh|Doraemon|2|truyện tranh;manga;thiếu nhi",
            "Thám tử lừng danh Conan – Tập 1||Gosho Aoyama|NXB Kim Đồng|2021|Tái bản lần thứ 10|vi|192|PAPERBACK|Thanh Huyền|Thám tử lừng danh Conan|1|trinh thám;manga;thiếu niên",
            "Kính vạn hoa – Tập 1||Nguyễn Nhật Ánh|NXB Trẻ|2011|Tái bản lần thứ 7|vi|256|PAPERBACK||Kính vạn hoa|1|truyện thiếu nhi;học đường;tuổi thơ",
            "Kính vạn hoa – Tập 2||Nguyễn Nhật Ánh|NXB Trẻ|2011|Tái bản lần thứ 7|vi|256|PAPERBACK||Kính vạn hoa|2|truyện thiếu nhi;học đường;tuổi thơ",
            "Hoàng tử bé||Antoine de Saint-Exupéry|NXB Văn học|2016|Tái bản lần thứ 5|vi|128|HARDCOVER|Nguyễn Thành Long|||truyện thiếu nhi;triết lý;kinh điển",
            "Charlotte's Web||E. B. White|HarperCollins|2012|Reissue Edition|en|192|PAPERBACK|||| truyện thiếu nhi;động vật;kinh điển",
            // ---- Tâm lý học
            "Tư duy nhanh và chậm||Daniel Kahneman|Alpha Books|2015|Tái bản lần thứ 4|vi|612|PAPERBACK|Nguyễn Hoàng Tâm|||tâm lý học;ra quyết định;kinh tế hành vi",
            "Hiểu về trái tim||Minh Niệm|NXB Tổng hợp TP.HCM|2017|Tái bản lần thứ 9|vi|520|PAPERBACK|||| tâm lý học;chữa lành;bán chạy",
            "Nghệ thuật tinh tế của việc đếch quan tâm|Một cách tiếp cận ngược đời để sống tốt|Mark Manson|NXB Thế giới|2019|Tái bản lần thứ 2|vi|224|PAPERBACK|Nguyễn Trung Nguyên|||kỹ năng sống;tâm lý học;bán chạy",
            "Đi tìm lẽ sống||Viktor E. Frankl|NXB Tổng hợp TP.HCM|2018|Tái bản lần thứ 3|vi|184|PAPERBACK|Nguyễn Duy Nhiên|||tâm lý học;triết lý;hồi ký",
            "Tâm lý học đám đông||Gustave Le Bon|NXB Tri thức|2018|Tái bản lần thứ 2|vi|240|PAPERBACK|Nguyễn Văn Khoa|||tâm lý học;xã hội học;kinh điển",
            "Mindset: Tâm lý học thành công||Carol S. Dweck|First News - Trí Việt|2020|Tái bản lần thứ 5|vi|320|PAPERBACK|Nguyễn Phan|||tâm lý học;phát triển bản thân;giáo dục",
            "Tâm lý học nhập môn||Nguyễn Khắc Viện|NXB Thế giới|2014|Tái bản lần thứ 3|vi|400|PAPERBACK|||| tâm lý học;nhập môn;giáo trình",
            "Atomic Habits|An Easy & Proven Way to Build Good Habits & Break Bad Ones|James Clear|Avery|2018|First Edition|en|320|HARDCOVER|||| thói quen;phát triển bản thân;bán chạy",
            // ---- Nghệ thuật
            "Câu chuyện nghệ thuật||E. H. Gombrich|NXB Mỹ thuật|2016|Tái bản lần thứ 3|vi|688|HARDCOVER|Lưu Văn Hy|||lịch sử nghệ thuật;hội họa;kinh điển",
            "Mỹ thuật thời Trần||Chu Quang Trứ|NXB Mỹ thuật|2005||vi|280|HARDCOVER|||| mỹ thuật;lịch sử;khảo cứu",
            "Lịch sử mỹ thuật Việt Nam||Nguyễn Quân|NXB Mỹ thuật|2013|Tái bản lần thứ 2|vi|360|PAPERBACK|||| mỹ thuật;lịch sử;giáo trình",
            "Ways of Seeing||John Berger|Penguin Books|2008|Reissue Edition|en|176|PAPERBACK|||| hội họa;phê bình;nghệ thuật thị giác",
            "Nhiếp ảnh căn bản|Ánh sáng, bố cục và kỹ thuật|Trần Văn Thắng|NXB Văn hóa - Thông tin|2011|Tái bản lần thứ 2|vi|220|PAPERBACK|||| nhiếp ảnh;kỹ thuật;nhập môn",
            "Nhạc lý cơ bản||Nguyễn Thị Nhung|NXB Âm nhạc|2009|Tái bản lần thứ 4|vi|160|PAPERBACK|||| âm nhạc;nhạc lý;giáo trình",
            "Drawing on the Right Side of the Brain||Betty Edwards|TarcherPerigee|2012|Fourth Edition|en|304|PAPERBACK|||| hội họa;kỹ thuật vẽ;sáng tạo",
            "Kiến trúc cổ Việt Nam||Nguyễn Bá Lăng|NXB Xây dựng|2008|Tái bản lần thứ 1|vi|290|HARDCOVER|||| kiến trúc;di sản;khảo cứu",
    };

    private DemoTitleData() {
    }

    /** Tách và trả đúng 64 dòng; sai định dạng là lỗi lập trình nên ném IllegalStateException ngay khi nạp. */
    static List<Row> rows() {
        List<Row> out = new ArrayList<>(ROWS.length);
        for (int i = 0; i < ROWS.length; i++) {
            String[] f = ROWS[i].split("\\|", -1);
            if (f.length != 13) {
                throw new IllegalStateException("Dòng sách demo " + i + " có " + f.length + " trường, cần 13");
            }
            List<String> tags = f[12].isBlank() ? List.of()
                    : java.util.Arrays.stream(f[12].split(";")).map(String::strip).toList();
            out.add(new Row(f[0], blank(f[1]), f[2], f[3], Integer.parseInt(f[4]), blank(f[5]), f[6],
                    Integer.parseInt(f[7]), f[8], blank(f[9]), blank(f[10]),
                    f[11].isBlank() ? null : Integer.valueOf(f[11]), tags));
        }
        return out;
    }

    private static String blank(String s) {
        return s.isBlank() ? null : s;
    }
}
