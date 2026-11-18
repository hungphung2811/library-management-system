package lms.demo.plan;

import java.util.ArrayList;
import java.util.List;
import lms.demo.plan.DemoPlan.AuthorPlan;
import lms.demo.plan.DemoPlan.PublisherPlan;

/**
 * Dữ liệu minh họa (ADR-0018) cho tác giả và nhà xuất bản của {@link DemoTitleData}: năm sinh/mất, quốc tịch, website,
 * quốc gia, năm thành lập, địa chỉ. Chỉ mang tính gần đúng, không dùng làm tư liệu tham khảo. Trường rỗng là không có.
 *
 * <p>Tác giả: tên | năm sinh | năm mất | quốc tịch | website. Nhà xuất bản: tên | quốc gia | năm thành lập | website | địa chỉ.
 */
final class DemoReferenceData {

    private static final String[] AUTHORS = {
            "Robert C. Martin|1952||Hoa Kỳ|",
            "Andrew Hunt, David Thomas|||Hoa Kỳ|",
            "Eric Freeman, Elisabeth Robson|||Hoa Kỳ|",
            "Kathy Sierra, Bert Bates|||Hoa Kỳ|",
            "Phạm Hữu Khang|||Việt Nam|",
            "Đỗ Xuân Lôi|||Việt Nam|",
            "Lê Hoàng Long|||Việt Nam|",
            "Martin Kleppmann|||Anh|",
            "Ngô Sĩ Liên|||Việt Nam|",
            "Trần Trọng Kim|1883|1953|Việt Nam|",
            "Trần Bạch Đằng|1926|2007|Việt Nam|",
            "Hồ Chí Minh|1890|1969|Việt Nam|",
            "Lê Văn Siêu|1911|1995|Việt Nam|",
            "Nguyễn Quang Ngọc|||Việt Nam|",
            "Nguyễn Khắc Thuần|||Việt Nam|",
            "Nguyễn Du|1766|1820|Việt Nam|",
            "Vũ Trọng Phụng|1912|1939|Việt Nam|",
            "Paulo Coelho|1947||Brazil|https://paulocoelho.com",
            "Nguyễn Nhật Ánh|1955||Việt Nam|",
            "J.K. Rowling|1965||Anh|https://www.jkrowling.com",
            "Victor Hugo|1802|1885|Pháp|",
            "Dale Carnegie|1888|1955|Hoa Kỳ|https://www.dalecarnegie.com",
            "Robert T. Kiyosaki|1947||Hoa Kỳ|",
            "Napoleon Hill|1883|1970|Hoa Kỳ|",
            "Jim Collins|1958||Hoa Kỳ|https://www.jimcollins.com",
            "Eric Ries|1978||Hoa Kỳ|https://theleanstartup.com",
            "N. Gregory Mankiw|1958||Hoa Kỳ|",
            "Philip Kotler|1931||Hoa Kỳ|",
            "Fred R. David|||Hoa Kỳ|",
            "Stephen Hawking|1942|2018|Anh|https://www.hawking.org.uk",
            "Yuval Noah Harari|1976||Israel|https://www.ynharari.com",
            "Charles Darwin|1809|1882|Anh|",
            "Jared Diamond|1937||Hoa Kỳ|",
            "Nguyễn Lân Dũng|1934||Việt Nam|",
            "Carl Sagan|1934|1996|Hoa Kỳ|",
            "Tô Hoài|1920|2014|Việt Nam|",
            "Fujiko F. Fujio|1933|1996|Nhật Bản|",
            "Gosho Aoyama|1963||Nhật Bản|",
            "Antoine de Saint-Exupéry|1900|1944|Pháp|",
            "E. B. White|1899|1985|Hoa Kỳ|",
            "Daniel Kahneman|1934|2024|Hoa Kỳ|",
            "Minh Niệm|||Việt Nam|",
            "Mark Manson|1984||Hoa Kỳ|https://markmanson.net",
            "Viktor E. Frankl|1905|1997|Áo|",
            "Gustave Le Bon|1841|1931|Pháp|",
            "Carol S. Dweck|1946||Hoa Kỳ|",
            "Nguyễn Khắc Viện|1913|1997|Việt Nam|",
            "James Clear|1986||Hoa Kỳ|https://jamesclear.com",
            "E. H. Gombrich|1909|2001|Anh|",
            "Chu Quang Trứ|||Việt Nam|",
            "Nguyễn Quân|||Việt Nam|",
            "John Berger|1926|2017|Anh|",
            "Trần Văn Thắng|||Việt Nam|",
            "Nguyễn Thị Nhung|||Việt Nam|",
            "Betty Edwards|1926|2020|Hoa Kỳ|",
            "Nguyễn Bá Lăng|||Việt Nam|",
    };

    private static final String[] PUBLISHERS = {
            "Pearson|Anh|1844|https://www.pearson.com|80 Strand, London WC2R 0RL, Anh",
            "Addison-Wesley|Hoa Kỳ|1942||Boston, Massachusetts, Hoa Kỳ",
            "O'Reilly Media|Hoa Kỳ|1978|https://www.oreilly.com|141 Stony Circle, Santa Rosa, California, Hoa Kỳ",
            "NXB Giáo dục Việt Nam|Việt Nam|1957|https://nxbgd.vn|81 Trần Hưng Đạo, Hoàn Kiếm, Hà Nội",
            "NXB Đại học Quốc gia Hà Nội|Việt Nam|1999|https://press.vnu.edu.vn|16 Hàng Chuối, Hai Bà Trưng, Hà Nội",
            "NXB Thanh niên|Việt Nam|1954||64 Bà Triệu, Hoàn Kiếm, Hà Nội",
            "NXB Khoa học xã hội|Việt Nam|||26 Lý Thường Kiệt, Hoàn Kiếm, Hà Nội",
            "NXB Văn học|Việt Nam|1957||18 Nguyễn Trường Tộ, Ba Đình, Hà Nội",
            "NXB Kim Đồng|Việt Nam|1957|https://nxbkimdong.com.vn|55 Quang Trung, Hai Bà Trưng, Hà Nội",
            "NXB Chính trị quốc gia Sự thật|Việt Nam|1945|https://nxbctqg.org.vn|6/86 Duy Tân, Cầu Giấy, Hà Nội",
            "NXB Trẻ|Việt Nam|1981|https://www.nxbtre.com.vn|161B Lý Chính Thắng, Quận 3, TP.HCM",
            "First News - Trí Việt|Việt Nam|1992|https://firstnews.com.vn|11H Nguyễn Thị Minh Khai, Quận 1, TP.HCM",
            "NXB Lao động|Việt Nam|||175 Giảng Võ, Đống Đa, Hà Nội",
            "Alpha Books|Việt Nam||https://alphabooks.vn|Hà Nội",
            "NXB Thống kê|Việt Nam|||Hà Nội",
            "NXB Lao động - Xã hội|Việt Nam|||Hà Nội",
            "NXB Kinh tế TP.HCM|Việt Nam|||TP.HCM",
            "Nhã Nam|Việt Nam|2004|https://nhanam.vn|59 Đỗ Quang, Trung Hòa, Cầu Giấy, Hà Nội",
            "NXB Thế giới|Việt Nam|1957|https://thegioipublishers.vn|46 Trần Hưng Đạo, Hoàn Kiếm, Hà Nội",
            "Random House|Hoa Kỳ|1927|https://www.penguinrandomhouse.com|1745 Broadway, New York, Hoa Kỳ",
            "HarperCollins|Hoa Kỳ|1989|https://www.harpercollins.com|195 Broadway, New York, Hoa Kỳ",
            "NXB Tổng hợp TP.HCM|Việt Nam|||62 Nguyễn Thị Minh Khai, Quận 1, TP.HCM",
            "NXB Tri thức|Việt Nam|||53 Nguyễn Du, Hai Bà Trưng, Hà Nội",
            "Avery|Hoa Kỳ|||New York, Hoa Kỳ",
            "NXB Mỹ thuật|Việt Nam|||Hà Nội",
            "Penguin Books|Anh|1935|https://www.penguin.co.uk|80 Strand, London WC2R 0RL, Anh",
            "NXB Văn hóa - Thông tin|Việt Nam|||Hà Nội",
            "NXB Âm nhạc|Việt Nam|||Hà Nội",
            "TarcherPerigee|Hoa Kỳ|||New York, Hoa Kỳ",
            "NXB Xây dựng|Việt Nam|||37 Lê Đại Hành, Hai Bà Trưng, Hà Nội",
    };

    private DemoReferenceData() {
    }

    static List<AuthorPlan> authors() {
        List<AuthorPlan> out = new ArrayList<>(AUTHORS.length);
        for (String row : AUTHORS) {
            String[] f = row.split("\\|", -1);
            if (f.length != 5) throw new IllegalStateException("Dòng tác giả demo sai định dạng: " + row);
            out.add(new AuthorPlan(f[0], integer(f[1]), integer(f[2]), blank(f[3]), blank(f[4])));
        }
        return out;
    }

    static List<PublisherPlan> publishers() {
        List<PublisherPlan> out = new ArrayList<>(PUBLISHERS.length);
        for (String row : PUBLISHERS) {
            String[] f = row.split("\\|", -1);
            if (f.length != 5) throw new IllegalStateException("Dòng nhà xuất bản demo sai định dạng: " + row);
            out.add(new PublisherPlan(f[0], blank(f[1]), integer(f[2]), blank(f[3]), blank(f[4])));
        }
        return out;
    }

    private static Integer integer(String s) {
        return s.isBlank() ? null : Integer.valueOf(s);
    }

    private static String blank(String s) {
        return s.isBlank() ? null : s;
    }
}
